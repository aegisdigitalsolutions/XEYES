package com.rfmapper.core.model

import kotlinx.datetime.Instant

/**
 * ISO-8601 UTC timestamps with fixed millisecond precision, e.g. `2026-09-14T08:19:04.312Z`.
 *
 * Fixed precision matters: `Instant.toString()` omits a zero millisecond component, which would
 * make two timestamps one millisecond apart serialize with different widths and break both the
 * CSV column contract and byte-identical re-export.
 */
object Iso8601 {

    private val PATTERN =
        Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""")

    fun isValid(text: String): Boolean {
        if (!PATTERN.matches(text)) return false
        return runCatching { Instant.parse(text) }.isSuccess
    }

    fun format(epochMillis: Long): String {
        val instant = Instant.fromEpochMilliseconds(epochMillis)
        val whole = Instant.fromEpochSeconds(instant.epochSeconds, 0)
        val millis = ((epochMillis % 1000) + 1000) % 1000
        val base = whole.toString().removeSuffix("Z").substringBefore('.')
        return buildString(24) {
            append(base)
            append('.')
            append(millis.toString().padStart(3, '0'))
            append('Z')
        }
    }

    fun parseToEpochMillis(text: String): Long? {
        if (!PATTERN.matches(text)) return null
        return runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
    }

    /** Start of the UTC day containing [epochMillis]. */
    fun startOfUtcDay(epochMillis: Long): Long {
        val dayMillis = 86_400_000L
        return Math.floorDiv(epochMillis, dayMillis) * dayMillis
    }

    /** `YYYY-MM-DD` for the UTC day containing [epochMillis]; used in export package names. */
    fun utcDateStamp(epochMillis: Long): String = format(epochMillis).substring(0, 10)
}
