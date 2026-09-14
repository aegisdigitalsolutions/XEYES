package com.rfmapper.data.room

import com.rfmapper.core.export.Sha256
import com.rfmapper.core.model.DerivedManifest
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MovementEstimate
import com.rfmapper.core.model.PackageType
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.core.model.QualityFlag
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SchemaVersion
import com.rfmapper.core.model.ZoneTransition
import com.rfmapper.data.room.derived.DerivedDao
import com.rfmapper.data.room.derived.DerivedGenerationEntity
import com.rfmapper.data.room.derived.MovementEstimateEntity
import com.rfmapper.data.room.derived.PositionEstimateEntity
import com.rfmapper.data.room.derived.QualityFlagEntity
import com.rfmapper.data.room.derived.ZoneTransitionEntity
import com.rfmapper.data.room.raw.ImportBatchDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports a `DERIVED_<date>.zip` produced by the Positioning Lab.
 *
 * Two rules define this class, both from `docs/13-derived-output-schema.md` §7. Every
 * `source_dataset_ids` entry must correspond to an import batch the Master already holds, which is
 * what keeps a derived estimate traceable back to raw evidence rather than to a file somebody
 * produced. And a generation is never overwritten: reprocessing inserts a new `algorithm_version`
 * alongside the old one, so last month's map can still be reproduced exactly.
 */
