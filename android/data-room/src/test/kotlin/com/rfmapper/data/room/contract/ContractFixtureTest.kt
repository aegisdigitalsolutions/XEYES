package com.rfmapper.data.room.contract

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.export.ZipExportSink
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.SensorType
import com.rfmapper.data.room.DerivedPackageImporter
import com.rfmapper.data.room.DeviceRegistryIo
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.PackageExporter
import com.rfmapper.data.room.PackageImporter
import com.rfmapper.data.room.ReferenceRepository
import com.rfmapper.data.room.RfMapperDatabase
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.data.room.raw.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generates the cross-language fixtures the Positioning Lab reads, and checks the committed ones
 * still match.
 *
 * The three components never share a process, so nothing about their file contracts can be verified
 * by calling one from the other. What can be verified is the artefact. This test is the writing half
 * of that arrangement: it drives the real Collector export path and the real Master reference-export
 * path, then compares the result against files committed under `contract/`. `tests/test_fixtures.py`
 * in the Lab is the reading half, and it consumes exactly these files.
 *
 * Fixtures are produced through the database rather than by serialising the scenario objects
 * directly. Serialising the model would only test that Kotlin can write what Kotlin wrote; going
 * through Room proves the Lab reads what the Master and the Collector genuinely produce, including
 * whatever the storage layer normalises on the way through.
 *
 * Run with `-Drfmapper.contract.write=true` to accept a deliberate change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContractFixtureTest {

    private lateinit var master: RfMapperDatabase
    private lateinit var masterRepository: ObservationRepository
    private lateinit var reference: ReferenceRepository
    private lateinit var siteModelIo: SiteModelIo
    private lateinit var deviceRegistryIo: DeviceRegistryIo

    /** Noon on the first fixture day. Every generated timestamp is fixed, or fixtures would churn. */
    private val referenceAuthoredAt = ContractScenario.DAY_START + 4 * 60 * 60 * 1000L

    /** The morning after the last survey visit, which is when an operator would do the importing. */
    private val importedAt = requireNotNull(Iso8601.parseToEpochMillis("2026-05-07T09:00:00.000Z"))

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        master = RfMapperDatabase.openInMemory(context)
        masterRepository = ObservationRepository(master.observationDao(), master.sessionDao())
        reference = ReferenceRepository(
            devices = master.managedDeviceDao(),
            infrastructure = master.infrastructureDao(),
            observers = master.observerDao(),
            site = master.siteModelDao(),
            fingerprints = master.fingerprintDao(),
            calibration = master.observerCalibrationDao(),
            nowMillis = { referenceAuthoredAt },
        )
        siteModelIo = SiteModelIo(
            site = master.siteModelDao(),
            infrastructure = master.infrastructureDao(),
            observers = master.observerDao(),
            fingerprints = master.fingerprintDao(),
            calibration = master.observerCalibrationDao(),
            nowMillis = { referenceAuthoredAt },
        )
        deviceRegistryIo = DeviceRegistryIo(reference, nowMillis = { referenceAuthoredAt })
    }

    @After
    fun close() = master.close()

    // -- REFERENCE ---------------------------------------------------------------------------------

    @Test
    fun `the site model the master exports is the one the lab reads`() = runTest {
        loadReferenceLayer()

        val exported = siteModelIo.export(
            referenceModelId = ContractScenario.REFERENCE_MODEL_ID,
            frame = ContractScenario.FRAME,
            notes = ContractScenario.siteModel().notes,
        )

        // The round trip has to preserve identity and geometry, or the fixture would be a record of
        // a storage bug rather than of the contract.
        assertEquals(ContractScenario.REFERENCE_MODEL_ID, exported.referenceModelId)
        assertEquals(ContractScenario.FRAME, exported.frame)
        assertEquals(2, exported.zones.size)
        assertEquals(5, exported.infrastructureNodes.size)
        assertEquals(3, exported.observers.size)
        assertEquals(2, exported.surveyPoints.size)
        assertTrue(exported.referentialIssues().isEmpty(), "${exported.referentialIssues()}")
        assertTrue(
            exported.fingerprints.isEmpty(),
            "the Lab must build the fingerprints itself from the surveyor's ground truth",
        )

        val buffer = ByteArrayOutputStream()
        siteModelIo.writeTo(exported, buffer)
        ContractFixtures.golden(SITE_MODEL, buffer.toByteArray())
    }

    @Test
    fun `the device registry the master exports is the one the lab attributes against`() = runTest {
        loadReferenceLayer()

        val registry = deviceRegistryIo.export(REGISTRY_ID, notes = "Cross-language contract fixture.")

        assertEquals(2, registry.managedDevices.size)
        assertTrue(registry.referentialIssues().isEmpty(), "${registry.referentialIssues()}")
        assertTrue(
            registry.managedDevices.any { it.status.name == "BLOCKED" },
            "a blocked device is still a device the Lab must recognise",
        )

        val buffer = ByteArrayOutputStream()
        deviceRegistryIo.writeTo(registry, buffer)
        ContractFixtures.golden(DEVICES, buffer.toByteArray())
    }

    // -- RAW ---------------------------------------------------------------------------------------

    @Test
    fun `the collector packages are the ones the lab ingests`() = runTest {
        val produced = exportEveryPackage()

        assertEquals(PACKAGE_NAMES, produced.keys.toList())
        produced.forEach { (name, zip) -> ContractFixtures.goldenArchive(name, zip) }
    }

    @Test
    fun `every fixture package imports into the master it was generated for`() = runTest {
        loadReferenceLayer()
        ContractScenario.siteModel().observers.forEach { reference.setEnrolled(it.observerId, true) }

        var imported = 0L
        for ((name, zip) in exportEveryPackage()) {
            val importer = PackageImporter(
                repository = masterRepository,
                observers = master.observerDao(),
                devices = master.managedDeviceDao(),
                batches = master.importBatchDao(),
                nowMillis = { importedAt },
            )
            val preview = importer.preview(name, zip.inputStream())
            assertTrue(preview.canImport, "$name: ${preview.preview.blockingIssues}")
            assertEquals(0, preview.preview.invalidCount, "$name carries an undecodable row")
            imported += importer.commit(preview, operator = "contract").inserted
        }

        assertEquals(imported, masterRepository.count())
        assertEquals(TOTAL_OBSERVATIONS, masterRepository.count())

        // The managed tag is heard by both collectors, and attribution is what makes those rows a
        // device's history rather than anonymous RF. The randomised address must stay anonymous.
        val stored = master.observationDao().pageForExport(0, Long.MAX_VALUE, "", "", 10_000)
        val attributed = stored.filter { it.targetDeviceId != null }
        assertEquals(60, attributed.size, "30 sightings of the tag by each of the two collectors")
        assertTrue(attributed.all { it.targetDeviceId == ContractScenario.TAG_DEVICE })
        assertTrue(stored.none { it.identifierType == "BLE_MAC_RANDOM" && it.targetDeviceId != null })
    }

    // -- DERIVED -----------------------------------------------------------------------------------

    @Test
    fun `the master imports the derived package the lab produced from these fixtures`() = runTest {
        val fixture = ContractFixtures.file(DERIVED_PACKAGE)
        if (!fixture.isFile) {
            error(
                "$DERIVED_PACKAGE is missing. It is produced by the Lab, not by this build: run " +
                    "`pytest tests/test_fixtures.py --rfmapper-write-contract` in positioning-lab/.",
            )
        }

        // Traceability first. The importer refuses a package citing datasets the Master has never
        // held, so the raw packages have to be imported before the derived one — which is also the
        // order an operator works in, and the reason the refusal is worth having.
        loadReferenceLayer()
        ContractScenario.siteModel().observers.forEach { reference.setEnrolled(it.observerId, true) }
        for ((name, zip) in exportEveryPackage()) {
            val importer = PackageImporter(
                repository = masterRepository,
                observers = master.observerDao(),
                devices = master.managedDeviceDao(),
                batches = master.importBatchDao(),
                nowMillis = { importedAt },
            )
            importer.commit(importer.preview(name, zip.inputStream()), operator = "contract")
        }

        val importer = DerivedPackageImporter(
            derived = master.derivedDao(),
            batches = master.importBatchDao(),
            nowMillis = { importedAt },
        )
        val preview = importer.preview(fixture.inputStream())

        assertTrue(preview.canImport, "${preview.blockingIssues}")
        val manifest = requireNotNull(preview.manifest)
        assertEquals(
            ContractScenario.REFERENCE_MODEL_ID,
            manifest.referenceModelId,
            "the Lab must cite the site model it was given",
        )
        assertTrue(
            preview.estimates.isNotEmpty(),
            "a derived package with no estimates would let an empty pipeline pass this test",
        )
        assertTrue(
            preview.estimates.all { it.deviceId == ContractScenario.TAG_DEVICE },
            "only registered devices may be positioned",
        )

        val result = importer.commit(preview, makeActive = true)
        assertEquals(preview.estimates.size, result.estimates)
        assertEquals(manifest.algorithmVersion, result.algorithmVersion)
    }

    // -- helpers -----------------------------------------------------------------------------------

    private suspend fun loadReferenceLayer() {
        siteModelIo.apply(ContractScenario.siteModel())
        ContractScenario.managedDevices().forEach { reference.saveDevice(it, addedBy = "contract") }
    }

    /**
     * Every package in the scenario, in a fixed order, each produced by the real Collector path.
     *
     * The surveyor's three visits are three days apart, so they leave three day-packages rather
     * than one. That is not incidental: a fingerprint built from a single session records that
     * session's conditions, and the Lab is supposed to see separate sessions here.
     */
    private suspend fun exportEveryPackage(): Map<String, ByteArray> = buildMap {
        put(
            PACKAGE_NAMES[0],
            exportDay(observer(ContractScenario.OBSERVER_NORTH), northObservations(), "2026-05-04"),
        )
        put(
            PACKAGE_NAMES[1],
            exportDay(observer(ContractScenario.OBSERVER_SOUTH), southObservations(), "2026-05-04"),
        )
        val surveyor = observer(ContractScenario.SURVEYOR)
        val surveyed = ContractScenario.surveyorObservations()
        PACKAGE_NAMES.drop(2).forEachIndexed { visit, name ->
            put(name, exportDay(surveyor, surveyed, SURVEY_DATES[visit]))
        }
    }

    /**
     * Runs one day-export against a Collector database holding [observations].
     *
     * A fresh database per package, because a Collector holds only its own rows: one database
     * carrying every observer's data would produce packages no real handset could produce, and the
     * export path would never be asked to select a single day out of a single device.
     */
    private suspend fun exportDay(
        observer: ObserverIdentity,
        observations: List<Observation>,
        dateStamp: String,
    ): ByteArray {
        val collector = RfMapperDatabase.openInMemory(
            ApplicationProvider.getApplicationContext(),
        )
        try {
            val repository = ObservationRepository(collector.observationDao(), collector.sessionDao())
            repository.write(observations)
            sessionsOf(observations, observer.observerId).forEach { repository.upsertSession(it) }

            val exporter = PackageExporter(
                repository = repository,
                sessions = collector.sessionDao(),
                appVersion = APP_VERSION,
                newExportId = { exportId(observer.observerId, dateStamp) },
                // The evening of the day being exported. Not one fixed instant for all five
                // packages: the validator refuses a row timestamped after its package was written,
                // and it is right to — a package claiming to contain tomorrow's collection is
                // either a clock fault or an edited file.
                nowMillis = { endOfDay(dateStamp) },
            )
            val plan = exporter.planDay(observer, dateStamp)
            assertTrue(plan.observationCount > 0, "${plan.packageName} would be empty")

            val buffer = ByteArrayOutputStream()
            exporter.write(plan, ZipExportSink(buffer))
            return buffer.toByteArray()
        } finally {
            collector.close()
        }
    }

    /**
     * Session rows reconstructed from the observations themselves.
     *
     * The fixtures are declared as observations rather than driven through the collection engine,
     * so the session summaries a real Collector would have accumulated have to be derived here.
     * Counting from the rows keeps `sessions.json` consistent with `observations.json`; an
     * independently invented count would make the fixture describe a Collector that cannot exist.
     */
    private fun sessionsOf(
        observations: List<Observation>,
        observerId: String,
    ): List<SessionEntity> = observations
        .groupBy { it.metadata[MetadataKeys.SESSION_ID] }
        .mapNotNull { (sessionId, rows) -> sessionId?.let { it to rows } }
        .sortedBy { (_, rows) -> rows.minOf { it.timestampUtc } }
        .map { (sessionId, rows) ->
            val first = rows.minOf { it.timestampUtc }
            val last = rows.maxOf { it.timestampUtc }
            SessionEntity(
                sessionId = sessionId,
                startedAtUtc = first,
                startedAtEpochMs = requireNotNull(Iso8601.parseToEpochMillis(first)),
                endedAtUtc = last,
                observerId = observerId,
                scanProfile = "BALANCED",
                buildingId = ContractScenario.BUILDING,
                zoneId = rows.first().zoneId,
                observationCount = rows.size.toLong(),
                wifiCount = rows.count { it.sensorType == SensorType.WIFI_SCAN }.toLong(),
                bleCount = rows.count { it.sensorType == SensorType.BLE }.toLong(),
                rttCount = rows.count { it.sensorType == SensorType.RTT }.toLong(),
                gpsCount = rows.count { it.sensorType == SensorType.GPS }.toLong(),
                droppedSamples = 0,
                throttledScanRequests =
                    rows.count { it.metadata[MetadataKeys.THROTTLED] == "true" }.toLong(),
                backgroundDenied = false,
                suspectedServiceKill = false,
                batteryStartPct = null,
                batteryEndPct = null,
                degradations = emptyList(),
            )
        }
        .also { require(it.none { session -> session.sessionId.isBlank() }) }

    private fun observer(observerId: String): ObserverIdentity =
        ContractScenario.siteModel().observers.first { it.observerId == observerId }

    private fun northObservations() = ContractScenario.collectorObservations(
        observerId = ContractScenario.OBSERVER_NORTH,
        hearsTagAt = -52,
        firstId = 10_000,
    )

    private fun southObservations() = ContractScenario.collectorObservations(
        observerId = ContractScenario.OBSERVER_SOUTH,
        hearsTagAt = -77,
        firstId = 20_000,
    )

    private fun endOfDay(dateStamp: String): Long =
        requireNotNull(Iso8601.parseToEpochMillis("${dateStamp}T20:00:00.000Z"))

    /** A fixed export id per package, since a random one would change the fixture on every run. */
    private fun exportId(observerId: String, dateStamp: String): String {
        val seed = "$observerId/$dateStamp".hashCode().toLong() and 0xFFFF_FFFFL
        return "00000000-0000-4000-a000-${seed.toString(16).padStart(12, '0')}"
    }

    private companion object {
        const val APP_VERSION = "1.0.0"
        const val REGISTRY_ID = "registry-contract-2026-05-04"

        const val SITE_MODEL = "site_model.json"
        const val DEVICES = "devices.json"
        const val DERIVED_PACKAGE = "DERIVED_2026-05-04.zip"

        val SURVEY_DATES = listOf("2026-05-04", "2026-05-05", "2026-05-06")

        val PACKAGE_NAMES = listOf(
            "RFMapper_OBSC1_2026-05-04.zip",
            "RFMapper_OBSC2_2026-05-04.zip",
            "RFMapper_OBSC3_2026-05-04.zip",
            "RFMapper_OBSC3_2026-05-05.zip",
            "RFMapper_OBSC3_2026-05-06.zip",
        )

        /** 2 collectors × 36 rows, plus 3 survey visits × 2 points × 8 samples × 5 sources. */
        const val TOTAL_OBSERVATIONS = 2 * 36L + 3 * 2 * 8 * 5L
    }
}
