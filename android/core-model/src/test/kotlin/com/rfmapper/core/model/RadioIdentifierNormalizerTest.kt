package com.rfmapper.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioIdentifierNormalizerTest {

    @Test
    fun `mac normalization is canonical and separator agnostic`() {
        val expected = "aa:bb:0c:01:02:03"
        for (input in listOf(
            "aa:bb:0c:01:02:03",
            "AA:BB:0C:01:02:03",
            "AA-BB-C-01-02-03",
            "aa-bb-0c-01-02-03",
            "aabb0c010203",
            "  AA:BB:0C:01:02:03  ",
        )) {
            assertEquals(expected, RadioIdentifierNormalizer.normalizeMac(input), "input '$input'")
        }
    }

    @Test
    fun `mac normalization rejects malformed input`() {
        for (input in listOf(null, "", "not-a-mac", "aa:bb:cc", "aa:bb:cc:dd:ee:ff:00", "zz:bb:cc:dd:ee:ff")) {
            assertNull(RadioIdentifierNormalizer.normalizeMac(input), "input '$input'")
        }
    }

    @Test
    fun `the android unknown-bssid sentinel becomes null`() {
        // Android has historically reported this placeholder instead of null; storing it would
        // create a phantom access point shared across the whole site.
        assertNull(RadioIdentifierNormalizer.normalizeMac("02:00:00:00:00:00"))
        assertNull(RadioIdentifierNormalizer.normalizeMac("00:00:00:00:00:00"))
    }

    @Test
    fun `short bluetooth uuids expand to the base uuid`() {
        val expected = "0000180f-0000-1000-8000-00805f9b34fb"
        for (input in listOf(
            "180F",
            "180f",
            "0x180F",
            "0000180F",
            "0000180f-0000-1000-8000-00805f9b34fb",
            "0000180F00001000800000805F9B34FB",
        )) {
            assertEquals(expected, RadioIdentifierNormalizer.normalizeUuid(input), "input '$input'")
        }
    }

    @Test
    fun `uuid normalization rejects malformed input`() {
        for (input in listOf(null, "", "xyz", "180", "0000180f-0000")) {
            assertNull(RadioIdentifierNormalizer.normalizeUuid(input), "input '$input'")
        }
    }

    @Test
    fun `hex payloads normalize to uppercase without separators`() {
        assertEquals("004C0215A1B2", RadioIdentifierNormalizer.normalizeHex("00:4c:02:15:a1:b2"))
        assertEquals("004C0215", RadioIdentifierNormalizer.normalizeHex("00 4c 02 15"))
        assertNull(RadioIdentifierNormalizer.normalizeHex(""))
        assertNull(RadioIdentifierNormalizer.normalizeHex("nothex"))
    }

    @Test
    fun `manufacturer data encodes the company id as four hex digits`() {
        assertEquals(
            "004C0215A1B2",
            RadioIdentifierNormalizer.encodeManufacturerData(0x004C, byteArrayOf(0x02, 0x15, 0xA1.toByte(), 0xB2.toByte())),
        )
        assertNull(RadioIdentifierNormalizer.encodeManufacturerData(0x1FFFF, byteArrayOf()))
    }

    @Test
    fun `ssid bytes are preserved exactly`() {
        // Trailing spaces are legitimate and distinguishing; case folding would merge real networks.
        assertEquals("Guest  ", RadioIdentifierNormalizer.normalizeSsid("Guest  "))
        assertEquals("MiXeD", RadioIdentifierNormalizer.normalizeSsid("MiXeD"))
        assertEquals("quoted", RadioIdentifierNormalizer.normalizeSsid("\"quoted\""))
        // A hidden network scans as an empty SSID, which is null rather than "".
        assertNull(RadioIdentifierNormalizer.normalizeSsid(""))
        assertNull(RadioIdentifierNormalizer.normalizeSsid("\"\""))
    }

    @Test
    fun `locally administered addresses are detected as randomized`() {
        assertTrue(RadioIdentifierNormalizer.isRandomBleAddress("42:1a:2b:3c:4d:5e"))
        assertTrue(RadioIdentifierNormalizer.isRandomBleAddress("02:00:11:22:33:44"))
        assertFalse(RadioIdentifierNormalizer.isRandomBleAddress("d1:e2:f3:04:15:26"))

        assertEquals(
            IdentifierType.BLE_MAC_RANDOM,
            RadioIdentifierNormalizer.identifierTypeForBleAddress("42:1a:2b:3c:4d:5e"),
        )
        assertEquals(
            IdentifierType.BLE_MAC_PUBLIC,
            RadioIdentifierNormalizer.identifierTypeForBleAddress("d1:e2:f3:04:15:26"),
        )
    }

    @Test
    fun `channel derivation covers the common bands`() {
        assertEquals(1, RadioIdentifierNormalizer.channelForFrequency(2412))
        assertEquals(6, RadioIdentifierNormalizer.channelForFrequency(2437))
        assertEquals(13, RadioIdentifierNormalizer.channelForFrequency(2472))
        assertEquals(14, RadioIdentifierNormalizer.channelForFrequency(2484))
        assertEquals(36, RadioIdentifierNormalizer.channelForFrequency(5180))
        assertEquals(165, RadioIdentifierNormalizer.channelForFrequency(5825))
        assertEquals(1, RadioIdentifierNormalizer.channelForFrequency(5955))
        assertNull(RadioIdentifierNormalizer.channelForFrequency(null))
        assertNull(RadioIdentifierNormalizer.channelForFrequency(1234))
    }
}
