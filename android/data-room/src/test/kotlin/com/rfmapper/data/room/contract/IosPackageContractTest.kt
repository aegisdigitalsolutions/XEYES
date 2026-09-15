package com.rfmapper.data.room.contract

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.importing.ImportErrorCode
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.SensorType
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.PackageImporter
import com.rfmapper.data.room.ReferenceRepository
import com.rfmapper.data.room.RfMapperDatabase
import com.rfmapper.data.room.SiteModelIo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Imports the package the **Swift** Collector produced.
 *
 * This is the third corner of the file contract, and the one that was missing. `contract/` already
 * held packages written by Kotlin and read by Python; `RFMapper_OBSI1_2026-05-04.zip` is written by
 * the Swift export engine (`ios/Packages/RFMapper`, `ContractFixtureTests`) and is read here by the
 * real Master import path and in `positioning-lab/tests/test_ios_contract.py` by the real Lab
 * pipeline. `docs/06-ios-capability-matrix.md` §7 names exactly this as the acceptance criterion for
 * an iOS Collector: the Kotlin validator run against an iOS-produced zip.
 *
 * It is a stronger test than comparing Swift's output against Swift's expectations. Two
 * implementations agreeing on a byte layout they each derived independently — the CSV dialect, the
 * decimal formatting, the row checksums, the entry digests, the JSON field order — means they
 * genuinely match rather than sharing an assumption. Nothing here reads a Swift source file; it
 * reads a committed artefact, which is all a Master would ever receive.
 *
 * The fixture is regenerated on the Swift side:
 *
 *     cd ios/Packages/RFMapper && RFMAPPER_WRITE_CONTRACT=1 swift test
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IosPackageContractTest {

    private lateinit var master: RfMapperDatabase
    private lateinit var repository: ObservationRepository
    private lateinit var reference: ReferenceRepository
    private lateinit var siteModelIo: SiteModelIo

    private val authoredAt = ContractScenario.DAY_START + 4 * 60 * 60 * 1000L
    private val importedAt = requireNotNull(Iso8601.parseToEpochMillis("2026-05-07T09:00:00.000Z"))

    /** What the last [importEverything] committed, so a test can assert on the Master's own verdict. */
    private var commit: PackageImporter.CommitResult? = null

    private fun lastCommit(): PackageImporter.CommitResult = assertNotNull(commit)

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        master = RfMapperDatabase.openInMemory(context)
        repository = ObservationRepository(master.observationDao(), master.sessionDao())
        reference = ReferenceRepository(
            devices = master.managedDeviceDao(),
            infrastructure = master.infrastructureDao(),
            observers = master.observerDao(),
            site = master.siteModelDao(),
            fingerprints = master.fingerprintDao(),
            calibration = master.observerCalibrationDao(),
            nowMillis = { authoredAt },
        )
        siteModelIo = SiteModelIo(
            site = master.siteModelDao(),
            infrastructure = master.infrastructureDao(),
            observers = master.observerDao(),
            fingerprints = master.fingerprintDao(),
            calibration = master.observerCalibrationDao(),
            nowMillis = { authoredAt },
        )
    }

    @After
    fun close() = master.close()

    /**
     * The enrolment gate, exercised in the order an administrator meets it.
     *
     * The iPhone is not in the shared site model — it is a handset that turned up with a package —
     * so the first attempt must be refused. That refusal is the interesting half: a Master that
     * accepted observations from an observer it had never registered could not say whose data it
     * held. The identity it then enrols comes out of the package's own `observer.json`, which is
     * also the only way the Master learns what this device could and could not see.
     */
    @Test
    fun `an unknown iphone is refused until the master enrols it`() = runTest {
        loadReferenceLayer()

        val refused = importer().preview(PACKAGE, fixture())
        assertTrue(
            refused.preview.issues.any { it.code == ImportErrorCode.UNKNOWN_OBSERVER },
            "an unregistered observer's package must be refused: ${refused.preview.issues}",
        )
        assertTrue(!refused.canImport)

        // Read out of the package, not invented here: this is the Master learning the handset's
        // identity from the file, which is the only channel the two ever share.
        val identity = assertNotNull(refused.preview.observer)
        assertEquals(OBSERVER, identity.observerId)
        reference.saveObserver(identity, enrolled = true)

        val accepted = importer().preview(PACKAGE, fixture())
        assertTrue(accepted.canImport, "${accepted.preview.blockingIssues}")
        assertEquals(
            0,
            accepted.preview.invalidCount,
            "every row's own checksum was recomputed by Kotlin and had to match Swift's",
        )
        assertEquals(ROW_COUNT, accepted.preview.totalRows)

        val committed = importer().commit(accepted, operator = "contract")
        assertEquals(ROW_COUNT, committed.inserted)
        assertEquals(ROW_COUNT.toLong(), repository.count())
    }

    /**
     * The declaration that stops silence being read as evidence.
     *
     * An iOS observer reports no Wi-Fi scan results because it *cannot* scan Wi-Fi, not because no
     * access points were there. If `unsupported` does not survive the import, the Lab will read the
     * absence as evidence of absence and drive every fingerprint's Wi-Fi visibility toward zero
     * (`docs/06-ios-capability-matrix.md` §6.2). It is the single most consequential field in the
     * package and the easiest one to drop silently.
     */
    @Test
    fun `the iphone's declaration of what it cannot see survives the import`() = runTest {
        val identity = assertNotNull(importer().preview(PACKAGE, fixture()).preview.observer)

        assertEquals(Platform.IOS, identity.platform)
        assertEquals(
            setOf(ObserverCapability.BLE, ObserverCapability.GPS, ObserverCapability.WIFI_ASSOCIATION),
            identity.capabilities,
        )
        assertEquals(
            setOf(ObserverCapability.WIFI_SCAN, ObserverCapability.RTT),
            identity.unsupported,
        )
        assertTrue(
            !identity.fixedObserver,
            "a handheld phone's samples must never be given a surveyed reference point's weight",
        )

        reference.saveObserver(identity, enrolled = true)
        val stored = assertNotNull(master.observerDao().byId(OBSERVER))
        assertTrue(ObserverCapability.WIFI_SCAN.name in stored.unsupported)
        assertTrue(ObserverCapability.WIFI_ASSOCIATION.name in stored.capabilities)
    }

    /**
     * The Wi-Fi row iOS can actually produce, and the one most likely to be "fixed" by a consumer.
     *
     * iOS discloses the BSSID of the joined network but not its signal strength, so the row arrives
     * with no `rssi` and says why in its metadata. The import path must leave it that way. A zero, a
     * sentinel, or a value inferred from `NEHotspotNetwork.signalStrength` — a coarse bar level
     * documented as unspecified — would be a fabricated measurement in the raw layer, which is the
     * one thing this schema exists to prevent.
     */
    @Test
    fun `an association row is stored with no signal strength and a reason`() = runTest {
        val stored = importEverything().filter { it.sensorType == SensorType.WIFI_ASSOCIATION }

        assertEquals(ASSOCIATION_ROWS, stored.size)
        assertTrue(stored.all { it.rssi == null }, "an iOS association has no signal level to store")
        assertTrue(stored.all { it.bssid != null }, "the BSSID is the whole evidence of the row")

        val explained = stored.filter {
            it.metadata[MetadataKeys.MISSING_PERMISSIONS] == "WIFI_RSSI_UNAVAILABLE_ON_IOS"
        }
        assertTrue(
            explained.isNotEmpty(),
            "the absence has to carry its cause, or the Lab cannot tell it from a collection fault",
        )
    }

    /**
     * A `CBPeripheral.identifier` is not an address, and nothing may treat it as one.
     *
     * It is a UUID scoped to this peripheral, this app install and this device — meaningless to any
     * other observer. The row therefore declares `OTHER` rather than a MAC type, and carries
     * `identifier_scope=APP_INSTALL` so the Lab will not join it to another observer's sighting of
     * something else (`docs/06-ios-capability-matrix.md` §3).
     */
    @Test
    fun `a peripheral identifier arrives scoped to the app install that saw it`() = runTest {
        val ble = importEverything().filter { it.sensorType == SensorType.BLE }

        assertTrue(ble.isNotEmpty())
        assertTrue(
            ble.all { it.identifierType == IdentifierType.OTHER },
            "claiming a MAC type would invite a reader to treat this UUID as an address",
        )
        assertTrue(
            ble.all { it.metadata[MetadataKeys.IDENTIFIER_SCOPE] == "APP_INSTALL" },
            "the scope tag is what stops a cross-observer join on a meaningless identifier",
        )
        assertTrue(ble.all { it.metadata[MetadataKeys.IOS_PERIPHERAL_IDENTIFIER] != null })
        assertTrue(
            ble.none { it.radioIdentifier == ContractScenario.TAG_BLE },
            "iOS cannot see the tag's hardware address, so no row may carry it",
        )
    }

    /**
     * Attribution across platforms, which for iOS has exactly one viable key.
     *
     * The Master cannot match an iOS BLE row on its address — it never receives one. What both
     * sides can recognise is the service UUID the tag advertises, which is why `docs/17` §4 calls
     * `SERVICE_UUID` the recommended rule and the only one that works across Android and iOS. This
     * test is the proof that the recommendation is implemented rather than merely written down: the
     * same physical tag, enrolled once by service UUID, is attributed from an iPhone's sighting of
     * it whose identifier the registry has never seen and could not hold.
     *
     * The peripheral that advertises nothing stays anonymous, as it must: there is no honest way to
     * join it to anything.
     */
    @Test
    fun `the tag is attributed by the service uuid, the only key ios can offer`() = runTest {
        val stored = importEverything()
        val attributed = stored.filter { it.targetDeviceId != null }

        // The count the *Master* attributed, which is the assertion that matters. The package
        // already arrives with target_device_id stamped by the Collector, so checking only the
        // stored field would pass just as well if the Master had rubber-stamped a claim it could
        // not verify. This number is non-zero only when its own registry made the match.
        assertEquals(TAG_ROWS, lastCommit().attributed, "the Master must reach its own verdict")

        assertEquals(TAG_ROWS, attributed.size)
        assertTrue(attributed.all { it.targetDeviceId == ContractScenario.TAG_DEVICE })
        assertTrue(
            attributed.all { it.bleServiceUuid == ContractScenario.TAG_SERVICE_UUID },
            "attribution must rest on the service UUID, since the address is unavailable",
        )
        assertTrue(
            stored.filter { it.sensorType == SensorType.BLE && it.bleServiceUuid == null }
                .all { it.targetDeviceId == null },
            "a peripheral advertising no service UUID cannot be joined to anything",
        )

        // The Collector claimed the same device, so there is nothing to disagree about and no
        // advisory should be raised. The disagreement path is covered in AttributionOnImportTest.
        val preview = importer().preview(PACKAGE, fixture()).preview
        assertTrue(
            preview.issues.none { it.code == ImportErrorCode.ATTRIBUTION_DISAGREEMENT },
            "${preview.issues}",
        )
        assertTrue(
            stored.none { it.metadata[MetadataKeys.COLLECTOR_CLAIMED_DEVICE_ID] != null },
            "no claim needed preserving, because none was overruled",
        )
    }

    /** The package says which program wrote it, and an imported batch can be traced back to it. */
    @Test
    fun `the package names the collector that produced it`() = runTest {
        val manifest = assertNotNull(importer().preview(PACKAGE, fixture()).preview.manifest)

        assertEquals(OBSERVER, manifest.observerId)
        assertEquals(ROW_COUNT.toLong(), manifest.observationCount)
        assertEquals("RFMapper iOS Collector", manifest.generator?.name)
        assertNull(
            manifest.countsBySensorType["WIFI_SCAN"],
            "iOS cannot scan Wi-Fi, so no package it writes may claim a scan",
        )
    }

    // -- helpers -----------------------------------------------------------------------------------

    private suspend fun loadReferenceLayer() {
        siteModelIo.apply(ContractScenario.siteModel())
        ContractScenario.managedDevices().forEach { reference.saveDevice(it, addedBy = "contract") }
    }

    /**
     * Enrols the iPhone from its own package, imports it, and returns the stored rows.
     *
     * Read back through `toObservation()` rather than as storage rows, because that conversion
     * re-applies the domain model's invariants. A value that survived the Swift writer, the CSV, the
     * validator and Room but is not a legal observation fails here rather than being asserted about
     * as a string.
     */
    private suspend fun importEverything(): List<Observation> {
        loadReferenceLayer()
        val identity = requireNotNull(importer().preview(PACKAGE, fixture()).preview.observer)
        reference.saveObserver(identity, enrolled = true)

        val preview = importer().preview(PACKAGE, fixture())
        require(preview.canImport) { "${preview.preview.blockingIssues}" }
        commit = importer().commit(preview, operator = "contract")

        return master.observationDao()
            .pageForExport(0, Long.MAX_VALUE, "", "", 10_000)
            .map { it.toObservation() }
    }

    private fun importer() = PackageImporter(
        repository = repository,
        observers = master.observerDao(),
        devices = master.managedDeviceDao(),
        batches = master.importBatchDao(),
        nowMillis = { importedAt },
    )

    private fun fixture() = ContractFixtures.file(PACKAGE)
        .also {
            if (!it.isFile) {
                error(
                    "$PACKAGE is missing. It is produced by the Swift Collector, not by this " +
                        "build: run `RFMAPPER_WRITE_CONTRACT=1 swift test` in " +
                        "ios/Packages/RFMapper.",
                )
            }
        }
        .inputStream()

    private companion object {
        const val PACKAGE = "RFMapper_OBSI1_2026-05-04.zip"
        const val OBSERVER = "OBS-I1"

        /** 6 sightings of the tag, 3 of a stranger, 4 associations, a GNSS fix, an awkward SSID. */
        const val ROW_COUNT = 6 + 3 + 4 + 1 + 1
        const val TAG_ROWS = 6
        const val ASSOCIATION_ROWS = 4 + 1
    }
}
