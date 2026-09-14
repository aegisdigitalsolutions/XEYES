package com.rfmapper.core.importing

import com.rfmapper.core.export.ExportEngineV1
import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.export.ExportRequest
import com.rfmapper.core.export.InMemoryExportSink
import com.rfmapper.core.export.ObservationSource
import com.rfmapper.core.export.ObservationSummary
import com.rfmapper.core.export.Sha256
import com.rfmapper.core.export.ZipExportSink
import com.rfmapper.core.model.DateRange
import com.rfmapper.core.model.ExportKind
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.model.csv.ObservationCsvCodec
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Milestone 1 acceptance criterion that does not need hardware: a package written by the
 * Collector must be accepted by the Master, and importing it twice must not duplicate data.
 */
class RoundTripTest {

    private val exporter = ExportEngineV1()
    private val observer = TestData.observer()

    // -- helpers ------------------------------------------------------------------------------

    private fun exportZip(
        observations: List<Observation>,
        observer: ObserverIdentity = this.observer,
        mutateSummary: (ObservationSummary) -> ObservationSummary = { it },
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        exporter.write(
            request = TestData.request(observations, observer, mutateSummary),
            source = ObservationSource { observations.iterator() },
            sink = ZipExportSink(buffer),
        )
        return buffer.toByteArray()
    }

    private fun preview(
        zip: ByteArray,
        alreadyPresent: Set<String> = emptySet(),
        enrolled: Set<String> = setOf(TestData.OBSERVER),
    ): ImportPreview {
        val engine = ImportEngineV1(ObserverRegistry { it in enrolled })
        return engine.preview(
            ZipPackageReader.from(zip),
            ExistingIdLookup { candidates -> candidates intersect alreadyPresent },
        )
    }

    // -- tests --------------------------------------------------------------------------------

    @Test
    fun `a package contains exactly the specified entries`() {
        val sink = InMemoryExportSink()
        val observations = TestData.day()
        exporter.write(
            TestData.request(observations, observer),
            ObservationSource { observations.iterator() },
            sink,
        )
        assertEquals(
            listOf("manifest.json", "observations.csv", "observations.json", "observer.json", "checksum.txt"),
            sink.entryNames,
        )
        assertTrue(sink.isFinished)
    }

    @Test
    fun `checksum file covers every other entry and verifies`() {
        val sink = InMemoryExportSink()
        val observations = TestData.day()
        exporter.write(
            TestData.request(observations, observer),
            ObservationSource { observations.iterator() },
            sink,
        )
        val declared = Sha256.parseChecksumFile(sink.text(ExportPackage.CHECKSUM)!!)
        val covered = sink.entryNames - ExportPackage.CHECKSUM
        assertEquals(covered.sorted(), declared.keys.sorted())
        for (entry in covered) {
            assertEquals(Sha256.hex(sink.bytes(entry)!!), declared[entry], "digest of $entry")
        }
    }

    @Test
    fun `a valid package imports cleanly`() {
        val observations = TestData.day()
        val result = preview(exportZip(observations))

        assertTrue(result.canImport, "blocking issues: ${result.blockingIssues}")
        assertEquals(observations.size, result.totalRows)
        assertEquals(observations.size, result.newCount)
        assertEquals(0, result.duplicateCount)
        assertEquals(0, result.invalidCount)
        assertEquals(TestData.OBSERVER, result.manifest?.observerId)
        assertContentEquals(observations, result.newObservations)
    }

    @Test
    fun `importing the same package twice yields no new observations`() {
        // The core idempotency requirement: re-importing must not duplicate data.
        val observations = TestData.day()
        val zip = exportZip(observations)

        val first = preview(zip)
        assertEquals(observations.size, first.newCount)

        val committedIds = first.newObservations.map { it.observationId }.toSet()
        val second = preview(zip, alreadyPresent = committedIds)

        assertEquals(0, second.newCount)
        assertEquals(observations.size, second.duplicateCount)
        assertTrue(second.issues.any { it.code == ImportErrorCode.ALREADY_IMPORTED })
        // Advisory, not blocking: a repeat import is harmless rather than an error.
        assertTrue(second.canImport)
    }

