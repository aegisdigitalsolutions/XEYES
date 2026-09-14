package com.rfmapper.master

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.export.Sha256
import com.rfmapper.core.importing.ImportEngineV1
import com.rfmapper.core.importing.ObserverRegistry
import com.rfmapper.core.model.DerivedManifest
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.PackageType
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SiteFrame
import com.rfmapper.core.model.SiteModel
import com.rfmapper.data.room.DerivedPackageImporter
import com.rfmapper.data.room.DeviceRegistryIo
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.PackageImporter
import com.rfmapper.data.room.ReferenceRepository
import com.rfmapper.data.room.RfMapperDatabase
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.master.importing.ImportCoordinator
import com.rfmapper.master.settings.MasterSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The coordinator decides what a picked file *is*. Getting that wrong would route a derived
 * package through the observation importer, so it is worth its own test.
 */
@RunWith(RobolectricTestRunner::class)
class ImportCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: RfMapperDatabase
    private lateinit var coordinator: ImportCoordinator
    private lateinit var folder: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = RfMapperDatabase.openInMemory(context)
        folder = File(context.cacheDir, "packages-${System.nanoTime()}").apply { mkdirs() }

        val settings = MasterSettings(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
            },
        )

        coordinator = ImportCoordinator(
            context = context,
            observations = PackageImporter(
                repository = ObservationRepository(db.observationDao(), db.sessionDao()),
                observers = db.observerDao(),
                devices = db.managedDeviceDao(),
                batches = db.importBatchDao(),
                engine = ImportEngineV1(ObserverRegistry { true }),
            ),
            derived = DerivedPackageImporter(db.derivedDao(), db.importBatchDao()),
            siteModel = SiteModelIo(
                site = db.siteModelDao(),
                infrastructure = db.infrastructureDao(),
                observers = db.observerDao(),
                fingerprints = db.fingerprintDao(),
                calibration = db.observerCalibrationDao(),
            ),
            deviceRegistry = DeviceRegistryIo(
                ReferenceRepository(
                    devices = db.managedDeviceDao(),
                    infrastructure = db.infrastructureDao(),
                    observers = db.observerDao(),
                    site = db.siteModelDao(),
                    fingerprints = db.fingerprintDao(),
                    calibration = db.observerCalibrationDao(),
                ),
            ),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        db.close()
        folder.deleteRecursively()
    }

    @Test
    fun `a derived package is recognised by its manifest, not its filename`() = runTest {
        // Deliberately named as though it were an observation package: a file renamed in transit is
        // ordinary, a package imported as the wrong kind is not.
        val file = write("OBS04_2026-09-14.zip", derivedZip())

        val staged = coordinator.stage(android.net.Uri.fromFile(file))

        assertTrue(staged.toString(), staged is ImportCoordinator.Staged.Derived)
        val derived = staged as ImportCoordinator.Staged.Derived
        assertEquals("pipeline-1.0.0", derived.preview.manifest?.algorithmVersion)
        // No import batch exists, so it cannot be traced to raw evidence and must not be committable.
        assertFalse(derived.canCommit)
    }

    @Test
    fun `a site model document is recognised and staged`() = runTest {
        val model = SiteModel(
            referenceModelId = "site-2026-09-01",
            createdAt = "2026-09-01T09:00:00.000Z",
            frame = SiteFrame(originLat = 51.5, originLon = -0.12),
        )
        val file = write(
            "site.json",
            RfMapperJson.compact.encodeToString(SiteModel.serializer(), model).encodeToByteArray(),
        )

        val staged = coordinator.stage(android.net.Uri.fromFile(file))

        assertTrue(staged.toString(), staged is ImportCoordinator.Staged.Site)
        assertTrue((staged as ImportCoordinator.Staged.Site).canCommit)
    }

    @Test
    fun `an unrecognisable file is reported rather than guessed at`() = runTest {
        val file = write("notes.txt", "the AP by the loading dock moved on Tuesday".encodeToByteArray())

        val staged = coordinator.stage(android.net.Uri.fromFile(file))

        assertTrue(staged.toString(), staged is ImportCoordinator.Staged.Unreadable)
    }

    @Test
    fun `committing a package with blocking issues writes nothing`() = runTest {
        val file = write("DERIVED_2026-09-14.zip", derivedZip())
        val staged = coordinator.stage(android.net.Uri.fromFile(file))

        val outcome = coordinator.commit(staged)

        assertFalse(outcome.success)
        assertTrue(outcome.detail.any { it.contains("UNKNOWN_SOURCE_DATASET") })
        assertEquals(0, db.derivedDao().generations().size)
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(folder, name).apply { writeBytes(bytes) }

    private fun derivedZip(): ByteArray {
        val manifest = DerivedManifest(
            packageType = PackageType.DERIVED,
            exportId = "export-1",
            createdAt = "2026-09-14T23:10:04.000Z",
            algorithmVersion = "pipeline-1.0.0",
            engineVersions = mapOf("zone_engine" to "1.1.3"),
            sourceDatasetIds = listOf("OBS04_2026-09-14"),
            generator = Generator("rfmapper_lab", "1.0.0"),
        )
        val entries = mapOf(
            "manifest.json" to RfMapperJson.compact
                .encodeToString(DerivedManifest.serializer(), manifest).encodeToByteArray(),
            "position_estimates.json" to "[]".encodeToByteArray(),
        )
        val all = entries + (
            "checksum.txt" to
                Sha256.checksumFile(entries.mapValues { Sha256.hex(it.value) }).encodeToByteArray()
            )

        return java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                all.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
