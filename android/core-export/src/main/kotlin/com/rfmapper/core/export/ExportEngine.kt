package com.rfmapper.core.export

import com.rfmapper.core.model.DateRange
import com.rfmapper.core.model.ExportKind
import com.rfmapper.core.model.ExportManifest
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.SessionSummary

/**
 * Aggregate facts about the rows an export will contain, obtained from cheap database aggregates
 * before streaming begins.
 *
 * The manifest has to be written before the rows (it is the first entry in the archive), so the
 * counts cannot come from the stream itself. Precomputing them has a useful side effect: the writer
 * compares them against what it actually streamed and fails on disagreement, which turns a database
 * aggregate bug into a loud error rather than a manifest that quietly lies.
 */
data class ObservationSummary(
    val observationCount: Long,
    val firstObservationUtc: String?,
    val lastObservationUtc: String?,
    val countsBySensorType: Map<String, Long> = emptyMap(),
    val groundTruthCount: Long = 0,
    val sessionIds: List<String> = emptyList(),
) {
    init {
        require(observationCount >= 0) { "observationCount must not be negative" }
        if (observationCount > 0) {
            require(firstObservationUtc != null && lastObservationUtc != null) {
                "a non-empty export must know its first and last observation timestamps"
            }
        }
    }
}

data class ExportRequest(
    val exportId: String,
    val observer: ObserverIdentity,
    val exportKind: ExportKind,
    val createdAtEpochMillis: Long,
    val summary: ObservationSummary,
    val dateRange: DateRange? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val appVersion: String,
    val generator: Generator,
    /** Included in the package file name for a session export. */
    val sessionIdForName: String? = null,
)

data class ExportResult(
    val packageName: String,
    val observationsWritten: Long,
    val entryDigests: Map<String, String>,
    val bytesPerEntry: Map<String, Long>,
)

class ExportException(message: String) : Exception(message)

/**
 * Supplies the rows of an export, in `(timestamp_utc, observation_id)` order.
 *
 * [open] may be called more than once and must yield the same sequence each time. The CSV and JSON
 * entries are separate zip entries written sequentially, so producing both from a single iterator
 * would mean buffering one of them in memory — roughly 300 MB for a 500k-row day. Instead each
 * entry gets its own pass.
 *
 * Two passes are safe precisely because RAW is append-only and the export's time range is closed:
 * the same query over the same immutable rows cannot return different results. The writer verifies
 * this rather than assuming it.
 */
fun interface ObservationSource {
    fun open(): Iterator<Observation>
}

interface ExportEngine {
    val version: String

    /**
     * Writes a complete observation package into [sink] using constant memory: rows are streamed
     * from [source] straight through a digesting stream into the sink, never materialised.
     *
     * Row order is verified during streaming, because deterministic order is what makes an export
     * reproducible and `checksum.txt` meaningful.
     */
    fun write(request: ExportRequest, source: ObservationSource, sink: ExportSink): ExportResult
}

object ExportPackage {
    const val MANIFEST = "manifest.json"
    const val OBSERVATIONS_CSV = "observations.csv"
    const val OBSERVATIONS_JSON = "observations.json"
    const val OBSERVER = "observer.json"
    const val SESSIONS = "sessions.json"
    const val CHECKSUM = "checksum.txt"

    val REQUIRED_ENTRIES = listOf(MANIFEST, OBSERVATIONS_CSV, OBSERVATIONS_JSON, OBSERVER, CHECKSUM)

    /**
     * `RFMapper_OBS04_2026-09-14.zip`, or `RFMapper_OBS04_2026-09-14_0d6b1f4a.zip` for a session
     * export.
     */
    fun fileName(observerId: String, dateStamp: String, sessionId: String? = null): String {
        val safeObserver = observerId.filter { it.isLetterOrDigit() }
        val suffix = sessionId?.take(8)?.let { "_$it" } ?: ""
        return "RFMapper_${safeObserver}_$dateStamp$suffix.zip"
    }

    fun manifestFor(request: ExportRequest): ExportManifest = ExportManifest(
        exportId = request.exportId,
        observerId = request.observer.observerId,
        createdAt = com.rfmapper.core.model.Iso8601.format(request.createdAtEpochMillis),
        exportKind = request.exportKind,
        dateRange = request.dateRange,
        observationCount = request.summary.observationCount,
        firstObservation = request.summary.firstObservationUtc,
        lastObservation = request.summary.lastObservationUtc,
        appVersion = request.appVersion,
        platform = request.observer.platform.name.lowercase(),
        osVersion = request.observer.osVersion,
        deviceModel = request.observer.deviceModel,
        installationId = request.observer.installationId,
        sessionIds = request.summary.sessionIds,
        countsBySensorType = request.summary.countsBySensorType,
        groundTruthCount = request.summary.groundTruthCount,
        generator = request.generator,
    )
}