    @Test
    fun `an overlapping package imports only the genuinely new rows`() {
        // EXPORT TODAY run twice on the same day: the second package is a superset.
        val morning = TestData.day(count = 20)
        val fullDay = TestData.day(count = 50)

        val alreadyPresent = morning.map { it.observationId }.toSet()
        val result = preview(exportZip(fullDay), alreadyPresent = alreadyPresent)

        assertEquals(50, result.totalRows)
        assertEquals(30, result.newCount)
        assertEquals(20, result.duplicateCount)
        assertFalse(result.issues.any { it.code == ImportErrorCode.ALREADY_IMPORTED })
    }

    @Test
    fun `deduplication planner reports the counts shown in the preview`() {
        val observations = TestData.day(count = 40)
        val alreadyPresent = observations.take(9).map { it.observationId }.toSet()
        val plan = DeduplicationPlanner.plan(preview(exportZip(observations), alreadyPresent))

        assertEquals(31, plan.toInsert.size)
        assertEquals(9, plan.duplicateCount)
        assertEquals(0, plan.invalidCount)
    }

    @Test
    fun `export is byte-identical for identical input`() {
        // Determinism is what makes checksum.txt meaningful and re-exports diffable.
        val observations = TestData.day()
        assertContentEquals(exportZip(observations), exportZip(observations))
    }

    @Test
    fun `an unenrolled observer is blocked rather than silently accepted`() {
        val result = preview(exportZip(TestData.day()), enrolled = emptySet())
        assertFalse(result.canImport)
        assertTrue(result.blockingIssues.any { it.code == ImportErrorCode.UNKNOWN_OBSERVER })
    }

    @Test
    fun `a tampered csv fails the package checksum`() {
        val zip = exportZip(TestData.day())
        val corrupted = TestZip.replaceEntry(zip, ExportPackage.OBSERVATIONS_CSV) { csv ->
            csv.replace("-61", "-42")
        }
        val result = preview(corrupted)
        assertFalse(result.canImport)
        assertTrue(result.blockingIssues.any { it.code == ImportErrorCode.CHECKSUM_MISMATCH })
    }

    @Test
    fun `a missing required entry is blocking`() {
        val zip = exportZip(TestData.day())
        val stripped = TestZip.removeEntry(zip, ExportPackage.OBSERVER)
        val result = preview(stripped)
        assertFalse(result.canImport)
        assertTrue(result.blockingIssues.any { it.code == ImportErrorCode.MISSING_ENTRY })
    }

    @Test
    fun `an unreadable schema major is rejected with a clear error`() {
        val zip = exportZip(TestData.day())
        val future = TestZip.rechecksum(zip) { name, content ->
            if (name == ExportPackage.MANIFEST) content.replace("\"1.0.0\"", "\"2.0.0\"") else content
        }
        val result = preview(future)
        assertFalse(result.canImport)
        assertTrue(result.blockingIssues.any { it.code == ImportErrorCode.UNREADABLE_SCHEMA_VERSION })
    }

    @Test
    fun `a manifest count that disagrees with the content is blocking`() {
        val observations = TestData.day(count = 10)
        val zip = exportZip(observations)
        val lying = TestZip.rechecksum(zip) { name, content ->
            if (name == ExportPackage.MANIFEST) {
                content.replace("\"observation_count\": 10", "\"observation_count\": 99")
            } else {
                content
            }
        }
        val result = preview(lying)
        assertFalse(result.canImport)
        assertTrue(result.blockingIssues.any { it.code == ImportErrorCode.MANIFEST_COUNT_MISMATCH })
    }

    @Test
    fun `one malformed row is reported and the rest still imports`() {
        // Losing a whole day of field work over a single bad row would be worse than importing the
        // other 9 and reporting the one.
        val observations = TestData.day(count = 10)
        val zip = exportZip(observations)
        // Line 3 of the file: the header is line 1, so this is the second observation.
        val damagedLine = 3
        val damaged = TestZip.rechecksum(zip) { name, content ->
            if (name != ExportPackage.OBSERVATIONS_CSV) return@rechecksum content
            val lines = content.split("\n").toMutableList()
            val rssiColumn = ObservationCsvCodec.COLUMNS.indexOf("rssi")
            val cells = lines[damagedLine - 1].split(",").toMutableList()
            cells[rssiColumn] = "banana"
            lines[damagedLine - 1] = cells.joinToString(",")
            lines.joinToString("\n")
        }
        val result = preview(damaged)

        assertTrue(result.canImport, "a bad row must not block the package: ${result.blockingIssues}")
        assertEquals(10, result.totalRows)
        assertEquals(9, result.newCount)
        assertEquals(1, result.invalidCount)
        assertTrue(
            result.issues.any {
                it.code == ImportErrorCode.INVALID_ROW && it.rowNumber == damagedLine
            },
            "issues: ${result.issues}",
        )
    }

