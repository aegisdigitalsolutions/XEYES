package com.rfmapper.data.room.contract

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Locates and compares the cross-language fixtures under `contract/` at the repository root.
 *
 * The three components never run in one process — the Collector is an Android app, the Master is
 * another, the Lab is a Python program — so nothing about their file contracts can be checked by
 * calling one from the other. What can be checked is the artefact: one side writes a file, the file
 * is committed, and the other side's test suite reads it. A change to either encoder that the other
 * decoder cannot follow then fails a test in this repository instead of on the night the pipeline
 * first runs against real data.
 *
 * Fixtures written by Kotlin are golden files: the test regenerates them and compares. Run with
 * `-Drfmapper.contract.write=true` to accept a deliberate change, then read the diff before
 * committing it — a change here is a change to a published contract.
 */
object ContractFixtures {

    private const val WRITE_PROPERTY = "rfmapper.contract.write"

    val regenerating: Boolean get() = System.getProperty(WRITE_PROPERTY)?.toBoolean() == true

    /** `contract/`, found by walking up for the repository marker rather than by relative guess. */
    val root: File by lazy {
        val start = File("").absoluteFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "docs/00-first-deliverables-index.md").isFile }
            ?.let { File(it, "contract") }
            ?: error("could not locate the repository root from $start")
    }

    fun file(relative: String): File = File(root, relative)

    /**
     * Compares [actual] against the committed fixture, or rewrites it when regenerating.
     *
     * The failure message names the property to set rather than only reporting a byte difference,
     * because the useful question when this fails is "did I mean to change the format?" and the
     * answer has to be a deliberate act.
     */
    fun golden(relative: String, actual: ByteArray) {
        val target = file(relative)
        if (regenerating) {
            target.parentFile?.mkdirs()
            target.writeBytes(actual)
            return
        }
        if (!target.isFile) {
            error("contract fixture $relative is missing; regenerate with -D$WRITE_PROPERTY=true")
        }
        val expected = target.readBytes()
        if (expected.contentEquals(actual)) return

        throw AssertionError(
            buildString {
                append("contract fixture $relative no longer matches what this build produces.\n")
                append(describeDifference(expected, actual))
                append(
                    "\nThe Positioning Lab reads this file. If the change is intended, regenerate " +
                        "with -D$WRITE_PROPERTY=true, check the diff, and make sure the Lab's " +
                        "reader handles it.",
                )
            },
        )
    }

    /**
     * Compares a zip fixture by its entry names, their order and their decompressed contents.
     *
     * Deliberately not a byte comparison of the archive. Compressed output is a property of
     * whichever zlib the toolchain happens to bundle, so pinning archive bytes would turn a JDK or
     * CPython upgrade into a failing contract test while nothing about the contract had moved. What
     * the other side actually reads is the entry list and the payloads, and those are pinned
     * exactly.
     */
    fun goldenArchive(relative: String, actual: ByteArray) {
        val target = file(relative)
        if (regenerating) {
            target.parentFile?.mkdirs()
            target.writeBytes(actual)
            return
        }
        if (!target.isFile) {
            error("contract fixture $relative is missing; regenerate with -D$WRITE_PROPERTY=true")
        }

        val produced = entriesOf(actual)
        val committed = entriesOf(target.readBytes())
        val hint = "\nThe Positioning Lab reads this package. If the change is intended, " +
            "regenerate with -D$WRITE_PROPERTY=true and check the diff."

        if (produced.keys.toList() != committed.keys.toList()) {
            throw AssertionError(
                "contract fixture $relative no longer has the same entries.\n" +
                    "  committed: ${committed.keys}\n" +
                    "  produced:  ${produced.keys}$hint",
            )
        }
        for ((name, payload) in produced) {
            val expected = committed.getValue(name)
            if (expected.contentEquals(payload)) continue
            throw AssertionError(
                "contract fixture $relative: entry '$name' has changed.\n" +
                    describeDifference(expected, payload) + hint,
            )
        }
    }

    private fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun describeDifference(expected: ByteArray, actual: ByteArray): String {
        val expectedText = expected.decodeToString()
        val actualText = actual.decodeToString()
        if (!expectedText.isProbablyText() || !actualText.isProbablyText()) {
            return "committed ${expected.size} bytes, produced ${actual.size} bytes"
        }
        val expectedLines = expectedText.lines()
        val actualLines = actualText.lines()
        val at = expectedLines.zip(actualLines).indexOfFirst { (a, b) -> a != b }
        return when {
            at >= 0 -> "first difference at line ${at + 1}:\n" +
                "  committed: ${expectedLines[at].take(160)}\n" +
                "  produced:  ${actualLines[at].take(160)}"

            else -> "identical for ${minOf(expectedLines.size, actualLines.size)} lines, then " +
                "committed has ${expectedLines.size} lines and produced has ${actualLines.size}"
        }
    }

    private fun String.isProbablyText(): Boolean = none { it == '\u0000' }
}
