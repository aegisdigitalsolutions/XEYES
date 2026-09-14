package com.rfmapper.core.export

import com.rfmapper.core.model.ExportKind
import com.rfmapper.core.model.ExportManifest
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SessionSummary
import com.rfmapper.core.model.csv.ObservationCsvCodec
import java.io.OutputStream

/**
 * Writes the V1 observation package described in `docs/16-export-package-specification.md`.
 *
 * Everything is streamed: each observation is encoded and written as it is pulled from the source,
 * so peak memory is independent of package size. The CSV and JSON entries each get their own pass
 * over the source (see [ObservationSource]), and the two passes are cross-checked so a disagreement
 * becomes an error rather than a package whose two views of the same data differ.
 */
class ExportEngineV1 : ExportEngine {

    override val version: String = "export_engine-1.0.0"

    override fun write(
        request: ExportRequest,
        source: ObservationSource,
        sink: ExportSink,
    ): ExportResult {
        val digests = LinkedHashMap<String, String>()
        val sizes = LinkedHashMap<String, Long>()

        val manifest = ExportPackage.manifestFor(request)
        writeEntry(sink, ExportPackage.MANIFEST, digests, sizes) { out ->
            out.writeUtf8(RfMapperJson.pretty.encodeToString(ExportManifest.serializer(), manifest))
            out.writeUtf8("\n")
        }

        val csvPass = StreamStatistics()
        writeEntry(sink, ExportPackage.OBSERVATIONS_CSV, digests, sizes) { out ->
            out.writeUtf8(ObservationCsvCodec.header)
            out.writeUtf8("\n")
            forEachOrdered(source) { observation ->
                csvPass.accept(observation)
                out.writeUtf8(ObservationCsvCodec.encode(observation))
                out.writeUtf8("\n")
            }
        }

        val jsonPass = StreamStatistics()
        writeEntry(sink, ExportPackage.OBSERVATIONS_JSON, digests, sizes) { out ->
            out.writeUtf8("[")
            forEachOrdered(source) { observation ->
                if (jsonPass.count > 0) out.writeUtf8(",")
                jsonPass.accept(observation)
                out.writeUtf8("\n")
                out.writeUtf8(RfMapperJson.compact.encodeToString(Observation.serializer(), observation))
            }
            if (jsonPass.count > 0) out.writeUtf8("\n")
            out.writeUtf8("]\n")
        }

        if (csvPass.rowFingerprint != jsonPass.rowFingerprint || csvPass.count != jsonPass.count) {
            throw ExportException(
                "the CSV and JSON passes disagree (${csvPass.count} vs ${jsonPass.count} rows): " +
                    "the observation source is not stable across passes",
            )
        }

        writeEntry(sink, ExportPackage.OBSERVER, digests, sizes) { out ->
            out.writeUtf8(RfMapperJson.pretty.encodeToString(ObserverIdentity.serializer(), request.observer))
            out.writeUtf8("\n")
        }

        if (request.sessions.isNotEmpty()) {
            writeEntry(sink, ExportPackage.SESSIONS, digests, sizes) { out ->
                val serializer = kotlinx.serialization.builtins.ListSerializer(SessionSummary.serializer())
                out.writeUtf8(RfMapperJson.pretty.encodeToString(serializer, request.sessions))
                out.writeUtf8("\n")
            }
        }

        verifyAgainstManifest(manifest, csvPass)

        // Written last, so its presence means every other entry completed.
        writeEntry(sink, ExportPackage.CHECKSUM, digests, sizes) { out ->
            out.writeUtf8(Sha256.checksumFile(digests))
        }

        sink.finish()

        return ExportResult(
            packageName = ExportPackage.fileName(
                observerId = request.observer.observerId,
                dateStamp = dateStampFor(request),
                sessionId = request.sessionIdForName.takeIf { request.exportKind == ExportKind.SESSION },
            ),
            observationsWritten = csvPass.count,
            entryDigests = digests,
            bytesPerEntry = sizes,
        )
    }