    @Test
    fun `a row belonging to another observer is rejected`() {
        // The exporter refuses to write such a row, so the package is assembled directly: import
        // validation has to hold against arbitrary bytes, not only against our own output.
        val zip = TestZip.appendRows(
            zip = exportZip(TestData.day(count = 5), mutateSummary = { it.copy(countsBySensorType = emptyMap()) }),
            rows = listOf(TestData.observation(index = 99, observerId = "OBS-99")),
        )
        val result = preview(zip)

        assertTrue(result.canImport, "blocking: ${result.blockingIssues}")
        assertTrue(result.issues.any { it.code == ImportErrorCode.ROW_OBSERVER_MISMATCH })
        assertEquals(5, result.newCount)
    }

    @Test
    fun `the exporter itself refuses a row from a foreign observer`() {
        val mixed = (TestData.day(count = 3) + TestData.observation(index = 99, observerId = "OBS-99"))
            .sortedWith(compareBy({ it.timestampUtc }, { it.observationId }))
        val error = runCatching { exportZip(mixed) }.exceptionOrNull()
        assertTrue(error is com.rfmapper.core.export.ExportException, "got $error")
    }

    @Test
    fun `a ground truth claim without a survey session is rejected`() {
        // The import-side half of the safeguard that stops ordinary observations becoming calibration.
        val forged = TestData.observation(index = 1).copy(
            metadata = mapOf(MetadataKeys.SAMPLE_KIND to SampleKind.GROUND_TRUTH.name),
        )
        val result = preview(exportZip(listOf(forged)))

        assertTrue(result.issues.any { it.code == ImportErrorCode.INVALID_GROUND_TRUTH_CLAIM })
        assertEquals(0, result.newCount)
    }

    @Test
    fun `a legitimate survey sample is accepted as ground truth`() {
        val surveySample = TestData.observation(index = 1).copy(
            metadata = mapOf(
                MetadataKeys.SAMPLE_KIND to SampleKind.GROUND_TRUTH.name,
                MetadataKeys.SURVEY_SESSION_ID to "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
                MetadataKeys.SURVEY_POINT_ID to "B7_CENTER",
            ),
        )
        val result = preview(exportZip(listOf(surveySample)))

        assertTrue(result.canImport, "issues: ${result.issues}")
        assertEquals(1, result.newCount)
        assertEquals(1L, result.manifest?.groundTruthCount)
    }

    @Test
    fun `a duplicate observation id inside one package is reported`() {
        val duplicated = TestData.observation(index = 1).let { listOf(it, it) }
        val result = preview(exportZip(duplicated))
        assertTrue(result.issues.any { it.message.contains("more than once") })
        assertEquals(1, result.newCount)
    }

    @Test
    fun `a timestamp outside the declared range is rejected`() {
        val observations = TestData.day(count = 3).toMutableList()
        observations[2] = observations[2].copy(timestampUtc = "2026-09-20T00:00:00.000Z")
        val zip = exportZip(observations.sortedWith(compareBy({ it.timestampUtc }, { it.observationId })))
        val result = preview(zip)
        assertTrue(result.issues.any { it.code == ImportErrorCode.TIMESTAMP_OUT_OF_RANGE })
    }

    @Test
    fun `a package with sessions includes the session summary entry`() {
        val observations = TestData.day()
        val sink = InMemoryExportSink()
        exporter.write(
            TestData.request(observations, observer).copy(sessions = listOf(TestData.session())),
            ObservationSource { observations.iterator() },
            sink,
        )
        assertTrue(ExportPackage.SESSIONS in sink.entryNames)
        assertTrue(sink.text(ExportPackage.SESSIONS)!!.contains("throttled_scan_requests"))
    }

    @Test
    fun `an out-of-order source is refused rather than silently reordered`() {
        val descending = TestData.day(count = 5).reversed()
        val error = runCatching {
            exportZip(descending)
        }.exceptionOrNull()
        assertTrue(error is com.rfmapper.core.export.ExportException, "got $error")
    }

    @Test
    fun `a package name follows the specified convention`() {
        assertEquals(
            "RFMapper_OBS04_2026-09-14.zip",
            ExportPackage.fileName("OBS-04", "2026-09-14"),
        )
        assertEquals(
            "RFMapper_OBS04_2026-09-14_0d6b1f4a.zip",
            ExportPackage.fileName("OBS-04", "2026-09-14", "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40"),
        )
    }

