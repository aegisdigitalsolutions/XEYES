package com.rfmapper.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaVersionTest {

    @Test
    fun `the current version is readable`() {
        assertTrue(SchemaVersion.isReadable(SchemaVersion.CURRENT))
    }

    @Test
    fun `a newer minor or patch is readable`() {
        // Minor and patch changes are additive, so an older build must accept them.
        assertTrue(SchemaVersion.isReadable("1.1.0"))
        assertTrue(SchemaVersion.isReadable("1.0.7"))
        assertTrue(SchemaVersion.isReadable("1.42.9"))
    }

    @Test
    fun `an unknown major is rejected rather than guessed`() {
        assertFalse(SchemaVersion.isReadable("2.0.0"))
        assertFalse(SchemaVersion.isReadable("0.9.0"))
    }

    @Test
    fun `malformed versions are rejected`() {
        for (bad in listOf("", "1", "1.0", "1.0.0.0", "v1.0.0", "1.0.0-rc1")) {
            assertFalse(SchemaVersion.isReadable(bad), "'$bad' should not be readable")
            assertNull(SchemaVersion.parse(bad))
        }
    }

    @Test
    fun `a newer minor is flagged as possibly carrying unknown fields`() {
        assertTrue(SchemaVersion.mayContainUnknownFields("1.1.0"))
        assertFalse(SchemaVersion.mayContainUnknownFields("1.0.0"))
        assertFalse(SchemaVersion.mayContainUnknownFields("1.0.9"))
        assertFalse(SchemaVersion.mayContainUnknownFields("2.0.0"))
    }

    @Test
    fun `parsing yields the components`() {
        assertEquals(SchemaVersion.Parsed(1, 2, 3), SchemaVersion.parse("1.2.3"))
    }
}

class Iso8601Test {

    @Test
    fun `formatting always uses three fractional digits`() {
        // Instant.toString() would render these as ".00Z" and "Z", which would break the fixed-width
        // CSV column contract and make two exports of the same data differ byte-for-byte.
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0))
        assertEquals("1970-01-01T00:00:00.001Z", Iso8601.format(1))
        assertEquals("1970-01-01T00:00:00.010Z", Iso8601.format(10))
        assertEquals("1970-01-01T00:00:01.000Z", Iso8601.format(1_000))

        val millis = Iso8601.parseToEpochMillis("2026-09-14T08:19:04.312Z")!!
        assertEquals("2026-09-14T08:19:04.312Z", Iso8601.format(millis))
    }

    @Test
    fun `format and parse round trip`() {
        for (millis in listOf(0L, 1L, 999L, 1_000L, 1_757_836_744_312L, 2_000_000_000_000L)) {
            val text = Iso8601.format(millis)
            assertTrue(Iso8601.isValid(text), "'$text' should be valid")
            assertEquals(millis, Iso8601.parseToEpochMillis(text))
        }
    }

    @Test
    fun `validation requires exactly three fractional digits and a literal Z`() {
        assertTrue(Iso8601.isValid("2026-09-14T08:19:04.312Z"))
        assertFalse(Iso8601.isValid("2026-09-14T08:19:04Z"))
        assertFalse(Iso8601.isValid("2026-09-14T08:19:04.31Z"))
        assertFalse(Iso8601.isValid("2026-09-14T08:19:04.3125Z"))
        assertFalse(Iso8601.isValid("2026-09-14T08:19:04.312+00:00"))
        assertFalse(Iso8601.isValid("2026-13-14T08:19:04.312Z"))
    }

    @Test
    fun `utc day helpers support day-scoped exports`() {
        val noon = Iso8601.parseToEpochMillis("2026-09-14T12:34:56.789Z")!!
        assertEquals("2026-09-14T00:00:00.000Z", Iso8601.format(Iso8601.startOfUtcDay(noon)))
        assertEquals("2026-09-14", Iso8601.utcDateStamp(noon))
    }
}
