package com.rfmapper.core.importing

import com.rfmapper.core.export.Sha256
import com.rfmapper.core.model.Observation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

interface ImportEngine {
    val version: String

    /** Validates a package and produces the preview the administrator confirms. Writes nothing. */
    fun preview(reader: PackageReader, existing: ExistingIdLookup): ImportPreview
}

class ImportEngineV1(
    observerRegistry: ObserverRegistry = ObserverRegistry { true },
) : ImportEngine {

    override val version: String = "import_engine-1.0.0"

    private val validator = PackageValidator(observerRegistry)

    override fun preview(reader: PackageReader, existing: ExistingIdLookup): ImportPreview =
        validator.validate(reader, existing)
}

/**
 * Reads a zip package from a stream into memory.
 *
 * Observation packages are a day of one observer's collection — tens of megabytes at most — so
 * holding one while validating is acceptable, and it lets every cross-check (checksums, CSV/JSON
 * agreement, manifest counts) run without re-opening the archive. If packages ever grow past that,
 * this is the class to make streaming, not the validator.
 */
class ZipPackageReader private constructor(
    private val entries: Map<String, ByteArray>,
    private val sha256: String?,
) : PackageReader {

    override fun entryNames(): List<String> = entries.keys.toList()

    override fun bytes(entryName: String): ByteArray? = entries[entryName]

    override fun packageSha256(): String? = sha256

    companion object {
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

        fun from(bytes: ByteArray): ZipPackageReader {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBounded()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return ZipPackageReader(entries, Sha256.hex(bytes))
        }

        fun from(stream: InputStream): ZipPackageReader = from(stream.readBytes())

        /** Guards against a zip whose declared size is small but whose content expands unboundedly. */
        private fun InputStream.readBounded(): ByteArray {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = read(chunk)
                if (read < 0) break
                total += read
                require(total <= MAX_ENTRY_BYTES) { "package entry exceeds $MAX_ENTRY_BYTES bytes" }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }
}

/**
 * Turns a validated preview into the exact set of rows to insert.
 *
 * Deduplication ultimately relies on `observation_id` being the storage primary key with
 * ignore-on-conflict, which is atomic and therefore immune to a check-then-write race. This planner
 * exists to produce accurate *counts* for the preview, not to be the safety mechanism.
 */
object DeduplicationPlanner {

    data class Plan(
        val toInsert: List<Observation>,
        val duplicateCount: Int,
        val invalidCount: Int,
    )

    fun plan(preview: ImportPreview): Plan {
        require(preview.canImport) {
            "cannot plan an import with blocking issues: " +
                preview.blockingIssues.joinToString { "${it.code}: ${it.message}" }
        }
        val invalidRows = preview.issues.count { it.rowNumber != null }
        return Plan(
            toInsert = preview.newObservations,
            duplicateCount = preview.duplicateCount,
            invalidCount = invalidRows,
        )
    }
}
