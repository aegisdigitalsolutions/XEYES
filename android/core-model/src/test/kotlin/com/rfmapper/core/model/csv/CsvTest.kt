package com.rfmapper.core.model.csv

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvTest {

    @Test
    fun `fields are quoted only when required`() {
        assertEquals("plain", Csv.encodeField("plain"))
        assertEquals("\"has,comma\"", Csv.encodeField("has,comma"))
        assertEquals("\"has\"\"quote\"", Csv.encodeField("has\"quote"))
        assertEquals("\"has\nnewline\"", Csv.encodeField("has\nnewline"))
        assertEquals("", Csv.encodeField(null))
    }

    @Test
    fun `a quoted field may contain a newline`() {
        // Splitting on newlines before parsing quotes would corrupt this row, which is why the
        // parser is a character-fed state machine.
        val text = "a,\"line1\nline2\",c\n"
        assertEquals(listOf(listOf("a", "line1\nline2", "c")), Csv.parse(text))
    }

    @Test
    fun `doubled quotes decode to a single quote`() {
        assertEquals(listOf(listOf("say \"hi\"")), Csv.parse("\"say \"\"hi\"\"\"\n"))
    }

    @Test
    fun `crlf line endings are handled`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\r\nc,d\r\n"))
    }

    @Test
    fun `a final record without a trailing newline is emitted`() {
        assertEquals(listOf(listOf("a", "b")), Csv.parse("a,b"))
    }

    @Test
    fun `empty fields are preserved positionally`() {
        assertEquals(listOf(listOf("a", "", "c", "")), Csv.parse("a,,c,\n"))
    }

    @Test
    fun `chunked feeding produces the same records as whole-text parsing`() {
        val text = "id,name\n1,\"O'Brien, A\"\n2,\"multi\nline\"\n"
        val expected = Csv.parse(text)

        for (chunkSize in 1..7) {
            val records = mutableListOf<List<String>>()
            val parser = Csv.RecordParser()
            text.chunked(chunkSize).forEach { chunk -> parser.feed(chunk) { records += it } }
            parser.finish { records += it }
            assertEquals(expected, records, "chunk size $chunkSize should not change the result")
        }
    }

    @Test
    fun `round trip of an awkward row`() {
        val fields = listOf("a,b", "c\"d", "e\nf", "", "plain")
        val encoded = Csv.encodeRow(fields)
        assertEquals(fields, Csv.parse("$encoded\n").single())
    }
}
