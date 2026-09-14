package com.rfmapper.core.model.csv

import com.rfmapper.core.model.Fixtures
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ObservationCsvCodecTest {

    private val header = ObservationCsvCodec.COLUMNS

    private fun roundTrip(observation: Observation): Observation {
        val line = ObservationCsvCodec.encode(observation)
        val record = Csv.parse(line).single()
        return when (val result = ObservationCsvCodec.decode(header, record)) {
            is ObservationCsvCodec.DecodeResult.Success -> {
                assertTrue(result.checksumMatched, "row checksum should verify after a round trip")
                result.observation
            }
            is ObservationCsvCodec.DecodeResult.Failure -> fail("decode failed: ${result.reasons}")
        }
    }

    @Test
    fun `header matches the published column contract`() {
        assertEquals(29, ObservationCsvCodec.COLUMNS.size)
        assertEquals("observation_id", ObservationCsvCodec.COLUMNS.first())
        assertEquals("row_checksum", ObservationCsvCodec.COLUMNS.last())
        assertEquals(
            "observation_id,schema_version,timestamp_utc,observer_id,observer_device_type," +
                "sensor_type,target_device_id,radio_identifier,identifier_type,ssid,bssid," +
                "ble_service_uuid,manufacturer_data,rssi,tx_power,frequency,channel," +
                "rtt_distance_mm,rtt_stddev_mm,latitude,longitude,horizontal_accuracy,building_id," +
                "zone_id,x_coordinate,y_coordinate,confidence,metadata_json,row_checksum",
            ObservationCsvCodec.header,
        )
    }

    @Test
    fun `wifi ble and rtt observations survive a round trip`() {
        assertEquals(Fixtures.wifiScan(), roundTrip(Fixtures.wifiScan()))
        assertEquals(Fixtures.ble(), roundTrip(Fixtures.ble()))
        assertEquals(Fixtures.rtt(), roundTrip(Fixtures.rtt()))
    }

    @Test
    fun `encoding is deterministic regardless of metadata insertion order`() {
        val first = Fixtures.wifiScan(
            metadata = linkedMapOf("zulu" to "1", "alpha" to "2", MetadataKeys.SESSION_ID to "s"),
        )
        val second = Fixtures.wifiScan(
            metadata = linkedMapOf(MetadataKeys.SESSION_ID to "s", "alpha" to "2", "zulu" to "1"),
        )
        // Deterministic bytes are what make checksum.txt meaningful and re-exports diffable.
        assertEquals(ObservationCsvCodec.encode(first), ObservationCsvCodec.encode(second))
    }

    @Test
    fun `metadata json has sorted keys and compact form`() {
        assertEquals(
            """{"alpha":"2","zulu":"1"}""",
            ObservationCsvCodec.encodeMetadata(linkedMapOf("zulu" to "1", "alpha" to "2")),
        )
        assertEquals("{}", ObservationCsvCodec.encodeMetadata(emptyMap()))
    }

    @Test
    fun `an ssid containing a comma quote and newline round trips`() {
        val awkward = "Guest, \"Main\"\nWing"
        val observation = Fixtures.wifiScan().copy(ssid = awkward)
        assertEquals(awkward, roundTrip(observation).ssid)
    }

    @Test
    fun `an empty field decodes to null`() {
        val observation = Fixtures.wifiScan().copy(ssid = null, targetDeviceId = null)
        val decoded = roundTrip(observation)
        assertNull(decoded.ssid)
        assertNull(decoded.targetDeviceId)
    }

    @Test
    fun `decimals use plain notation with trailing zeros trimmed`() {
        assertEquals("11", ObservationCsvCodec.formatDecimal(11.0))
        assertEquals("24.5", ObservationCsvCodec.formatDecimal(24.5))
        assertEquals("0.000001", ObservationCsvCodec.formatDecimal(0.000001))
        // No exponent notation, which a naive Double.toString would produce here.
        assertEquals("0", ObservationCsvCodec.formatDecimal(0.00000001))
        assertEquals("-51.123457", ObservationCsvCodec.formatDecimal(-51.1234567))
        assertNull(ObservationCsvCodec.formatDecimal(null))
    }

    @Test
    fun `columns are matched by name so an appended column stays readable`() {
        val extendedHeader = header + "future_column"
        val record = ObservationCsvCodec.cells(Fixtures.wifiScan()).map { it ?: "" } + "future-value"
        when (val result = ObservationCsvCodec.decode(extendedHeader, record)) {
            is ObservationCsvCodec.DecodeResult.Success -> {
                // Unknown columns are preserved rather than discarded.
                assertEquals("future-value", result.observation.metadata["csv_extra_future_column"])
                assertTrue(result.checksumMatched)
            }
            is ObservationCsvCodec.DecodeResult.Failure -> fail("decode failed: ${result.reasons}")
        }
    }

    @Test
    fun `a reordered header is still decoded correctly`() {
        val observation = Fixtures.ble()
        val cells = ObservationCsvCodec.cells(observation)
        val permutation = header.indices.reversed().toList()
        val shuffledHeader = permutation.map { header[it] }
        val shuffledRecord = permutation.map { cells[it] ?: "" }
        when (val result = ObservationCsvCodec.decode(shuffledHeader, shuffledRecord)) {
            is ObservationCsvCodec.DecodeResult.Success -> assertEquals(observation, result.observation)
            is ObservationCsvCodec.DecodeResult.Failure -> fail("decode failed: ${result.reasons}")
        }
    }

    @Test
    fun `a missing required column is a header mismatch`() {
        val truncated = header - "rssi"
        val result = ObservationCsvCodec.decode(truncated, List(truncated.size) { "" })
        assertTrue(result is ObservationCsvCodec.DecodeResult.Failure)
        assertTrue(result.reasons.single().startsWith("CSV_HEADER_MISMATCH"))
    }

    @Test
    fun `a corrupted cell is reported rather than silently repaired`() {
        val cells = ObservationCsvCodec.cells(Fixtures.wifiScan()).map { it ?: "" }.toMutableList()
        cells[header.indexOf("rssi")] = "not-a-number"
        val result = ObservationCsvCodec.decode(header, cells)
        assertTrue(result is ObservationCsvCodec.DecodeResult.Failure)
        assertTrue(result.reasons.any { it.contains("INVALID_INTEGER") })
    }

    @Test
    fun `an unparseable enum is reported`() {
        val cells = ObservationCsvCodec.cells(Fixtures.wifiScan()).map { it ?: "" }.toMutableList()
        cells[header.indexOf("sensor_type")] = "TELEPATHY"
        val result = ObservationCsvCodec.decode(header, cells)
        assertTrue(result is ObservationCsvCodec.DecodeResult.Failure)
        assertTrue(result.reasons.any { it.contains("INVALID_ENUM") })
    }

    @Test
    fun `an edited cell is caught by the row checksum`() {
        // The scenario this guards: a CSV opened and re-saved by a spreadsheet.
        val cells = ObservationCsvCodec.cells(Fixtures.wifiScan()).map { it ?: "" }.toMutableList()
        cells[header.indexOf("rssi")] = "-42"
        when (val result = ObservationCsvCodec.decode(header, cells)) {
            is ObservationCsvCodec.DecodeResult.Success ->
                assertFalse(result.checksumMatched, "tampered row should fail its checksum")
            is ObservationCsvCodec.DecodeResult.Failure -> fail("row should decode but fail checksum")
        }
    }

    @Test
    fun `a row without a checksum is accepted so hand-written csv remains importable`() {
        val cells = ObservationCsvCodec.cells(Fixtures.wifiScan()).map { it ?: "" }.toMutableList()
        cells[header.indexOf("row_checksum")] = ""
        when (val result = ObservationCsvCodec.decode(header, cells)) {
            is ObservationCsvCodec.DecodeResult.Success -> assertTrue(result.checksumMatched)
            is ObservationCsvCodec.DecodeResult.Failure -> fail("decode failed: ${result.reasons}")
        }
    }

    @Test
    fun `non-finite values cannot be encoded`() {
        // Guarded at the model layer too, but the codec must never emit NaN or Infinity.
        assertEquals(null, ObservationCsvCodec.formatDecimal(null))
        val error = runCatching { ObservationCsvCodec.formatDecimal(Double.NaN) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
