package com.rfmapper.core.importing

import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.export.Sha256
import com.rfmapper.core.model.ExportManifest
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.PackageType
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SchemaVersion
import com.rfmapper.core.model.csv.Csv
import com.rfmapper.core.model.csv.ObservationCsvCodec
import kotlinx.serialization.builtins.ListSerializer

/**
 * Validates an observation package without writing anything.
 *
 * Checks run cheapest-and-most-decisive first, so a bad package fails before 18,000 rows are
 * parsed. Nothing in here trusts a declared value: the manifest's counts, the CSV's row checksums
 * and the JSON's contents are all cross-checked against each other.
 */
class PackageValidator(
    private val observerRegistry: ObserverRegistry = ObserverRegistry { true },
    /** Tolerance for a row timestamp ahead of the package's creation time, for minor clock skew. */
    private val futureToleranceMillis: Long = 5 * 60 * 1000L,
) {

    fun validate(reader: PackageReader, existing: ExistingIdLookup): ImportPreview {
        val issues = mutableListOf<ImportIssue>()

        // The digest of the file itself, established before anything is inspected. Every outcome
        // reports it, including a rejection: "which file was this?" is exactly the question an
        // administrator asks about a package that failed, and it is also what lets the Master
        // recognise the same file on a later attempt.
        val packageSha = reader.packageSha256()

        // 1. Structure.
        val entryNames = runCatching { reader.entryNames() }.getOrElse {
            return failed(
                issues + ImportIssue(ImportErrorCode.MALFORMED_PACKAGE, it.message ?: "unreadable"),
                packageSha256 = packageSha,
            )
        }
        val missing = ExportPackage.REQUIRED_ENTRIES.filter { it !in entryNames }
        if (missing.isNotEmpty()) {
            return failed(
                issues + ImportIssue(
                    ImportErrorCode.MISSING_ENTRY,
                    "package is missing required ${missing.joinToString()}",
                ),
                packageSha256 = packageSha,
            )
        }

        // 2 & 3. Manifest, schema version and package type.
        val manifestBytes = reader.bytes(ExportPackage.MANIFEST)!!
        val manifest = runCatching {
            RfMapperJson.compact.decodeFromString(
                ExportManifest.serializer(),
                manifestBytes.toString(Charsets.UTF_8),
            )
        }.getOrElse {
            return failed(
                issues + ImportIssue(
                    ImportErrorCode.MALFORMED_PACKAGE,
                    "manifest.json is not valid: ${it.message}",
                ),
                packageSha256 = packageSha,
            )
        }

        if (!SchemaVersion.isReadable(manifest.schemaVersion)) {
            return failed(
                issues + ImportIssue(
                    ImportErrorCode.UNREADABLE_SCHEMA_VERSION,
                    "package schema_version ${manifest.schemaVersion} is not readable by this build " +
                        "(supports major ${SchemaVersion.SUPPORTED_MAJOR})",
                ),
                manifest,
                packageSha256 = packageSha,
            )
        }
        if (manifest.packageType != PackageType.OBSERVATIONS) {
            return failed(
                issues + ImportIssue(
                    ImportErrorCode.WRONG_PACKAGE_TYPE,
                    "expected an OBSERVATIONS package, got ${manifest.packageType}",
                ),
                manifest,
                packageSha256 = packageSha,
            )
        }

        // 4. Integrity of every entry.
        val declaredChecksums = Sha256.parseChecksumFile(
            reader.bytes(ExportPackage.CHECKSUM)!!.toString(Charsets.UTF_8),
        )
        for (entry in entryNames.filter { it != ExportPackage.CHECKSUM }) {
            val expected = declaredChecksums[entry]
            if (expected == null) {
                issues += ImportIssue(
                    ImportErrorCode.CHECKSUM_MISMATCH,
                    "checksum.txt does not cover '$entry'",
                )
                continue
            }
            val actual = Sha256.hex(reader.bytes(entry)!!)
            if (actual != expected) {
                issues += ImportIssue(
                    ImportErrorCode.CHECKSUM_MISMATCH,
                    "'$entry' digest $actual does not match the declared $expected",
                )
            }
        }
        if (issues.any { it.blocking }) return failed(issues, manifest, packageSha256 = packageSha)

        // 6 & 7. Observer identity and enrollment.
        val observer = runCatching {
            RfMapperJson.compact.decodeFromString(
                ObserverIdentity.serializer(),
                reader.bytes(ExportPackage.OBSERVER)!!.toString(Charsets.UTF_8),
            )
        }.getOrElse {
            return failed(
                issues + ImportIssue(
                    ImportErrorCode.MALFORMED_PACKAGE,
                    "observer.json is not valid: ${it.message}",
                ),
                manifest,
                packageSha256 = packageSha,
            )
        }
        if (observer.observerId != manifest.observerId) {
            issues += ImportIssue(
                ImportErrorCode.OBSERVER_MISMATCH,
                "manifest declares observer ${manifest.observerId} but observer.json says ${observer.observerId}",
            )
        }
        if (!observerRegistry.isEnrolled(manifest.observerId)) {
            issues += ImportIssue(
                ImportErrorCode.UNKNOWN_OBSERVER,
                "observer ${manifest.observerId} is not enrolled: enroll it before importing its data",
            )
        }
        if (issues.any { it.blocking }) return failed(issues, manifest, observer, packageSha)

        // 8 & 9. Rows.
        val csvOutcome = parseCsv(reader.bytes(ExportPackage.OBSERVATIONS_CSV)!!, manifest, issues)
        if (issues.any { it.blocking }) return failed(issues, manifest, observer, packageSha)

        // 13. The canonical JSON must agree with the inspectable CSV.
        //
        // Compared against the rows that *decoded*, in order, rather than the rows that passed every
        // check. A row rejected for, say, a timestamp outside the declared range is still present in
        // both files, so comparing against the accepted set would raise a spurious file-level
        // mismatch on top of the row error already reported. When some rows failed to decode at all
        // they have no id to compare, so the check weakens to a subsequence test.
        val jsonIds = parseJsonIds(reader.bytes(ExportPackage.OBSERVATIONS_JSON)!!, issues)
        if (jsonIds != null) {
            val csvIds = csvOutcome.decodedIds
            val agrees = if (csvOutcome.undecodableRows == 0) {
                jsonIds == csvIds
            } else {
                isSubsequence(csvIds, jsonIds)
            }
            if (!agrees) {
                issues += ImportIssue(
                    ImportErrorCode.CSV_JSON_MISMATCH,
                    "observations.csv and observations.json do not contain the same rows in the same order",
                )
            }
        }

        // 10. Declared counts must match the content.
        if (manifest.observationCount != csvOutcome.totalRows.toLong()) {
            issues += ImportIssue(
                ImportErrorCode.MANIFEST_COUNT_MISMATCH,
                "manifest declares ${manifest.observationCount} observations but the CSV holds " +
                    "${csvOutcome.totalRows}",
            )
        }
        if (manifest.countsBySensorType.isNotEmpty() &&
            !sensorCountsAgree(manifest.countsBySensorType, csvOutcome)
        ) {
            issues += ImportIssue(
                ImportErrorCode.MANIFEST_COUNT_MISMATCH,
                "manifest counts_by_sensor_type ${manifest.countsBySensorType} does not match " +
                    "the content ${csvOutcome.decodedSensorCounts}",
            )
        }
        if (issues.any { it.blocking }) return failed(issues, manifest, observer, packageSha)

        // 15. Deduplicate against what the Master already holds.
        val candidateIds = csvOutcome.observations.map { it.observationId }.toSet()
        val alreadyPresent = existing.existing(candidateIds)
        val newObservations = csvOutcome.observations.filter { it.observationId !in alreadyPresent }

        if (packageSha != null && candidateIds.isNotEmpty() && alreadyPresent.size == candidateIds.size) {
            issues += ImportIssue(
                ImportErrorCode.ALREADY_IMPORTED,
                "every observation in this package is already present; importing again is a no-op",
            )
        }

        return ImportPreview(
            manifest = manifest,
            observer = observer,
            packageSha256 = packageSha,
            totalRows = csvOutcome.totalRows,
            newObservations = newObservations,
            duplicateIds = alreadyPresent.toList().sorted(),
            issues = issues,
        )
    }

    private class CsvOutcome(
        /** Rows that passed every check and may be inserted. */
        val observations: List<Observation>,
        val totalRows: Int,
        /** Sensor counts over every row that decoded, valid or not, for the manifest comparison. */
        val decodedSensorCounts: Map<String, Long>,
        /** Ids of every row that decoded, in file order, for the CSV/JSON comparison. */
        val decodedIds: List<String>,
        val undecodableRows: Int,
    )

    /**
     * Compares the manifest's per-sensor counts with the CSV content.
     *
     * A row whose cells cannot be decoded has no sensor type to count, so an exact comparison would
     * raise a package-level mismatch on top of the row error already reported — and a blocking one,
     * costing a day of field work over a single corrupt cell. The counts therefore have to *account*
     * for the content rather than equal it: no sensor type may appear more often in the file than
     * the manifest promised, and the shortfall may be no larger than the number of rows that failed
     * to decode. With every row decodable this reduces to equality.
     */
    private fun sensorCountsAgree(declared: Map<String, Long>, csv: CsvOutcome): Boolean {
        if (csv.decodedSensorCounts.keys.any { it !in declared }) return false
        var shortfall = 0L
        for ((sensorType, declaredCount) in declared) {
            val found = csv.decodedSensorCounts[sensorType] ?: 0L
            if (found > declaredCount) return false
            shortfall += declaredCount - found
        }
        return shortfall == csv.undecodableRows.toLong()
    }

    /** True when [candidate] appears in [sequence] in order, allowing gaps. */
    private fun isSubsequence(candidate: List<String>, sequence: List<String>): Boolean {
        var cursor = 0
        for (element in sequence) {
            if (cursor < candidate.size && candidate[cursor] == element) cursor++
        }
        return cursor == candidate.size
    }

    private fun parseCsv(
        bytes: ByteArray,
        manifest: ExportManifest,
        issues: MutableList<ImportIssue>,
    ): CsvOutcome {
        val records = Csv.parse(bytes.toString(Charsets.UTF_8))
        if (records.isEmpty()) {
            issues += ImportIssue(ImportErrorCode.CSV_HEADER_MISMATCH, "observations.csv is empty")
            return CsvOutcome(emptyList(), 0, emptyMap(), emptyList(), 0)
        }

        val header = records.first()
        val missingColumns = ObservationCsvCodec.COLUMNS.filter { it !in header }
        if (missingColumns.isNotEmpty()) {
            issues += ImportIssue(
                ImportErrorCode.CSV_HEADER_MISMATCH,
                "observations.csv is missing ${missingColumns.joinToString()}",
            )
            return CsvOutcome(emptyList(), records.size - 1, emptyMap(), emptyList(), records.size - 1)
        }

        val createdAtMillis = Iso8601.parseToEpochMillis(manifest.createdAt) ?: Long.MAX_VALUE
        val rangeStart = manifest.dateRange?.from?.let(Iso8601::parseToEpochMillis)
        val rangeEnd = manifest.dateRange?.to?.let(Iso8601::parseToEpochMillis)
        val surveySessionIds = mutableSetOf<String>()

        val accepted = ArrayList<Observation>(records.size - 1)
        val seenIds = HashSet<String>(records.size)
        val decodedSensorCounts = LinkedHashMap<String, Long>()
        val decodedIds = ArrayList<String>(records.size)
        var undecodableRows = 0

        records.drop(1).forEachIndexed { index, record ->
            val rowNumber = index + 2 // 1-based, counting the header
            when (val decoded = ObservationCsvCodec.decode(header, record)) {
                is ObservationCsvCodec.DecodeResult.Failure -> {
                    issues += ImportIssue(
                        ImportErrorCode.INVALID_ROW,
                        decoded.reasons.joinToString("; "),
                        rowNumber = rowNumber,
                    )
                    undecodableRows++
                }
                is ObservationCsvCodec.DecodeResult.Success -> {
                    val observation = decoded.observation
                    decodedSensorCounts.merge(observation.sensorType.name, 1L, Long::plus)
                    decodedIds += observation.observationId
                    var rowValid = true

                    if (!decoded.checksumMatched) {
                        issues += ImportIssue(
                            ImportErrorCode.ROW_CHECKSUM_MISMATCH,
                            "row_checksum does not match the row contents; the file may have been edited",
                            rowNumber = rowNumber,
                            observationId = observation.observationId,
                        )
                        rowValid = false
                    }

                    // 11. Every row must belong to the package's observer.
                    if (observation.observerId != manifest.observerId) {
                        issues += ImportIssue(
                            ImportErrorCode.ROW_OBSERVER_MISMATCH,
                            "row observer ${observation.observerId} does not match the package's " +
                                "${manifest.observerId}",
                            rowNumber = rowNumber,
                            observationId = observation.observationId,
                        )
                        rowValid = false
                    }

                    // 12. Timestamps must be inside the declared range and not implausibly future.
                    val timestamp = observation.timestampEpochMillis
                    val beforeRange = rangeStart != null && timestamp < rangeStart
                    val afterRange = rangeEnd != null && timestamp > rangeEnd
                    val inFuture = timestamp > createdAtMillis + futureToleranceMillis
                    if (beforeRange || afterRange || inFuture) {
                        issues += ImportIssue(
                            ImportErrorCode.TIMESTAMP_OUT_OF_RANGE,
                            "timestamp ${observation.timestampUtc} lies outside the package's declared range",
                            rowNumber = rowNumber,
                            observationId = observation.observationId,
                        )
                        rowValid = false
                    }

                    // 14. The import-side half of the ground-truth safeguard: a row may not claim
                    // calibration status without a survey session to back it.
                    if (observation.sampleKind == SampleKind.GROUND_TRUTH) {
                        val sessionId = observation.metadata[MetadataKeys.SURVEY_SESSION_ID]
                        val pointId = observation.metadata[MetadataKeys.SURVEY_POINT_ID]
                        if (sessionId.isNullOrBlank() || pointId.isNullOrBlank()) {
                            issues += ImportIssue(
                                ImportErrorCode.INVALID_GROUND_TRUTH_CLAIM,
                                "a GROUND_TRUTH row requires both survey_session_id and survey_point_id",
                                rowNumber = rowNumber,
                                observationId = observation.observationId,
                            )
                            rowValid = false
                        } else {
                            surveySessionIds += sessionId
                        }
                    }

                    if (!seenIds.add(observation.observationId)) {
                        issues += ImportIssue(
                            ImportErrorCode.INVALID_ROW,
                            "observation_id ${observation.observationId} appears more than once in this package",
                            rowNumber = rowNumber,
                            observationId = observation.observationId,
                        )
                        rowValid = false
                    }

                    if (rowValid) accepted += observation
                }
            }
        }

        return CsvOutcome(accepted, records.size - 1, decodedSensorCounts, decodedIds, undecodableRows)
    }

    private fun parseJsonIds(bytes: ByteArray, issues: MutableList<ImportIssue>): List<String>? =
        runCatching {
            RfMapperJson.compact
                .decodeFromString(ListSerializer(Observation.serializer()), bytes.toString(Charsets.UTF_8))
                .map { it.observationId }
        }.getOrElse {
            issues += ImportIssue(
                ImportErrorCode.MALFORMED_PACKAGE,
                "observations.json is not valid: ${it.message}",
            )
            null
        }

    private fun failed(
        issues: List<ImportIssue>,
        manifest: ExportManifest? = null,
        observer: ObserverIdentity? = null,
        packageSha256: String? = null,
    ) = ImportPreview(
        manifest = manifest,
        observer = observer,
        packageSha256 = packageSha256,
        totalRows = 0,
        newObservations = emptyList(),
        duplicateIds = emptyList(),
        issues = issues,
    )
}
