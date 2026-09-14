package com.rfmapper.data.room

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.model.Building
import com.rfmapper.core.model.FingerprintEntry
import com.rfmapper.core.model.FingerprintPoint
import com.rfmapper.core.model.FingerprintStatus
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.InfrastructureNode
import com.rfmapper.core.model.InfrastructureType
import com.rfmapper.core.model.Point
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SiteFrame
import com.rfmapper.core.model.SiteModel
import com.rfmapper.core.model.SurveyPoint
import com.rfmapper.core.model.Zone
import com.rfmapper.core.model.ZoneEdge
import com.rfmapper.core.model.ZoneEdgeType
import com.rfmapper.core.model.ZoneKind
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class SiteModelIoTest {

    private lateinit var db: RfMapperDatabase
    private lateinit var io: SiteModelIo
    private lateinit var reference: ReferenceRepository

    @Before
    fun setUp() {
        db = RfMapperDatabase.openInMemory(ApplicationProvider.getApplicationContext())
        io = SiteModelIo(
            site = db.siteModelDao(),
            infrastructure = db.infrastructureDao(),
            observers = db.observerDao(),
            fingerprints = db.fingerprintDao(),
            calibration = db.observerCalibrationDao(),
            nowMillis = { RoomFixtures.DAY_START },
        )
        reference = ReferenceRepository(
            devices = db.managedDeviceDao(),
            infrastructure = db.infrastructureDao(),
            observers = db.observerDao(),
            site = db.siteModelDao(),
            fingerprints = db.fingerprintDao(),
            calibration = db.observerCalibrationDao(),
            nowMillis = { RoomFixtures.DAY_START },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a site model survives import and re-export`() = runTest {
        val preview = io.preview(bytes(SITE))
        assertNull(preview.parseError)
        assertTrue(preview.issues.toString(), preview.canApply)

        val applied = io.apply(requireNotNull(preview.model))
        assertEquals(2, applied.zones)
        assertEquals(1, applied.buildings)

        val exported = io.export(referenceModelId = "site-2026-09-01", frame = SITE.frame)
        assertEquals(SITE.buildings.map { it.buildingId }, exported.buildings.map { it.buildingId })
        assertEquals(SITE.zones.map { it.zoneId }.sorted(), exported.zones.map { it.zoneId }.sorted())
        assertEquals(1, exported.infrastructureNodes.size)
        assertEquals(1, exported.surveyPoints.size)
        assertTrue(exported.referentialIssues().isEmpty())
    }

    @Test
    fun `a zone referencing a missing building is reported rather than applied`() = runTest {
        val broken = SITE.copy(
            zones = SITE.zones + Zone(zoneId = "B9-EAST", buildingId = "B9", name = "East wing"),
        )
        val preview = io.preview(bytes(broken))

        assertFalse(preview.canApply)
        assertTrue(
            preview.issues.toString(),
            preview.issues.any { it.contains("B9-EAST") && it.contains("unknown building") },
        )
    }

    @Test
    fun `every referential problem is reported at once`() = runTest {
        val broken = SITE.copy(
            zones = SITE.zones + Zone(zoneId = "B9-EAST", buildingId = "B9", name = "East"),
            zoneEdges = SITE.zoneEdges + ZoneEdge("B7-CENTER", "B12-NOWHERE", ZoneEdgeType.DOOR),
        )
        // A fix-one-then-rediscover-the-next loop is what makes hand-authoring a site model
        // unbearable, so the validator reports the whole set.
        val preview = io.preview(bytes(broken))
        assertEquals(preview.issues.toString(), 2, preview.issues.size)
    }

    @Test
    fun `importing a model never promotes a fingerprint to ground truth`() = runTest {
        io.apply(SITE)
        reference.saveFingerprint(CANDIDATE)
        assertTrue(reference.promoteFingerprint(CANDIDATE.fingerprintId, promotedBy = "admin@site"))

        // The file claims CANDIDATE. A re-import must not demote a human's promotion, and a file
        // claiming GROUND_TRUTH must not grant itself one either.
        val reimported = SITE.copy(fingerprints = listOf(CANDIDATE))
        io.apply(reimported)

        val stored = requireNotNull(reference.fingerprint(CANDIDATE.fingerprintId))
        assertEquals(FingerprintStatus.GROUND_TRUTH, stored.status)
        assertEquals(
            "admin@site",
            requireNotNull(db.fingerprintDao().byId(CANDIDATE.fingerprintId)).promotedBy,
        )
    }

    @Test
    fun `only promoted fingerprints leave in an exported model`() = runTest {
        io.apply(SITE)
        reference.saveFingerprint(CANDIDATE)

        assertTrue(io.export("site-1", SITE.frame).fingerprints.isEmpty())

        reference.promoteFingerprint(CANDIDATE.fingerprintId, promotedBy = "admin@site")
        val exported = io.export("site-1", SITE.frame)
        assertEquals(1, exported.fingerprints.size)
        assertEquals(2, exported.fingerprints.single().entries.size)
    }

    @Test
    fun `observer enrolment is the master's decision and is never taken from the file`() = runTest {
        io.apply(SITE.copy(observers = listOf(RoomFixtures.observer())))
        assertNotNull(db.observerDao().byId(RoomFixtures.OBSERVER))
        assertTrue(db.observerDao().enrolledIds().isEmpty())

        reference.setEnrolled(RoomFixtures.OBSERVER, enrolled = true)
        io.apply(SITE.copy(observers = listOf(RoomFixtures.observer())))
        assertEquals(listOf(RoomFixtures.OBSERVER), db.observerDao().enrolledIds())
    }

    @Test
    fun `an RTT anchor without coordinates cannot anchor a range and is reported`() = runTest {
        val broken = SITE.copy(
            infrastructureNodes = listOf(
                InfrastructureNode(
                    nodeId = "AP-99",
                    friendlyName = "Unlocated ranging AP",
                    type = InfrastructureType.RTT_ANCHOR,
                    buildingId = "B7",
                    rttCapable = true,
                ),
            ),
        )
        assertTrue(broken.referentialIssues().any { it.contains("AP-99") && it.contains("cannot anchor") })
    }

    private fun bytes(model: SiteModel) =
        ByteArrayInputStream(RfMapperJson.compact.encodeToString(SiteModel.serializer(), model).encodeToByteArray())

    private companion object {
        val SITE = SiteModel(
            referenceModelId = "site-2026-09-01",
            createdAt = "2026-09-01T09:00:00.000Z",
            frame = SiteFrame(originLat = 51.5074, originLon = -0.1278, rotationDeg = 12.0),
            buildings = listOf(
                Building(
                    buildingId = "B7",
                    name = "Building 7",
                    floors = listOf(0, 1),
                    outlinePolygon = listOf(Point(0.0, 0.0), Point(40.0, 0.0), Point(40.0, 25.0), Point(0.0, 25.0)),
                ),
            ),
            zones = listOf(
                Zone(
                    zoneId = "B7-CENTER",
                    buildingId = "B7",
                    name = "Central floor",
                    zoneKind = ZoneKind.AREA,
                    polygon = listOf(Point(5.0, 5.0), Point(20.0, 5.0), Point(20.0, 18.0), Point(5.0, 18.0)),
                ),
                Zone(zoneId = "B7-DOCK", buildingId = "B7", name = "Loading dock", zoneKind = ZoneKind.AREA),
            ),
            zoneEdges = listOf(ZoneEdge("B7-CENTER", "B7-DOCK", ZoneEdgeType.DOOR, typicalTraversalS = 4.0)),
            infrastructureNodes = listOf(
                InfrastructureNode(
                    nodeId = "AP-12",
                    friendlyName = "Ceiling AP, central",
                    type = InfrastructureType.RTT_ANCHOR,
                    buildingId = "B7",
                    zoneId = "B7-CENTER",
                    x = 12.0,
                    y = 11.0,
                    knownBssid = "aa:bb:cc:11:22:33",
                    rttCapable = true,
                ),
            ),
            surveyPoints = listOf(
                SurveyPoint(
                    surveyPointId = "SP-1",
                    buildingId = "B7",
                    zoneId = "B7-CENTER",
                    x = 10.0,
                    y = 10.0,
                    label = "Under the central beam",
                    physicalDescription = "Standing on the floor marking, facing north",
                ),
            ),
        )

        val CANDIDATE = FingerprintPoint(
            fingerprintId = "FP-1",
            surveyPointId = "SP-1",
            buildingId = "B7",
            zoneId = "B7-CENTER",
            x = 10.0,
            y = 10.0,
            observerId = RoomFixtures.OBSERVER,
            status = FingerprintStatus.CANDIDATE,
            sampleCount = 120,
            sessionCount = 3,
            entries = listOf(
                FingerprintEntry(
                    radioIdentifier = "aa:bb:cc:11:22:33",
                    identifierType = IdentifierType.WIFI_BSSID,
                    sampleCount = 118,
                    visibilityProbability = 0.98,
                    rssiMedian = -58.0,
                    rssiStddev = 2.4,
                ),
                FingerprintEntry(
                    radioIdentifier = "aa:bb:cc:44:55:66",
                    identifierType = IdentifierType.WIFI_BSSID,
                    sampleCount = 71,
                    visibilityProbability = 0.59,
                    rssiMedian = -74.0,
                    rssiStddev = 6.1,
                ),
            ),
        )
    }
}