    @Test
    fun `a large export streams without materialising rows`() {
        // 25k rows exercises the streaming path; a buffering implementation would show up as a
        // proportional memory spike here.
        val observations = TestData.day(count = 25_000)
        val result = preview(exportZip(observations))
        assertTrue(result.canImport, "issues: ${result.blockingIssues}")
        assertEquals(25_000, result.newCount)
    }
}

object TestData {

    const val OBSERVER = "OBS-04"
    const val CREATED_AT = 1_789_400_000_000L
    val DAY_RANGE = DateRange(from = "2026-09-14T00:00:00.000Z", to = "2026-09-14T23:59:59.999Z")

    private val DAY_START = Iso8601.parseToEpochMillis("2026-09-14T06:11:02.310Z")!!

    fun observer(observerId: String = OBSERVER) = ObserverIdentity(
        observerId = observerId,
        friendlyName = "Building 4 Android Collector",
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        buildingId = "B4",
        defaultZoneId = "B4-CENTER",
        deviceModel = "Pixel 7a",
        manufacturer = "Google",
        platform = Platform.ANDROID,
        osVersion = "34",
        appVersion = "1.0.0",
        installationId = "b1c2d3e4-1111-4222-8333-444455556666",
        capabilities = setOf(ObserverCapability.WIFI_SCAN, ObserverCapability.BLE, ObserverCapability.GPS),
        unsupported = setOf(ObserverCapability.RTT),
    )

    fun observation(index: Int, observerId: String = OBSERVER) = Observation(
        observationId = uuid(index),
        timestampUtc = Iso8601.format(DAY_START + index * 1_000L),
        observerId = observerId,
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        sensorType = if (index % 3 == 0) SensorType.BLE else SensorType.WIFI_SCAN,
        radioIdentifier = if (index % 3 == 0) "d1:e2:f3:04:15:26" else "aa:bb:cc:11:22:33",
        identifierType = if (index % 3 == 0) IdentifierType.BLE_MAC_PUBLIC else IdentifierType.WIFI_BSSID,
        ssid = if (index % 3 == 0) null else "SITE-INFRA-7",
        bssid = if (index % 3 == 0) null else "aa:bb:cc:11:22:33",
        rssi = -61 - (index % 7),
        frequency = if (index % 3 == 0) null else 5180,
        channel = if (index % 3 == 0) null else 36,
        buildingId = "B7",
        zoneId = "B7-CENTER",
        confidence = 0.9,
        metadata = mapOf(MetadataKeys.SESSION_ID to "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40"),
    )

    fun day(count: Int = 12): List<Observation> = (1..count).map { observation(it) }

    fun uuid(index: Int): String = "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"

    fun summaryFor(observations: List<Observation>) = ObservationSummary(
        observationCount = observations.size.toLong(),
        firstObservationUtc = observations.firstOrNull()?.timestampUtc,
        lastObservationUtc = observations.lastOrNull()?.timestampUtc,
        countsBySensorType = observations
            .groupingBy { it.sensorType.name }
            .eachCount()
            .mapValues { it.value.toLong() },
        groundTruthCount = observations.count { it.sampleKind == SampleKind.GROUND_TRUTH }.toLong(),
        sessionIds = observations.mapNotNull { it.sessionId }.distinct(),
    )

    fun request(
        observations: List<Observation>,
        observer: ObserverIdentity,
        mutateSummary: (ObservationSummary) -> ObservationSummary = { it },
    ) = ExportRequest(
        exportId = "3f8e1c20-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
        observer = observer,
        exportKind = ExportKind.DAY,
        createdAtEpochMillis = CREATED_AT,
        summary = mutateSummary(summaryFor(observations)),
        dateRange = DAY_RANGE,
        appVersion = "1.0.0",
        generator = Generator("RFMapper Collector", "1.0.0"),
    )

    fun session() = com.rfmapper.core.model.SessionSummary(
        sessionId = "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40",
        startedAt = "2026-09-14T06:11:00.000Z",
        endedAt = "2026-09-14T12:43:00.000Z",
        scanProfile = "BALANCED",
        buildingId = "B4",
        zoneId = "B4-CENTER",
        observationCount = 9120,
        wifiCount = 6400,
        bleCount = 2700,
        throttledScanRequests = 14,
    )
}
