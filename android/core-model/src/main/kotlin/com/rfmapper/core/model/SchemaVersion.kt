package com.rfmapper.core.model

/**
 * The single source of truth for the observation-contract version.
 *
 * Compatibility follows semantic versioning, as specified in `docs/02-observation-schema.md`:
 *  - patch: documentation or a new reserved metadata key. Always readable.
 *  - minor: a new optional top-level field. Readable; unknown fields must be preserved.
 *  - major: a required field or a field's meaning changed. Consumers must reject unknown majors
 *    with a clear error rather than guessing.
 */
object SchemaVersion {

    const val CURRENT: String = "1.0.0"

    const val SUPPORTED_MAJOR: Int = 1

    /** Highest minor this build understands. Higher minors are readable but may carry extra fields. */
    const val SUPPORTED_MINOR: Int = 0

    private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    data class Parsed(val major: Int, val minor: Int, val patch: Int)

    fun parse(version: String): Parsed? {
        val match = PATTERN.matchEntire(version) ?: return null
        return Parsed(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
        )
    }

    /** True when this build can safely interpret [version]. */
    fun isReadable(version: String): Boolean {
        val parsed = parse(version) ?: return false
        return parsed.major == SUPPORTED_MAJOR
    }

    /**
     * True when [version] may contain fields this build does not know about. Such packages are
     * still readable, but a consumer that re-serializes them must preserve the unknown fields.
     */
    fun mayContainUnknownFields(version: String): Boolean {
        val parsed = parse(version) ?: return false
        return parsed.major == SUPPORTED_MAJOR && parsed.minor > SUPPORTED_MINOR
    }
}
