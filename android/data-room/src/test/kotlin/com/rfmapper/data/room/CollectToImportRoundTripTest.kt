package com.rfmapper.data.room

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.export.ZipExportSink
import com.rfmapper.core.importing.ImportErrorCode
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.radio.CollectionEngine
import com.rfmapper.core.radio.IdGenerator
import com.rfmapper.core.radio.ObservationFactory
import com.rfmapper.core.radio.ObserverPlace
import com.rfmapper.core.radio.ScanProfile
import com.rfmapper.core.radio.SessionContext
import com.rfmapper.core.radio.SurveyContext
import com.rfmapper.data.room.raw.ImportBatchEntity
import com.rfmapper.data.room.raw.SessionEntity
import com.rfmapper.data.room.reference.DeviceIdentifierEntity
import com.rfmapper.data.room.reference.ManagedDeviceEntity
import com.rfmapper.data.room.reference.ObserverEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Milestone 1 acceptance criterion that needs no hardware, end to end through real storage:
 * samples are collected into the Collector's database, exported as a package, and imported into a
 * separate Master database. Importing the same package twice must add nothing.
 *
 * This is the test that would catch a mismatch between what the database stores and what the
 * package format expects — the kind of fault that otherwise only shows up after a day of field
 * work has already been collected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectToImportRoundTripTest {

    private lateinit var collector: RfMapperDatabase
    private lateinit var master: RfMapperDatabase
    private lateinit var collectorRepository: ObservationRepository
    private lateinit var masterRepository: ObservationRepository

    private val observer = RoomFixtures.observer()
    private val clock = FakeClock()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        collector = RfMapperDatabase.openInMemory(context)
        master = RfMapperDatabase.openInMemory(context)
        collectorRepository = ObservationRepository(collector.observationDao(), collector.sessionDao())
        masterRepository = ObservationRepository(master.observationDao(), master.sessionDao())
    }

    @After
    fun close() {
        collector.close()
        master.close()
    }

    // -- helpers ------------------------------------------------------------------------------------

    private class FakeClock(
        private var wall: Long = RoomFixtures.DAY_START,
        private var monotonic: Long = 86_400_000L,
    ) : com.rfmapper.core.radio.Clock {
        override fun wallClockMillis(): Long = wall
        override fun monotonicElapsedMillis(): Long = monotonic
        fun advance(millis: Long) {
            wall += millis
            monotonic += millis
        }
    }

    private suspend fun collect(
        wifiScans: Int,
        bleSightings: Int,
        survey: SurveyContext? = null,
    ): SessionEntity {
        val factory = ObservationFactory(observer, clock, IdGenerator.RANDOM)
        val engine = CollectionEngine(
            factory = factory,
            writer = collectorRepository,
            clock = clock,
            batchSize = 50,
        )
        val session = SessionContext(
            sessionId = RoomFixtures.SESSION,
            startedAtMillis = clock.wallClockMillis(),
            scanProfile = ScanProfile.BALANCED,
            place = ObserverPlace(buildingId = "B7", zoneId = "B7-CENTER"),
        )
        engine.start(session)
        survey?.let { engine.beginSurvey(it) }

        repeat(wifiScans) { index ->
            clock.advance(1_000)
            engine.submit(
                FakeSamples.wifi(
                    bssid = "aa:bb:cc:11:22:%02x".format(index % 8),
                    rssi = -55 - (index % 20),
                    wallClockMillis = clock.wallClockMillis(),
                    monotonicMillis = clock.monotonicElapsedMillis(),
                ),
            )
        }
        repeat(bleSightings) { index ->
            clock.advance(250)
            engine.submit(
                FakeSamples.ble(
                    mac = "d1:e2:f3:04:15:%02x".format(index % 5),
                    rssi = -75 - (index % 10),
                    wallClockMillis = clock.wallClockMillis(),
                    monotonicMillis = clock.monotonicElapsedMillis(),
                ),
            )
        }

        survey?.let { engine.endSurvey() }
        val summary = engine.stop()
        val entity = SessionEntity.from(summary, observer.observerId, session.startedAtMillis)
        collectorRepository.upsertSession(entity)
        return entity
    }

    private suspend fun exportDay(dateStamp: String = "2026-09-14"): Pair<String, ByteArray> {
        val exporter = PackageExporter(
            repository = collectorRepository,
            sessions = collector.sessionDao(),
            appVersion = "1.0.0",
            newExportId = { "3f8e1c20-4a5b-4c6d-8e9f-0a1b2c3d4e5f" },
            nowMillis = { RoomFixtures.DAY_START + 12 * 60 * 60 * 1000L },
        )
        val plan = exporter.planDay(observer, dateStamp)
        val buffer = ByteArrayOutputStream()
        exporter.write(plan, ZipExportSink(buffer))
        return plan.packageName to buffer.toByteArray()
    }

    private suspend fun enrolObserverOnMaster() {
        master.observerDao().upsert(
            ObserverEntity(
                observerId = observer.observerId,
                friendlyName = observer.friendlyName,
                observerDeviceType = observer.observerDeviceType.name,
                buildingId = observer.buildingId,
                defaultZoneId = observer.defaultZoneId,
                deviceModel = observer.deviceModel,
                manufacturer = observer.manufacturer,
                platform = observer.platform.name,
                osVersion = observer.osVersion,
                appVersion = observer.appVersion,
                installationId = observer.installationId,
                capabilities = observer.capabilities.map { it.name },
                unsupported = observer.unsupported.map { it.name },
                xCoordinate = null,
                yCoordinate = null,
                fixedObserver = false,
                enrolled = true,
                enrolledAtUtc = Iso8601.format(RoomFixtures.DAY_START),
                notes = null,
            ),
        )
    }

    private fun importer() = PackageImporter(
        repository = masterRepository,
        observers = master.observerDao(),
        devices = master.managedDeviceDao(),
        batches = master.importBatchDao(),
        nowMillis = { RoomFixtures.DAY_START + 20 * 60 * 60 * 1000L },
    )

    // -- tests --------------------------------------------------------------------------------------

    @Test
    fun `a collected day exports and imports cleanly`() = runTest {
        collect(wifiScans = 120, bleSightings = 240)
        assertEquals(360, collectorRepository.count())

        val (name, zip) = exportDay()
        assertEquals("RFMapper_OBS04_2026-09-14.zip", name)

        enrolObserverOnMaster()
        val preview = importer().preview(name, zip.inputStream())

        assertTrue(preview.canImport, "blocking: ${preview.preview.blockingIssues}")
        assertEquals(360, preview.preview.totalRows)
        assertEquals(360, preview.preview.newCount)
        assertEquals(0, preview.preview.invalidCount)

        val commit = importer().commit(preview, operator = "AM")
        assertEquals(360, commit.inserted)
        assertEquals(360, masterRepository.count())
    }

    @Test
    fun `importing the same package twice adds nothing`() = runTest {
        collect(wifiScans = 40, bleSightings = 60)
        val (name, zip) = exportDay()
        enrolObserverOnMaster()

        val first = importer().preview(name, zip.inputStream())
        importer().commit(first, operator = "AM")
        assertEquals(100, masterRepository.count())

        val second = importer().preview(name, zip.inputStream())
        assertTrue(second.canImport, "a repeat import is harmless, not an error")
        assertEquals(0, second.preview.newCount)
        assertEquals(100, second.preview.duplicateCount)
        assertTrue(second.isRepeatOfCommittedPackage)

        val commit = importer().commit(second, operator = "AM")
        assertEquals(0, commit.inserted)
        assertEquals(100, masterRepository.count(), "the raw layer is unchanged")
    }

    @Test
    fun `a second export of the same day imports only the newer rows`() = runTest {
        // EXPORT TODAY at lunchtime, then again at the end of the day: the specification's
        // overlapping-package case.
        collect(wifiScans = 30, bleSightings = 30)
        val (morningName, morningZip) = exportDay()
        enrolObserverOnMaster()
        importer().commit(importer().preview(morningName, morningZip.inputStream()), "AM")
        assertEquals(60, masterRepository.count())

        collect(wifiScans = 25, bleSightings = 25)
        val (fullName, fullZip) = exportDay()

        val preview = importer().preview(fullName, fullZip.inputStream())
        assertEquals(110, preview.preview.totalRows)
        assertEquals(50, preview.preview.newCount)
        assertEquals(60, preview.preview.duplicateCount)

        importer().commit(preview, "AM")
        assertEquals(110, masterRepository.count())
    }

    @Test
    fun `an observer the master does not know is refused`() = runTest {
        collect(wifiScans = 10, bleSightings = 10)
        val (name, zip) = exportDay()

        // No enrolment: unverified provenance must not enter an immutable raw layer.
        val preview = importer().preview(name, zip.inputStream())

        assertFalse(preview.canImport)
        assertTrue(preview.preview.blockingIssues.any { it.code == ImportErrorCode.UNKNOWN_OBSERVER })
        assertEquals(0, masterRepository.count())

        val rejected = master.importBatchDao().byPackageSha256(preview.preview.packageSha256!!)
        assertEquals(ImportBatchEntity.Status.REJECTED.name, rejected?.status)
    }

    @Test
    fun `enrolling the observer and retrying succeeds`() = runTest {
        collect(wifiScans = 10, bleSightings = 10)
        val (name, zip) = exportDay()

        assertFalse(importer().preview(name, zip.inputStream()).canImport)

        enrolObserverOnMaster()
        val retried = importer().preview(name, zip.inputStream())

        assertTrue(retried.canImport, "the obvious recovery from UNKNOWN_OBSERVER must work")
        importer().commit(retried, "AM")
        assertEquals(20, masterRepository.count())
    }

    @Test
    fun `an enrolled identifier is attributed on import and nothing else is`() = runTest {
        collect(wifiScans = 16, bleSightings = 10)
        val (name, zip) = exportDay()
        enrolObserverOnMaster()

        // One BLE address is an enrolled device. Every other identifier stays environmental.
        master.managedDeviceDao().replaceDevice(
            ManagedDeviceEntity(
                deviceId = "DEV-7",
                friendlyName = "Site tag 7",
                deviceType = "BLE_TAG",
                status = "AUTHORIZED",
                notes = null,
                firstSeenUtc = null,
                lastSeenUtc = null,
                createdAtUtc = Iso8601.format(RoomFixtures.DAY_START),
                updatedAtUtc = Iso8601.format(RoomFixtures.DAY_START),
            ),
            identifiers = listOf(
                DeviceIdentifierEntity(
                    identifier = "d1:e2:f3:04:15:00",
                    identifierType = "BLE_MAC_PUBLIC",
                    deviceId = "DEV-7",
                    addedBy = "AM",
                    addedAtUtc = Iso8601.format(RoomFixtures.DAY_START),
                    notes = null,
                ),
            ),
        )

        val commit = importer().commit(importer().preview(name, zip.inputStream()), "AM")

        assertEquals(2, commit.attributed, "two sightings of the enrolled tag")
        val stored = master.observationDao().pageForExport(0, Long.MAX_VALUE, "", "", 500)
        val attributed = stored.filter { it.targetDeviceId != null }
        assertEquals(2, attributed.size)
        assertTrue(attributed.all { it.targetDeviceId == "DEV-7" })
        assertTrue(attributed.all { it.radioIdentifier == "d1:e2:f3:04:15:00" })

        // last_seen is advanced from the observation's own timestamp, not from the import time.
        val device = master.managedDeviceDao().byId("DEV-7")
        assertEquals(attributed.maxOf { it.timestampUtc }, device?.lastSeenUtc)
    }

    @Test
    fun `a randomised address is never attributed even when it is enrolled by mistake`() = runTest {
        val factory = ObservationFactory(observer, clock, IdGenerator.RANDOM)
        val engine = CollectionEngine(factory, collectorRepository, clock, batchSize = 10)
        engine.start(
            SessionContext(RoomFixtures.SESSION, clock.wallClockMillis(), ScanProfile.BALANCED),
        )
        repeat(6) {
            clock.advance(500)
            engine.submit(
                FakeSamples.randomBle(
                    mac = "5a:11:22:33:44:55",
                    wallClockMillis = clock.wallClockMillis(),
                    monotonicMillis = clock.monotonicElapsedMillis(),
                ),
            )
        }
        engine.stop()

        val (name, zip) = exportDay()
        enrolObserverOnMaster()
        master.managedDeviceDao().replaceDevice(
            ManagedDeviceEntity(
                deviceId = "DEV-BAD",
                friendlyName = "Enrolled by mistake",
                deviceType = "PHONE",
                status = "AUTHORIZED",
                notes = null,
                firstSeenUtc = null,
                lastSeenUtc = null,
                createdAtUtc = Iso8601.format(RoomFixtures.DAY_START),
                updatedAtUtc = Iso8601.format(RoomFixtures.DAY_START),
            ),
            identifiers = listOf(
                DeviceIdentifierEntity(
                    identifier = "5a:11:22:33:44:55",
                    identifierType = "BLE_MAC_RANDOM",
                    deviceId = "DEV-BAD",
                    addedBy = "AM",
                    addedAtUtc = Iso8601.format(RoomFixtures.DAY_START),
                    notes = null,
                ),
            ),
        )

        val commit = importer().commit(importer().preview(name, zip.inputStream()), "AM")

        assertEquals(0, commit.attributed, "a rotating address is not an identity")
        val stored = master.observationDao().pageForExport(0, Long.MAX_VALUE, "", "", 100)
        assertTrue(stored.all { it.targetDeviceId == null })
    }

    @Test
    fun `survey samples arrive as ground truth with their point intact`() = runTest {
        collect(
            wifiScans = 20,
            bleSightings = 0,
            survey = SurveyContext(
                surveySessionId = "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
                surveyPointId = "B7_CENTER",
                place = ObserverPlace(buildingId = "B7", zoneId = "B7-CENTER", xCoordinate = 12.0, yCoordinate = 8.5),
                operator = "AM",
            ),
        )
        val (name, zip) = exportDay()
        enrolObserverOnMaster()
        importer().commit(importer().preview(name, zip.inputStream()), "AM")

        val groundTruth = master.observationDao().groundTruthForPoint("B7_CENTER")
        assertEquals(20, groundTruth.size)
        assertTrue(groundTruth.all { it.sampleKind == SampleKind.GROUND_TRUTH.name })
        assertTrue(groundTruth.all { it.xCoordinate == 12.0 && it.yCoordinate == 8.5 })
    }

    @Test
    fun `a session export carries only that session and its summary`() = runTest {
        val session = collect(wifiScans = 30, bleSightings = 20)

        val exporter = PackageExporter(
            repository = collectorRepository,
            sessions = collector.sessionDao(),
            appVersion = "1.0.0",
            newExportId = { "3f8e1c20-4a5b-4c6d-8e9f-0a1b2c3d4e5f" },
            nowMillis = { RoomFixtures.DAY_START + 12 * 60 * 60 * 1000L },
        )
        val plan = exporter.planSession(observer, session.sessionId)
        assertEquals("RFMapper_OBS04_2026-09-14_0d6b1f4a.zip", plan.packageName)
        assertEquals(50, plan.observationCount)

        val buffer = ByteArrayOutputStream()
        exporter.write(plan, ZipExportSink(buffer))

        enrolObserverOnMaster()
        val preview = importer().preview(plan.packageName, buffer.toByteArray().inputStream())
        assertTrue(preview.canImport, "blocking: ${preview.preview.blockingIssues}")
        assertEquals(50, preview.preview.newCount)
    }

    @Test
    fun `an export of an empty day is a valid package rather than a failure`() = runTest {
        val (name, zip) = exportDay(dateStamp = "2026-09-20")
        enrolObserverOnMaster()

        val preview = importer().preview(name, zip.inputStream())

        assertTrue(preview.canImport, "blocking: ${preview.preview.blockingIssues}")
        assertEquals(0, preview.preview.totalRows)
        assertNull(preview.preview.manifest?.firstObservation)
        assertEquals(0, importer().commit(preview, "AM").inserted)
    }

    @Test
    fun `a truncated package is rejected before anything is written`() = runTest {
        collect(wifiScans = 40, bleSightings = 40)
        val (name, zip) = exportDay()
        enrolObserverOnMaster()

        val truncated = zip.copyOf(zip.size / 2)
        val failure = runCatching { importer().preview(name, truncated.inputStream()) }

        // Either the archive fails to parse or the validator reports it; what must not happen is a
        // partial import.
        val blocked = failure.getOrNull()?.canImport != true
        assertTrue(blocked, "a truncated package must not be importable")
        assertEquals(0, masterRepository.count())
    }

    @Test
    fun `the exported package contains exactly the specified entries`() = runTest {
        collect(wifiScans = 5, bleSightings = 5)
        val (_, zip) = exportDay()

        val names = java.util.zip.ZipInputStream(zip.inputStream()).use { stream ->
            generateSequence { stream.nextEntry }.map { it.name }.toList()
        }

        assertEquals(
            listOf(
                ExportPackage.MANIFEST,
                ExportPackage.OBSERVATIONS_CSV,
                ExportPackage.OBSERVATIONS_JSON,
                ExportPackage.OBSERVER,
                ExportPackage.SESSIONS,
                ExportPackage.CHECKSUM,
            ),
            names,
        )
    }
}
