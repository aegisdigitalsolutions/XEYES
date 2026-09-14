package com.rfmapper.core.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where an export package's entries are written.
 *
 * Abstracted so that nothing in this module needs a filesystem path: the Android layer supplies a
 * sink backed by a Storage Access Framework document, and tests supply an in-memory one. That is
 * what allows the package format to be tested end to end on the JVM.
 */
interface ExportSink {

    /**
     * Opens a new entry. The caller closes the returned stream before opening the next one, because
     * a zip sink can only have one entry open at a time.
     */
    fun openEntry(name: String): OutputStream

    fun finish()
}

/**
 * A deterministic zip sink: fixed entry timestamps and a fixed compression level, so two exports of
 * identical data produce byte-identical archives. Without this, every re-export would differ and
 * `checksum.txt` would be the only way to tell a real change from a re-run.
 */
class ZipExportSink(target: OutputStream) : ExportSink {

    private val zip = ZipOutputStream(target).apply { setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION) }
    private var openEntryName: String? = null

    override fun openEntry(name: String): OutputStream {
        check(openEntryName == null) { "entry '$openEntryName' is still open" }
        openEntryName = name
        zip.putNextEntry(
            ZipEntry(name).apply {
                // A fixed timestamp keeps the archive reproducible.
                time = FIXED_ENTRY_TIME_MILLIS
            },
        )
        return object : OutputStream() {
            override fun write(byte: Int) = zip.write(byte)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = zip.write(bytes, offset, length)
            override fun flush() = zip.flush()
            override fun close() {
                zip.closeEntry()
                openEntryName = null
            }
        }
    }

    override fun finish() {
        check(openEntryName == null) { "entry '$openEntryName' is still open" }
        zip.finish()
        zip.flush()
    }

    private companion object {
        /** 1980-01-01T00:00:00Z, the earliest timestamp the zip format can represent. */
        const val FIXED_ENTRY_TIME_MILLIS = 315_532_800_000L
    }
}

/** Collects entries in memory. Used by tests and by integrity self-checks. */
class InMemoryExportSink : ExportSink {

    private val entries = LinkedHashMap<String, ByteArrayOutputStream>()
    private var finished = false

    override fun openEntry(name: String): OutputStream {
        require(name !in entries) { "duplicate entry '$name'" }
        val buffer = ByteArrayOutputStream()
        entries[name] = buffer
        return buffer
    }

    override fun finish() {
        finished = true
    }

    val isFinished: Boolean get() = finished

    val entryNames: List<String> get() = entries.keys.toList()

    fun bytes(name: String): ByteArray? = entries[name]?.toByteArray()

    fun text(name: String): String? = bytes(name)?.toString(Charsets.UTF_8)
}
