package com.rfmapper.data.room

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.export.Sha256
import com.rfmapper.core.model.DatasetKind
import com.rfmapper.core.model.DerivedManifest
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.PackageType
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.TopologyStatus
import com.rfmapper.core.model.ZoneEventType
import com.rfmapper.core.model.ZoneTransition
import com.rfmapper.data.room.raw.ImportBatchEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DerivedPackageImporterTest {

    private lateinit var db: RfMapperDatabase
    private lateinit var importer: DerivedPackageImporter
    private lateinit var derived: DerivedRepository

    @Before
    fun setUp() {
        db = RfMapperDatabase.openInMemory(ApplicationProvider.getApplicationContext())
        importer = DerivedPackageImporter(
            derived = db.derivedDao(),
            batches = db.importBatchDao(),
            nowMillis = { RoomFixtures.DAY_START },
        )
        derived = DerivedRepository(db.derivedDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a derived package citing known data imports as one generation`() = runTest {
        recordCommittedBatch("OBS04_2026-09-14")

        val preview = importer.preview(ByteArrayInputStream(packageBytes()))
        assertTrue(preview.issues.toString(), preview.canImport)
        assertEquals(2, preview.estimates.size)
        assertEquals(1, preview.transitions.size)

        val result = importer.commit(preview, makeActive = true)
        assertEquals("pipeline-1.0.0", result.algorithmVersion)
        assertEquals(2, result.estimates)

        val summary = requireNotNull(derived.summarise("pipeline-1.0.0"))
        assertEquals(2, summary.devices)
        assertEquals(mapOf(PrecisionTier.ZONE to 1, PrecisionTier.APPROXIMATE_POSITION to 1), summary.byTier)
        assertEquals(0.5, summary.coordinateShare, 1e-9)
        assertEquals("pipeline-1.0.0", requireNotNull(derived.activeGeneration()).algorithmVersion)
    }

    @Test
    fun `a package citing data the master has never seen is refused`() = runTest {
        // No import batch recorded: the estimates cannot be traced back to raw evidence.
        val preview = importer.preview(ByteArrayInputStream(packageBytes()))

        assertFalse(preview.canImport)
        assertTrue(
            preview.issues.any {
                it.problem == DerivedPackageImporter.Problem.UNKNOWN_SOURCE_DATASET &&
                    it.message.contains("OBS04_2026-09-14")
            },
        )
    }

    @Test
    fun `a tampered file fails its checksum`() = runTest {
        recordCommittedBatch("OBS04_2026-09-14")
        val tampered = packageBytes(
            transform = { entries ->
                entries + ("zone_transitions.json" to "[]".encodeToByteArray())
            },
            recomputeChecksums = false,
        )

        val preview = importer.preview(ByteArrayInputStream(tampered))
        assertFalse(preview.canImport)
        assertTrue(
            preview.issues.any { it.problem == DerivedPackageImporter.Problem.CHECKSUM_MISMATCH },
        )
    }

    @Test
    fun `reprocessing adds a generation beside the old one rather than replacing it`() = runTest {
        recordCommittedBatch("OBS04_2026-09-14")
        importer.commit(importer.preview(ByteArrayInputStream(packageBytes())))

        val second = packageBytes(algorithmVersion = "pipeline-1.1.0")
        val preview = importer.preview(ByteArrayInputStream(second))
        assertTrue(preview.issues.toString(), preview.canImport)
        importer.commit(preview)

        val generations = derived.observeGenerations().first().map { it.algorithmVersion }
        assertEquals(setOf("pipeline-1.0.0", "pipeline-1.1.0"), generations.toSet())

        // Discarding the newer generation leaves the older one, and its estimates, intact.
        derived.discard("pipeline-1.1.0")
        assertNotNull(derived.summarise("pipeline-1.0.0"))
        assertNull(derived.summarise("pipeline-1.1.0"))
    }

    @Test
    fun `re-importing the same generation is idempotent and flagged, not blocked`() = runTest {
        recordCommittedBatch("OBS04_2026-09-14")
        importer.commit(importer.preview(ByteArrayInputStream(packageBytes())))

        val again = importer.preview(ByteArrayInputStream(packageBytes()))
        assertTrue(again.issues.any { it.problem == DerivedPackageImporter.Problem.ALREADY_IMPORTED })
        assertTrue("a repeat is a warning, not a rejection", again.canImport)

        importer.commit(again)
        assertEquals(2, requireNotNull(derived.summarise("pipeline-1.0.0")).byTier.values.sum())
    }

    @Test
    fun `an estimate carrying a coordinate without uncertainty cannot enter the database`() = runTest {
        recordCommittedBatch("OBS04_2026-09-14")

        // Hand-written JSON, because the Kotlin constructor refuses to build this record at all.
        val fabricated = """
            [{"estimate_id":"E-9","algorithm_version":"pipeline-1.0.0","engine_versions":{},
              "parameter_set_sha256":null,"device_id":"DEV-1","timestamp_utc":"2026-09-14T09:00:00.000Z",
              "computed_at_utc":"2026-09-14T23:00:00.000Z","precision_tier":"APPROXIMATE_POSITION",
              "building_id":"B7","zone_id":"B7-CENTER","x":12.0,"y":9.0,
              "horizontal_uncertainty_m":null,"confidence":0.8,"confidence_factors":{},
              "method":"pos_wknn_centroid_v1","supporting_observer_ids":["OBS-04"],
              "supporting_observation_ids":["${RoomFixtures.observationId(1)}"],
              "source_dataset_ids":["OBS04_2026-09-14"],"reference_model_id":null,
              "calibration_set_id":null,"quality_flags":[]}]
        """.trimIndent()

        val bytes = packageBytes(
            transform = { it + ("position_estimates.json" to fabricated.encodeToByteArray()) },
        )
        val preview = importer.preview(ByteArrayInputStream(bytes))

        assertFalse(preview.canImport)
        assertTrue(
            preview.issues.toString(),
            preview.issues.any { it.problem == DerivedPackageImporter.Problem.INVARIANT_VIOLATION },
        )
    }

    // -- fixtures ---------------------------------------------------------------------------------

    private suspend fun recordCommittedBatch(name: String) {
        db.importBatchDao().upsert(
            ImportBatchEntity(
                importBatchId = "batch-1",
                observerId = RoomFixtures.OBSERVER,
                packageName = "$name.zip",
                packageSha256 = "0".repeat(64),
                importedAtUtc = "2026-09-14T22:00:00.000Z",
                schemaVersion = "1.0.0",
                declaredCount = 10,
                acceptedCount = 10,
                duplicateCount = 0,
                invalidCount = 0,
                manifestJson = "{}",
                issuesJson = null,
                operator = "admin@site",
                status = ImportBatchEntity.Status.COMMITTED.name,
            ),
        )
    }

    private fun packageBytes(
        algorithmVersion: String = "pipeline-1.0.0",
        transform: (Map<String, ByteArray>) -> Map<String, ByteArray> = { it },
        recomputeChecksums: Boolean = true,
    ): ByteArray {
        val estimates = listOf(
            PositionEstimate(
                estimateId = "E-1",
                algorithmVersion = algorithmVersion,
                deviceId = "DEV-1",
                timestampUtc = "2026-09-14T09:00:00.000Z",
                computedAtUtc = "2026-09-14T23:00:00.000Z",
                precisionTier = PrecisionTier.ZONE,
                buildingId = "B7",
                zoneId = "B7-CENTER",
                confidence = 0.94,
                method = "zone_bayes_v1",
                supportingObserverIds = listOf(RoomFixtures.OBSERVER),
                supportingObservationIds = listOf(RoomFixtures.observationId(1)),
                sourceDatasetIds = listOf("OBS04_2026-09-14"),
            ),
            PositionEstimate(
                estimateId = "E-2",
                algorithmVersion = algorithmVersion,
                deviceId = "DEV-2",
                timestampUtc = "2026-09-14T09:05:00.000Z",
                computedAtUtc = "2026-09-14T23:00:00.000Z",
                precisionTier = PrecisionTier.APPROXIMATE_POSITION,
                buildingId = "B7",
                zoneId = "B7-CENTER",
                x = 11.5,
                y = 8.25,
                horizontalUncertaintyM = 6.4,
                confidence = 0.71,
                method = "pos_wknn_centroid_v1",
                supportingObserverIds = listOf(RoomFixtures.OBSERVER),
                supportingObservationIds = listOf(RoomFixtures.observationId(2)),
                sourceDatasetIds = listOf("OBS04_2026-09-14"),
            ),
        )

        val transitions = listOf(
            ZoneTransition(
                transitionId = "T-1",
                algorithmVersion = algorithmVersion,
                deviceId = "DEV-1",
                eventType = ZoneEventType.RF_ZONE_ENTER,
                destinationZoneId = "B7-CENTER",
                transitionStartUtc = "2026-09-14T08:58:00.000Z",
                transitionConfirmedUtc = "2026-09-14T09:00:00.000Z",
                confidence = 0.88,
                topologyStatus = TopologyStatus.UNKNOWN_EDGE,
                supportingObserverIds = listOf(RoomFixtures.OBSERVER),
                supportingEstimateIds = listOf("E-1"),
            ),
        )

        val manifest = DerivedManifest(
            packageType = PackageType.DERIVED,
            exportId = "export-$algorithmVersion",
            createdAt = "2026-09-14T23:10:04.000Z",
            algorithmVersion = algorithmVersion,
            engineVersions = mapOf("zone_engine" to "1.1.3", "positioning_engine" to "1.0.0"),
            sourceDatasetIds = listOf("OBS04_2026-09-14"),
            datasetKind = DatasetKind.REAL,
            counts = mapOf("position_estimates" to 2L, "zone_transitions" to 1L),
            generator = Generator("rfmapper_lab", "1.0.0"),
        )

        val content = transform(
            linkedMapOf(
                "manifest.json" to RfMapperJson.compact
                    .encodeToString(DerivedManifest.serializer(), manifest).encodeToByteArray(),
                "position_estimates.json" to RfMapperJson.compact
                    .encodeToString(ListSerializer(PositionEstimate.serializer()), estimates).encodeToByteArray(),
                "zone_transitions.json" to RfMapperJson.compact
                    .encodeToString(ListSerializer(ZoneTransition.serializer()), transitions).encodeToByteArray(),
            ),
        )

        val all = content + ("checksum.txt" to checksumFor(content, recomputeChecksums).encodeToByteArray())

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in all) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** When [honest] is false the digests are computed before the tamper, as a real attack would leave them. */
    private fun checksumFor(content: Map<String, ByteArray>, honest: Boolean): String {
        val digests = content.mapValues { Sha256.hex(it.value) }.toMutableMap()
        if (!honest) digests["zone_transitions.json"] = "f".repeat(64)
        return Sha256.checksumFile(digests)
    }
}