    private inline fun forEachOrdered(source: ObservationSource, action: (Observation) -> Unit) {
        val iterator = source.open()
        var previousTimestamp: String? = null
        var previousId: String? = null
        while (iterator.hasNext()) {
            val observation = iterator.next()
            if (previousTimestamp != null) {
                val comparison = compareValuesBy(
                    previousTimestamp to previousId!!,
                    observation.timestampUtc to observation.observationId,
                    { it.first },
                    { it.second },
                )
                if (comparison > 0) {
                    throw ExportException(
                        "observations must be ordered by (timestamp_utc, observation_id): " +
                            "$previousId preceded ${observation.observationId}",
                    )
                }
            }
            previousTimestamp = observation.timestampUtc
            previousId = observation.observationId
            action(observation)
        }
    }

    private fun dateStampFor(request: ExportRequest): String =
        request.dateRange?.from?.take(10)
            ?: request.summary.firstObservationUtc?.take(10)
            ?: Iso8601.utcDateStamp(request.createdAtEpochMillis)

    /**
     * Checks the precomputed manifest against what was actually streamed. A disagreement means the
     * database aggregates and the row query disagree, which would otherwise ship as a package whose
     * manifest quietly misstates its contents.
     */
    private fun verifyAgainstManifest(manifest: ExportManifest, observed: StreamStatistics) {
        if (manifest.observationCount != observed.count) {
            throw ExportException(
                "manifest declares ${manifest.observationCount} observations but ${observed.count} were written",
            )
        }
        if (observed.count > 0) {
            if (manifest.firstObservation != observed.firstTimestamp) {
                throw ExportException(
                    "manifest first_observation ${manifest.firstObservation} != streamed ${observed.firstTimestamp}",
                )
            }
            if (manifest.lastObservation != observed.lastTimestamp) {
                throw ExportException(
                    "manifest last_observation ${manifest.lastObservation} != streamed ${observed.lastTimestamp}",
                )
            }
        }
        if (manifest.countsBySensorType.isNotEmpty() &&
            manifest.countsBySensorType != observed.countsBySensorType
        ) {
            throw ExportException(
                "manifest counts_by_sensor_type ${manifest.countsBySensorType} != " +
                    "streamed ${observed.countsBySensorType}",
            )
        }
        if (manifest.groundTruthCount != observed.groundTruthCount) {
            throw ExportException(
                "manifest ground_truth_count ${manifest.groundTruthCount} != " +
                    "streamed ${observed.groundTruthCount}",
            )
        }
        observed.observerIds.firstOrNull { it != manifest.observerId }?.let { stray ->
            throw ExportException(
                "package declares observer ${manifest.observerId} but contains a row from $stray",
            )
        }
    }

    private inline fun writeEntry(
        sink: ExportSink,
        name: String,
        digests: MutableMap<String, String>,
        sizes: MutableMap<String, Long>,
        body: (OutputStream) -> Unit,
    ) {
        val raw = sink.openEntry(name)
        val digesting = DigestingOutputStream(raw)
        try {
            body(digesting)
            digesting.flush()
        } finally {
            raw.close()
        }
        digests[name] = digesting.digestHex()
        sizes[name] = digesting.bytesWritten
    }

    private fun OutputStream.writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Running statistics over a streamed pass, including a rolling fingerprint of the row sequence.
     * The fingerprint lets two passes be compared without retaining either of them.
     */
    private class StreamStatistics {
        var count: Long = 0
            private set
        var firstTimestamp: String? = null
            private set
        var lastTimestamp: String? = null
            private set
        var groundTruthCount: Long = 0
            private set
        var rowFingerprint: Long = FNV_OFFSET_BASIS
            private set

        val countsBySensorType = LinkedHashMap<String, Long>()
        val observerIds = LinkedHashSet<String>()

        fun accept(observation: Observation) {
            count++
            if (firstTimestamp == null) firstTimestamp = observation.timestampUtc
            lastTimestamp = observation.timestampUtc
            countsBySensorType.merge(observation.sensorType.name, 1L, Long::plus)
            if (observation.sampleKind == SampleKind.GROUND_TRUTH) groundTruthCount++
            observerIds += observation.observerId
            for (char in observation.observationId) {
                rowFingerprint = (rowFingerprint xor char.code.toLong()) * FNV_PRIME
            }
        }

        private companion object {
            const val FNV_OFFSET_BASIS = -3_750_763_034_362_895_579L
            const val FNV_PRIME = 1_099_511_628_211L
        }
    }
}
