package com.rfmapper.core.importing

import com.rfmapper.core.model.ExportManifest
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity

/**
 * Failure codes from `docs/16-export-package-specification.md` §8.
 *
 * [blocking] distinguishes "this package cannot be trusted at all" from "this row is bad". A
 * blocking error stops the import; a row-level error marks one row invalid and the rest still
 * imports, because losing a whole day of field work over one malformed row would be worse than
 * importing 18,390 of 18,391 rows and reporting the one.
 */
enum class ImportErrorCode(val blocking: Boolean, val rejectsRow: Boolean = false) {
    MALFORMED_PACKAGE(true),
    MISSING_ENTRY(true),
    UNREADABLE_SCHEMA_VERSION(true),
    WRONG_PACKAGE_TYPE(true),
    CHECKSUM_MISMATCH(true),
    OBSERVER_MISMATCH(true),

    /** The Master must not accept data of unverified provenance into its immutable raw layer. */
    UNKNOWN_OBSERVER(true),
    CSV_HEADER_MISMATCH(true),
    MANIFEST_COUNT_MISMATCH(true),
    CSV_JSON_MISMATCH(true),

    /** Already imported byte-for-byte. Not fatal: deduplication makes a repeat import harmless. */
    ALREADY_IMPORTED(false),

    INVALID_ROW(blocking = false, rejectsRow = true),
    ROW_CHECKSUM_MISMATCH(blocking = false, rejectsRow = true),
    ROW_OBSERVER_MISMATCH(blocking = false, rejectsRow = true),
    TIMESTAMP_OUT_OF_RANGE(blocking = false, rejectsRow = true),

    /** A row claims ground truth without a survey session to back it. */
    INVALID_GROUND_TRUTH_CLAIM(blocking = false, rejectsRow = true),

    /** Advisory only: the row is stored, the disagreement is surfaced to the administrator. */
    ATTRIBUTION_DISAGREEMENT(false),
}

data class ImportIssue(
    val code: ImportErrorCode,
    val message: String,
    /** 1-based line number in `observations.csv`, header included, so it matches a text editor. */
    val rowNumber: Int? = null,
    val observationId: String? = null,
) {
    val blocking: Boolean get() = code.blocking
}

/**
 * The result of validating a package without writing anything. This is what backs the preview the
 * administrator confirms:
 *
 * ```
 * Observer: OBS-04
 * Date: 2026-09-14
 * Observations: 18,391
 * Duplicates: 291
 * New: 18,100
 * Invalid: 0
 * ```
 */
data class ImportPreview(
    val manifest: ExportManifest?,
    val observer: ObserverIdentity?,
    val packageSha256: String?,
    val totalRows: Int,
    val newObservations: List<Observation>,
    val duplicateIds: List<String>,
    val issues: List<ImportIssue>,
) {
    val newCount: Int get() = newObservations.size
    val duplicateCount: Int get() = duplicateIds.size

    /**
     * Rows that will not be inserted because something about them is wrong. Counted as distinct
     * rows, not as issues: one row can fail several checks at once and must still be one line in
     * the preview.
     */
    val invalidCount: Int
        get() = issues
            .filter { it.code.rejectsRow }
            .map { it.rowNumber }
            .distinct()
            .size

    val blockingIssues: List<ImportIssue> get() = issues.filter { it.blocking }
    val canImport: Boolean get() = blockingIssues.isEmpty()

    /** The date shown in the preview: the package's declared range, else its first observation. */
    val dateStamp: String?
        get() = manifest?.dateRange?.from?.take(10) ?: manifest?.firstObservation?.take(10)
}

/** Whatever the Master already holds, so the planner can decide what is new without a race. */
fun interface ExistingIdLookup {
    /** Returns the subset of [candidateIds] already present in the raw layer. */
    fun existing(candidateIds: Set<String>): Set<String>
}

/** The enrolled observers the Master recognises. */
fun interface ObserverRegistry {
    fun isEnrolled(observerId: String): Boolean
}

/** Read access to a package's entries, so the engine never touches a filesystem directly. */
interface PackageReader {
    fun entryNames(): List<String>
    fun bytes(entryName: String): ByteArray?

    /** SHA-256 of the whole package file, when the caller knows it. */
    fun packageSha256(): String?
}