class DerivedPackageImporter(
    private val derived: DerivedDao,
    private val batches: ImportBatchDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    enum class Problem {
        UNREADABLE_ZIP,
        MISSING_MANIFEST,
        WRONG_PACKAGE_TYPE,
        UNSUPPORTED_SCHEMA_VERSION,
        CHECKSUM_MISSING,
        CHECKSUM_MISMATCH,
        UNKNOWN_SOURCE_DATASET,
        MALFORMED_RECORD,
        INVARIANT_VIOLATION,
        COUNT_MISMATCH,
        ALREADY_IMPORTED,
        ;

        /** Whether this problem stops the import, as opposed to being reported alongside it. */
        val blocking: Boolean
            get() = this != COUNT_MISMATCH && this != ALREADY_IMPORTED
    }

    data class Issue(val problem: Problem, val message: String)

    data class Preview(
        val manifest: DerivedManifest?,
        val packageSha256: String,
        val estimates: List<PositionEstimate>,
        val transitions: List<ZoneTransition>,
        val movements: List<MovementEstimate>,
        val flags: List<QualityFlag>,
        val issues: List<Issue>,
        val existingGeneration: DerivedGenerationEntity?,
    ) {
        val canImport: Boolean get() = manifest != null && issues.none { it.problem.blocking }
        val blockingIssues: List<Issue> get() = issues.filter { it.problem.blocking }

        /**
         * How many estimates claim ranged precision. Surfaced separately because tier 4 is the one
         * claim an administrator should always look at twice.
         */
        val precisionRangeCount: Int
            get() = estimates.count { it.precisionTier == PrecisionTier.PRECISION_RANGE }
    }

    suspend fun preview(stream: InputStream): Preview = withContext(Dispatchers.IO) {
        val bytes = stream.use { it.readBytes() }
        val sha = Sha256.hex(bytes)

        val entries = runCatching { readEntries(bytes) }.getOrElse {
            return@withContext empty(sha, Issue(Problem.UNREADABLE_ZIP, it.message ?: "unreadable zip"))
        }

        val manifestBytes = entries[MANIFEST]
            ?: return@withContext empty(sha, Issue(Problem.MISSING_MANIFEST, "$MANIFEST is absent"))

        val manifest = runCatching {
            RfMapperJson.compact.decodeFromString(DerivedManifest.serializer(), manifestBytes.decodeToString())
        }.getOrElse {
            return@withContext empty(sha, Issue(Problem.MISSING_MANIFEST, "$MANIFEST is unreadable: ${it.message}"))
        }

        val issues = mutableListOf<Issue>()
        if (manifest.packageType != PackageType.DERIVED) {
            issues += Issue(
                Problem.WRONG_PACKAGE_TYPE,
                "package_type is ${manifest.packageType}, expected DERIVED",
            )
        }
        if (!SchemaVersion.isReadable(manifest.schemaVersion)) {
            issues += Issue(
                Problem.UNSUPPORTED_SCHEMA_VERSION,
                "schema_version ${manifest.schemaVersion} is not readable by this build",
            )
        }
        issues += verifyChecksums(entries)

        val estimates = decodeList(entries, ESTIMATES, PositionEstimate.serializer(), issues)
        val transitions = decodeList(entries, TRANSITIONS, ZoneTransition.serializer(), issues)
        val movements = decodeList(entries, MOVEMENTS, MovementEstimate.serializer(), issues)
        val flags = decodeQualityFlags(entries, issues)

        issues += verifyTraceability(manifest)
        issues += verifyCounts(manifest, estimates, transitions, movements, flags)
        issues += verifyRangedEvidence(estimates)

        val existing = derived.generation(manifest.algorithmVersion)
        if (existing != null) {
            issues += Issue(
                Problem.ALREADY_IMPORTED,
                "algorithm_version ${manifest.algorithmVersion} is already present; re-importing " +
                    "is idempotent on record id",
            )
        }

        Preview(
            manifest = manifest,
            packageSha256 = sha,
            estimates = estimates,
            transitions = transitions,
            movements = movements,
            flags = flags,
            issues = issues,
            existingGeneration = existing,
        )
    }

    data class Result(
        val algorithmVersion: String,
        val estimates: Int,
        val transitions: Int,
        val movements: Int,
        val flags: Int,
    )

    /**
     * Writes one generation atomically.
     *
     * [makeActive] defaults to false: which generation the UI displays is an administrator's
     * choice, and a newly arrived algorithm version silently becoming the visible truth is exactly
     * the kind of change that should require a decision.
     */
    suspend fun commit(preview: Preview, makeActive: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val manifest = requireNotNull(preview.manifest) { "cannot commit a package without a manifest" }
            require(preview.canImport) {
                "refusing to commit a derived package with blocking issues: " +
                    preview.blockingIssues.joinToString { "${it.problem}: ${it.message}" }
            }

            val generation = DerivedGenerationEntity(
                algorithmVersion = manifest.algorithmVersion,
                exportId = manifest.exportId,
                importedAtUtc = Iso8601.format(nowMillis()),
                createdAtUtc = manifest.createdAt,
                engineVersions = RfMapperJson.compact.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    manifest.engineVersions.toSortedMap(),
                ),
                datasetKind = manifest.datasetKind.name,
                sourceDatasetIds = manifest.sourceDatasetIds,
                estimateCount = preview.estimates.size.toLong(),
                transitionCount = preview.transitions.size.toLong(),
                movementCount = preview.movements.size.toLong(),
                isActive = false,
            )

            derived.importGeneration(
                generation = generation,
                estimates = preview.estimates.map(PositionEstimateEntity::from),
                transitions = preview.transitions.map(ZoneTransitionEntity::from),
                movements = preview.movements.map(MovementEstimateEntity::from),
                flags = preview.flags.map(QualityFlagEntity::from),
            )
            if (makeActive) derived.setActiveGeneration(manifest.algorithmVersion)

            Result(
                algorithmVersion = manifest.algorithmVersion,
                estimates = preview.estimates.size,
                transitions = preview.transitions.size,
                movements = preview.movements.size,
                flags = preview.flags.size,
            )
        }

    // -- validation -------------------------------------------------------------------------------

    /**
     * A derived package that cites data the Master has never imported is refused.
     *
     * Without this check the derived layer would be assertions rather than conclusions: nothing
     * would connect an estimate on the map to the raw rows that justify it.
     */
    private suspend fun verifyTraceability(manifest: DerivedManifest): List<Issue> {
        if (manifest.sourceDatasetIds.isEmpty()) {
            return listOf(
                Issue(
                    Problem.UNKNOWN_SOURCE_DATASET,
                    "the package cites no source dataset, so its estimates cannot be traced to raw evidence",
                ),
            )
        }
        val known = batches.committed()
        val knownIds = buildSet {
            known.forEach {
                add(it.importBatchId)
                add(it.packageName)
                add(it.packageName.removeSuffix(".zip"))
            }
        }
        return manifest.sourceDatasetIds.filterNot { it in knownIds }.map {
            Issue(Problem.UNKNOWN_SOURCE_DATASET, "source dataset '$it' has never been imported")
        }
    }

    private fun verifyCounts(
        manifest: DerivedManifest,
        estimates: List<PositionEstimate>,
        transitions: List<ZoneTransition>,
        movements: List<MovementEstimate>,
        flags: List<QualityFlag>,
    ): List<Issue> {
        val actual = mapOf(
            "position_estimates" to estimates.size.toLong(),
            "zone_transitions" to transitions.size.toLong(),
            "movement_estimates" to movements.size.toLong(),
            "quality_flags" to flags.size.toLong(),
        )
        return manifest.counts.mapNotNull { (key, declared) ->
            val found = actual[key] ?: return@mapNotNull null
            if (found == declared) {
                null
            } else {
                Issue(Problem.COUNT_MISMATCH, "manifest declares $declared $key but the package carries $found")
            }
        }
    }

    /**
     * Tier 4 requires genuine ranging evidence, and the Master re-checks it rather than trusting
     * the producer. The [PositionEstimate] constructor cannot verify this on its own — it sees only
     * observation ids, not their sensor types — so the claim is only as good as whoever validates
     * it last.
     */
    private fun verifyRangedEvidence(estimates: List<PositionEstimate>): List<Issue> =
        estimates.filter { it.precisionTier == PrecisionTier.PRECISION_RANGE }
            .filter { it.method.isBlank() || it.horizontalUncertaintyM == null }
            .map {
                Issue(
                    Problem.INVARIANT_VIOLATION,
                    "estimate ${it.estimateId} claims PRECISION_RANGE without a method and uncertainty",
                )
            }

    private fun verifyChecksums(entries: Map<String, ByteArray>): List<Issue> {
        val checksums = entries[CHECKSUMS]
            ?: return listOf(Issue(Problem.CHECKSUM_MISSING, "$CHECKSUMS is absent"))

        val declared = checksums.decodeToString().lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[1].trim() to parts[0].lowercase() else null
            }
            .toMap()

        if (declared.isEmpty()) {
            return listOf(Issue(Problem.CHECKSUM_MISSING, "$CHECKSUMS lists no files"))
        }

        return declared.mapNotNull { (name, expected) ->
            val content = entries[name]
                ?: return@mapNotNull Issue(Problem.CHECKSUM_MISMATCH, "$CHECKSUMS lists missing file '$name'")
            if (Sha256.hex(content).equals(expected, ignoreCase = true)) {
                null
            } else {
                Issue(Problem.CHECKSUM_MISMATCH, "'$name' does not match its declared sha256")
            }
        }
    }

    // -- reading ----------------------------------------------------------------------------------

    private fun <T> decodeList(
        entries: Map<String, ByteArray>,
        name: String,
        serializer: KSerializer<T>,
        issues: MutableList<Issue>,
    ): List<T> {
        val bytes = entries[name] ?: return emptyList()
        return runCatching {
            RfMapperJson.compact.decodeFromString(ListSerializer(serializer), bytes.decodeToString())
        }.getOrElse {
            // An invariant failure surfaces here as a deserialization error, because the model's
            // constructor is what refuses a coordinate without uncertainty.
            val problem = if (it is IllegalArgumentException) {
                Problem.INVARIANT_VIOLATION
            } else {
                Problem.MALFORMED_RECORD
            }
            issues += Issue(problem, "$name could not be read: ${it.message}")
            emptyList()
        }
    }

    private fun decodeQualityFlags(
        entries: Map<String, ByteArray>,
        issues: MutableList<Issue>,
    ): List<QualityFlag> {
        val bytes = entries[QUALITY] ?: return emptyList()
        return runCatching {
            val root = RfMapperJson.compact.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            val array = root?.get("flags") ?: return emptyList()
            RfMapperJson.compact.decodeFromJsonElement(ListSerializer(QualityFlag.serializer()), array)
        }.getOrElse {
            issues += Issue(Problem.MALFORMED_RECORD, "$QUALITY could not be read: ${it.message}")
            emptyList()
        }
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun empty(sha: String, issue: Issue) = Preview(
        manifest = null,
        packageSha256 = sha,
        estimates = emptyList(),
        transitions = emptyList(),
        movements = emptyList(),
        flags = emptyList(),
        issues = listOf(issue),
        existingGeneration = null,
    )

    private companion object {
        const val MANIFEST = "manifest.json"
        const val ESTIMATES = "position_estimates.json"
        const val TRANSITIONS = "zone_transitions.json"
        const val MOVEMENTS = "movement_estimates.json"
        const val QUALITY = "quality_report.json"
        const val CHECKSUMS = "checksum.txt"
    }
}
