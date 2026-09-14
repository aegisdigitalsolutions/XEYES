package com.rfmapper.core.model.csv

/**
 * RFC 4180 encoding and an incremental record parser.
 *
 * The parser is a character-fed state machine rather than a line reader because a quoted field may
 * legitimately contain a newline (an SSID can contain anything), so splitting on `\n` first would
 * corrupt such rows. Feeding characters also keeps the module free of I/O while still allowing the
 * import engine to stream a 500k-row file in constant memory.
 */
object Csv {

    const val SEPARATOR = ','
    const val QUOTE = '"'

    /** Quotes [value] only when required, escaping embedded quotes by doubling them. */
    fun encodeField(value: String?): String {
        if (value == null) return ""
        val needsQuoting = value.any { it == SEPARATOR || it == QUOTE || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return buildString(value.length + 2) {
            append(QUOTE)
            for (char in value) {
                if (char == QUOTE) append(QUOTE)
                append(char)
            }
            append(QUOTE)
        }
    }

    fun encodeRow(fields: List<String?>): String = fields.joinToString(",") { encodeField(it) }

    /** Parses complete CSV text. Convenience for tests and small files; large files use [RecordParser]. */
    fun parse(text: CharSequence): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val parser = RecordParser()
        parser.feed(text) { records += it }
        parser.finish { records += it }
        return records
    }

    /**
     * Incremental parser. Call [feed] with successive chunks and [finish] once at the end; the
     * callback receives one record per complete row.
     *
     * A field is returned as the empty string when it was empty in the source. Distinguishing an
     * empty field from a quoted empty string is impossible in CSV, which is why JSON is the
     * canonical format and both decode to null.
     */
    class RecordParser {
        private val fields = mutableListOf<String>()
        private val current = StringBuilder()
        private var inQuotes = false
        private var sawQuoteInQuotes = false
        private var started = false

        fun feed(chunk: CharSequence, onRecord: (List<String>) -> Unit) {
            for (char in chunk) {
                feedChar(char, onRecord)
            }
        }

        private fun feedChar(char: Char, onRecord: (List<String>) -> Unit) {
            started = true
            if (inQuotes) {
                when {
                    sawQuoteInQuotes && char == QUOTE -> {
                        current.append(QUOTE)
                        sawQuoteInQuotes = false
                    }
                    sawQuoteInQuotes -> {
                        // The quote closed the field; reprocess this character outside quotes.
                        inQuotes = false
                        sawQuoteInQuotes = false
                        feedChar(char, onRecord)
                    }
                    char == QUOTE -> sawQuoteInQuotes = true
                    else -> current.append(char)
                }
                return
            }

            when (char) {
                QUOTE -> inQuotes = true
                SEPARATOR -> endField()
                '\n' -> endRecord(onRecord)
                '\r' -> Unit // handled by the following '\n'; a lone '\r' is not a terminator here
                else -> current.append(char)
            }
        }

        fun finish(onRecord: (List<String>) -> Unit) {
            if (inQuotes && sawQuoteInQuotes) {
                inQuotes = false
                sawQuoteInQuotes = false
            }
            if (started && (current.isNotEmpty() || fields.isNotEmpty())) {
                endRecord(onRecord)
            }
            started = false
        }

        private fun endField() {
            fields += current.toString()
            current.setLength(0)
        }

        private fun endRecord(onRecord: (List<String>) -> Unit) {
            endField()
            onRecord(fields.toList())
            fields.clear()
        }
    }
}
