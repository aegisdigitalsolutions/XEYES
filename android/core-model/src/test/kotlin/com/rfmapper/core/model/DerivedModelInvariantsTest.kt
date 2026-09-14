package com.rfmapper.core.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * These tests exist to make the specifications' anti-fabrication rules structural. Each one asserts
 * that a dishonest estimate is not merely discouraged but impossible to construct.
 */
class DerivedModelInvariantsTest {

    private fun estimate(
        tier: PrecisionTier = PrecisionTier.ZONE,
        x: Double? = null,
        y: Double? = null,
        uncertainty: Double? = null,
        buildingId: String? = "B9",
        zoneId: String? = "B9-EAST",
    ) = PositionEstimate(
        estimateId = "11111111-2222-4333-8444-555555555555",
        algorithmVersion = "pipeline-1.0.0",
        engineVersions = mapOf("zone_engine" to "1.1.3"),
        deviceId = "DEVICE-17",
        timestampUtc = "2026-09-14T08:24:00.000Z",
        computedAtUtc = "2026-09-14T23:10:04.000Z",
        precisionTier = tier,
        buildingId = buildingId,
        zoneId = zoneId,
        x = x,
        y = y,
        horizontalUncertaintyM = uncertainty,
        confidence = 0.94,
        method = "zone_bayes_v1",
        supportingObserverIds = listOf("OBS-04", "OBS-09"),
        supportingObservationIds = listOf("obs-1", "obs-2"),
    )

    @Test
    fun `a zone-only estimate with null coordinates is valid and preferred`() {
        val zoneOnly = estimate()
        assertNull(zoneOnly.x)
        assertNull(zoneOnly.horizontalUncertaintyM)
        assertEquals(PrecisionTier.ZONE, zoneOnly.precisionTier)
    }

    @Test
    fun `a coordinate without uncertainty cannot be constructed`() {
        val error = assertFailsWith<IllegalArgumentException> {
            estimate(tier = PrecisionTier.APPROXIMATE_POSITION, x = 12.0, y = 34.0, uncertainty = null)
        }
        assertContains(error.message!!, "horizontal_uncertainty_m")
    }

