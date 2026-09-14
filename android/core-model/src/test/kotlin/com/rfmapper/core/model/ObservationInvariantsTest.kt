package com.rfmapper.core.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObservationInvariantsTest {

    @Test
    fun `a valid wifi observation is constructible`() {
        val observation = Fixtures.wifiScan()
        assertEquals(SensorType.WIFI_SCAN, observation.sensorType)
        assertEquals(-61, observation.rssi)
    }

    @Test
    fun `observation_id must be a lowercase uuid`() {
        // Upper-case hex is rejected rather than coerced: silently lower-casing would let two
        // encodings of one id coexist and defeat deduplication.
        val error = assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan(observationId = "4F1C9A2E-6B3D-4C58-9E77-0A1B2C3D4E5F")
        }
        assertContains(error.message!!, "observation_id")
    }

    @Test
    fun `observer_id is mandatory on every observation`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan(observerId = "  ") }
    }

    @Test
    fun `timestamp must carry fixed millisecond precision and a literal Z`() {
        for (bad in listOf(
            "2026-09-14T08:19:04Z",
            "2026-09-14T08:19:04.31Z",
            "2026-09-14T08:19:04.312+01:00",
            "2026-09-14 08:19:04.312Z",
        )) {
            assertFailsWith<IllegalArgumentException>("expected '$bad' to be rejected") {
                Fixtures.wifiScan(timestampUtc = bad)
            }
        }
    }

    @Test
    fun `rtt fields require sensor_type RTT`() {
        // The structural guard against an RSSI-derived distance masquerading as a genuine range.
        val error = assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan().copy(rttDistanceMm = 11_840)
        }
        assertContains(error.message!!, "sensor_type=RTT")
    }

    @Test
    fun `a genuine rtt observation is accepted`() {
        val rtt = Fixtures.rtt()
        assertEquals(11_840, rtt.rttDistanceMm)
        assertEquals(1_320, rtt.rttStddevMm)
    }

    @Test
    fun `gps observations require both coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan().copy(sensorType = SensorType.GPS, latitude = 51.5, longitude = null)
        }
    }

    @Test
    fun `site-local coordinates require a building`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan().copy(buildingId = null, xCoordinate = 24.5, yCoordinate = 11.0)
        }
        assertContains(error.message!!, "building_id")
    }

    @Test
    fun `x and y are all-or-nothing`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan().copy(xCoordinate = 24.5, yCoordinate = null)
        }
    }

    @Test
    fun `confidence is a probability`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(confidence = 1.4) }
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(confidence = -0.1) }
    }

    @Test
    fun `non-finite numerics are rejected`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(confidence = Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            Fixtures.wifiScan().copy(horizontalAccuracy = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `rssi outside radio range is rejected`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(rssi = -400) }
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(rssi = 60) }
    }

    @Test
    fun `bssid must be normalized before construction`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.wifiScan().copy(bssid = "AA:BB:CC:11:22:33") }
    }

    @Test
    fun `manufacturer data must be uppercase hex and never empty`() {
        assertFailsWith<IllegalArgumentException> { Fixtures.ble().copy(manufacturerData = "") }
        assertFailsWith<IllegalArgumentException> { Fixtures.ble().copy(manufacturerData = "004c0215") }
        assertFailsWith<IllegalArgumentException> { Fixtures.ble().copy(manufacturerData = "00:4C") }
    }

    @Test
    fun `derived accessors read reserved metadata keys`() {
        val survey = Fixtures.wifiScan(
            metadata = mapOf(
                MetadataKeys.SAMPLE_KIND to SampleKind.GROUND_TRUTH.name,
                MetadataKeys.SESSION_ID to "session-1",
                MetadataKeys.RESULT_FRESHNESS to ResultFreshness.CACHED.name,
                MetadataKeys.PERMISSION_DEGRADED to "true",
            ),
        )
        assertEquals(SampleKind.GROUND_TRUTH, survey.sampleKind)
        assertEquals("session-1", survey.sessionId)
        assertEquals(ResultFreshness.CACHED, survey.resultFreshness)
        assertTrue(survey.isPermissionDegraded)
    }

    @Test
    fun `unknown metadata keys are preserved verbatim`() {
        val observation = Fixtures.wifiScan(metadata = mapOf("future_key_from_a_later_minor" to "x"))
        assertEquals("x", observation.metadata["future_key_from_a_later_minor"])
    }

    @Test
    fun `sample kind defaults to ordinary when unset`() {
        assertEquals(SampleKind.ORDINARY, Fixtures.wifiScan().sampleKind)
    }
}
