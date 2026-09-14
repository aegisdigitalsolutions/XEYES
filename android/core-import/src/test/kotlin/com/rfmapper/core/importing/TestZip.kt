package com.rfmapper.core.importing

import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.export.Sha256
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zip surgery for negative tests: corrupting, stripping and re-signing package entries. */
object TestZip {

    private fun read(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                entries[entry.name] = stream.readBytes()
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }
        return entries
    }

    private fun write(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** Edits an entry *without* updating `checksum.txt`, simulating tampering or corruption. */
    fun replaceEntry(zip: ByteArray, name: String, transform: (String) -> String): ByteArray {
        val entries = read(zip)
        val original = requireNotNull(entries[name]) { "no entry '$name'" }
        entries[name] = transform(original.toString(Charsets.UTF_8)).toByteArray()
        return write(entries)
    }

    fun removeEntry(zip: ByteArray, name: String): ByteArray =
        write(read(zip).apply { remove(name) })

    /**
     * Appends extra rows to `observations.csv` and `observations.json` and updates the manifest
     * count, keeping the package internally consistent.
     *
     * A compliant exporter refuses to write a row from a foreign observer, so import-side checks for
     * that case can only be exercised by assembling the bytes directly. Import validation must hold
     * against arbitrary input, not only against input this codebase produced.
     */
    fun appendRows(zip: ByteArray, rows: List<com.rfmapper.core.model.Observation>): ByteArray =
        rechecksum(zip) { name, content ->
            when (name) {
                ExportPackage.OBSERVATIONS_CSV -> buildString {
                    append(content.trimEnd('\n'))
                    for (row in rows) {
                        append('\n')
                        append(com.rfmapper.core.model.csv.ObservationCsvCodec.encode(row))
                    }
                    append('\n')
                }
                ExportPackage.OBSERVATIONS_JSON -> {
                    val json = com.rfmapper.core.model.RfMapperJson.compact
                    val encoded = rows.joinToString(",\n") {
                        json.encodeToString(com.rfmapper.core.model.Observation.serializer(), it)
                    }
                    content.trimEnd('\n').removeSuffix("]").trimEnd('\n') + ",\n" + encoded + "\n]\n"
                }
                ExportPackage.MANIFEST -> {
                    val current = Regex("\"observation_count\": (\\d+)").find(content)!!.groupValues[1].toInt()
                    content.replace(
                        "\"observation_count\": $current",
                        "\"observation_count\": ${current + rows.size}",
                    )
                }
                else -> content
            }
        }

    /**
     * Edits entries and then regenerates `checksum.txt`, so the package is internally consistent.
     * Used to test validations that must catch a *well-formed* but wrong package, rather than
     * simply catching the checksum mismatch first.
     */
    fun rechecksum(zip: ByteArray, transform: (String, String) -> String): ByteArray {
        val entries = read(zip)
        val digests = LinkedHashMap<String, String>()
        for ((name, bytes) in entries.entries.toList()) {
            if (name == ExportPackage.CHECKSUM) continue
            val updated = transform(name, bytes.toString(Charsets.UTF_8)).toByteArray()
            entries[name] = updated
            digests[name] = Sha256.hex(updated)
        }
        entries[ExportPackage.CHECKSUM] = Sha256.checksumFile(digests).toByteArray()
        return write(entries)
    }
}