    @Test
    fun `uncertainty must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            estimate(tier = PrecisionTier.APPROXIMATE_POSITION, x = 12.0, y = 34.0, uncertainty = 0.0)
        }
    }

    @Test
    fun `a positional tier requires coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            estimate(tier = PrecisionTier.APPROXIMATE_POSITION)
        }
    }

    @Test
    fun `coordinates require a positional tier`() {
        val error = assertFailsWith<IllegalArgumentException> {
            estimate(tier = PrecisionTier.ZONE, x = 12.0, y = 34.0, uncertainty = 8.0)
        }
        assertContains(error.message!!, "APPROXIMATE_POSITION")
    }

    @Test
    fun `an approximate position with honest uncertainty is valid`() {
        val positioned = estimate(
            tier = PrecisionTier.APPROXIMATE_POSITION,
            x = 12.0,
            y = 34.0,
            uncertainty = 8.0,
        )
        assertEquals(8.0, positioned.horizontalUncertaintyM)
    }

    @Test
    fun `site presence must not assert a zone`() {
        assertFailsWith<IllegalArgumentException> {
            estimate(tier = PrecisionTier.SITE_PRESENCE, buildingId = null, zoneId = "B9-EAST")
        }
        val sitePresence = estimate(tier = PrecisionTier.SITE_PRESENCE, buildingId = null, zoneId = null)
        assertNull(sitePresence.zoneId)
    }

    @Test
    fun `a zone requires its building`() {
        assertFailsWith<IllegalArgumentException> { estimate(buildingId = null, zoneId = "B9-EAST") }
    }

    @Test
    fun `an estimate with no supporting observation is not an estimate`() {
        assertFailsWith<IllegalArgumentException> {
            estimate().copy(supportingObservationIds = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            estimate().copy(supportingObserverIds = emptyList())
        }
    }

    @Test
    fun `confidence is a probability`() {
        assertFailsWith<IllegalArgumentException> { estimate().copy(confidence = 1.2) }
    }

    @Test
    fun `a transition requires the endpoints implied by its event type`() {
        val base = ZoneTransition(
            transitionId = "22222222-3333-4444-8555-666666666666",
            algorithmVersion = "pipeline-1.0.0",
            deviceId = "DEVICE-17",
            eventType = ZoneEventType.RF_ZONE_TRANSITION,
            originZoneId = "B7-SOUTH",
            destinationZoneId = "B9-EAST",
            transitionStartUtc = "2026-09-14T08:24:00.000Z",
            transitionConfirmedUtc = "2026-09-14T08:24:45.000Z",
            confidence = 0.72,
            topologyStatus = TopologyStatus.ADJACENT,
            supportingObserverIds = listOf("OBS-04"),
            supportingEstimateIds = listOf("est-1", "est-2", "est-3"),
        )
        assertEquals(ZoneEventType.RF_ZONE_TRANSITION, base.eventType)

        assertFailsWith<IllegalArgumentException> { base.copy(destinationZoneId = null) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(eventType = ZoneEventType.RF_ZONE_EXIT, originZoneId = null, destinationZoneId = null)
        }
        // An entry into the site legitimately has no origin.
        val entered = base.copy(eventType = ZoneEventType.RF_ZONE_ENTER, originZoneId = null)
        assertNull(entered.originZoneId)
    }

    @Test
    fun `a non-adjacent transition is representable so the site graph can be questioned`() {
        // Topology must never silently override measurement; the anomaly is emitted and flagged.
        val suspicious = ZoneTransition(
            transitionId = "33333333-4444-4555-8666-777777777777",
            algorithmVersion = "pipeline-1.0.0",
            deviceId = "DEVICE-17",
            eventType = ZoneEventType.RF_ZONE_TRANSITION,
            originZoneId = "B4-CENTER",
            destinationZoneId = "B9-EAST",
            transitionStartUtc = "2026-09-14T08:24:00.000Z",
            transitionConfirmedUtc = "2026-09-14T08:24:50.000Z",
            confidence = 0.55,
            topologyStatus = TopologyStatus.NON_ADJACENT,
            supportingObserverIds = listOf("OBS-04"),
            supportingEstimateIds = listOf("est-9"),
            qualityFlags = listOf("TOPOLOGY_VIOLATION"),
        )
        assertContains(suspicious.qualityFlags, "TOPOLOGY_VIOLATION")
    }

    @Test
    fun `a lost device may have no supporting estimates but a moving one may not`() {
        val lost = MovementEstimate(
            movementId = "44444444-5555-4666-8777-888888888888",
            algorithmVersion = "pipeline-1.0.0",
            deviceId = "DEVICE-17",
            timestampUtc = "2026-09-14T09:00:00.000Z",
            state = MovementState.LOST,
            confidence = 0.5,
        )
        assertEquals(MovementState.LOST, lost.state)

        assertFailsWith<IllegalArgumentException> { lost.copy(state = MovementState.MOVING) }
    }

    @Test
    fun `a zone transition movement state names its origin and candidate`() {
        assertFailsWith<IllegalArgumentException> {
            MovementEstimate(
                movementId = "55555555-6666-4777-8888-999999999999",
                algorithmVersion = "pipeline-1.0.0",
                deviceId = "DEVICE-17",
                timestampUtc = "2026-09-14T08:24:00.000Z",
                state = MovementState.ZONE_TRANSITION,
                confidence = 0.6,
                supportingEstimateIds = listOf("est-1"),
            )
        }
    }

    @Test
    fun `rtt capability on an infrastructure node requires the rtt anchor type`() {
        // Prevents a datasheet claim from being recorded as a ranging capability.
        assertFailsWith<IllegalArgumentException> {
            InfrastructureNode(
                nodeId = "AP-LONGRANGE-1",
                friendlyName = "Yard router",
                type = InfrastructureType.ZONE_ANCHOR,
                rttCapable = true,
            )
        }
        val anchor = InfrastructureNode(
            nodeId = "AP-7",
            friendlyName = "B7 ceiling AP",
            type = InfrastructureType.RTT_ANCHOR,
            buildingId = "B7",
            x = 10.0,
            y = 20.0,
            rttCapable = true,
        )
        assertEquals(true, anchor.isLocatedAnchor)
    }

    @Test
    fun `managed device identifiers are normalized for attribution lookups`() {
        val device = ManagedDevice(
            deviceId = "DEVICE-03",
            friendlyName = "Tag 3",
            deviceType = "BLE_TAG",
            knownWifiIdentifiers = listOf("AA-BB-CC-11-22-33"),
            knownBleIdentifiers = listOf("D1:E2:F3:04:15:26", "42:1a:2b:3c:4d:5e"),
            knownServiceUuids = listOf("180F"),
        )
        assertEquals(
            listOf(
                "aa:bb:cc:11:22:33" to IdentifierType.WIFI_BSSID,
                "d1:e2:f3:04:15:26" to IdentifierType.BLE_MAC_PUBLIC,
                "42:1a:2b:3c:4d:5e" to IdentifierType.BLE_MAC_RANDOM,
                "0000180f-0000-1000-8000-00805f9b34fb" to IdentifierType.BLE_SERVICE_UUID,
            ),
            device.allIdentifiers,
        )
    }

    @Test
    fun `fingerprint visibility probability is bounded`() {
        assertFailsWith<IllegalArgumentException> {
            FingerprintEntry(
                radioIdentifier = Fixtures.AP_BSSID,
                identifierType = IdentifierType.WIFI_BSSID,
                sampleCount = 60,
                visibilityProbability = 1.4,
                rssiMedian = -51.0,
            )
        }
    }
}
