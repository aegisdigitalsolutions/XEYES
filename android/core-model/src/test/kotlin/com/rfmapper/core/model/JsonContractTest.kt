package com.rfmapper.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonContractTest {

    @Test
    fun `observation json uses the canonical snake_case field names`() {
        val encoded = RfMapperJson.compact.encodeToString(Observation.serializer(), Fixtures.wifiScan())
        val keys = (Json.parseToJsonElement(encoded) as JsonObject).keys
        assertEquals(
            setOf(
                "observation_id", "schema_version", "timestamp_utc", "observer_id",
                "observer_device_type", "sensor_type", "target_device_id", "radio_identifier",
                "identifier_type", "ssid", "bssid", "ble_service_uuid", "manufacturer_data",
                "rssi", "tx_power", "frequency", "channel", "rtt_distance_mm", "rtt_stddev_mm",
                "latitude", "longitude", "horizontal_accuracy", "building_id", "zone_id",
                "x_coordinate", "y_coordinate", "confidence", "metadata",
            ),
            keys,
        )
    }

    @Test
    fun `nullable fields stay present so a package is readable without the schema`() {
        val encoded = RfMapperJson.compact.encodeToString(Observation.serializer(), Fixtures.wifiScan())
        assertTrue(encoded.contains("\"target_device_id\":null"))
    }

    @Test
    fun `json round trip is lossless for all three sensor kinds`() {
        for (observation in listOf(Fixtures.wifiScan(), Fixtures.ble(), Fixtures.rtt())) {
            val encoded = RfMapperJson.compact.encodeToString(Observation.serializer(), observation)
            val decoded = RfMapperJson.compact.decodeFromString(Observation.serializer(), encoded)
            assertEquals(observation, decoded)
        }
    }

    @Test
    fun `unknown fields from a later minor version are tolerated`() {
        val encoded = RfMapperJson.compact
            .encodeToString(Observation.serializer(), Fixtures.wifiScan())
            .dropLast(1) + ""","a_field_from_1_1_0":"value"}"""
        val decoded = RfMapperJson.compact.decodeFromString(Observation.serializer(), encoded)
        assertEquals(Fixtures.wifiScan(), decoded)
    }

    @Test
    fun `deserialization enforces the same invariants as construction`() {
        // A hand-edited or hostile package cannot bypass the model's guarantees.
        val invalid = """
            {"observation_id":"not-a-uuid","schema_version":"1.0.0",
             "timestamp_utc":"2026-09-14T08:19:04.312Z","observer_id":"OBS-04",
             "observer_device_type":"ANDROID_PHONE","sensor_type":"WIFI_SCAN",
             "radio_identifier":"aa:bb:cc:11:22:33","identifier_type":"WIFI_BSSID"}
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            RfMapperJson.compact.decodeFromString(Observation.serializer(), invalid)
        }
    }

    @Test
    fun `observer identity round trips including capability declarations`() {
        val observer = Fixtures.observer()
        val encoded = RfMapperJson.compact.encodeToString(ObserverIdentity.serializer(), observer)
        assertEquals(observer, RfMapperJson.compact.decodeFromString(ObserverIdentity.serializer(), encoded))
        assertTrue(encoded.contains("\"platform\":\"android\""))
        assertTrue(encoded.contains("\"unsupported\":[\"RTT\"]"))
    }

    @Test
    fun `a capability cannot be both supported and unsupported`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.observer().copy(
                capabilities = setOf(ObserverCapability.BLE),
                unsupported = setOf(ObserverCapability.BLE),
            )
        }
    }

    @Test
    fun `a fixed observer must actually have a known position`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.observer().copy(fixedObserver = true, xCoordinate = null, yCoordinate = null)
        }
        val valid = Fixtures.observer().copy(
            fixedObserver = true,
            buildingId = "B4",
            xCoordinate = 10.0,
            yCoordinate = 20.0,
        )
        assertTrue(valid.fixedObserver)
    }

    @Test
    fun `file safe observer id strips punctuation for package names`() {
        assertEquals("OBS04", Fixtures.observer("OBS-04").fileSafeId)
    }
}
