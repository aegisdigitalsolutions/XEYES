package com.rfmapper.data.room

import com.rfmapper.core.importing.DeduplicationPlanner
import com.rfmapper.core.importing.ImportEngine
import com.rfmapper.core.importing.ImportEngineV1
import com.rfmapper.core.importing.ImportIssue
import com.rfmapper.core.importing.ImportPreview
import com.rfmapper.core.importing.ObserverRegistry
import com.rfmapper.core.importing.ZipPackageReader
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.data.room.raw.ImportBatchEntity
import com.rfmapper.data.room.raw.ImportBatchDao
import com.rfmapper.data.room.reference.ManagedDeviceDao
import com.rfmapper.data.room.reference.ObserverDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.util.UUID

/**
 * Imports an observation package into the Master's raw layer.
 *
 * Two phases, always, and never one: [preview] reads and validates without writing anything, and
 * [commit] writes only what a human has confirmed. The specification requires the administrator to
 * see counts before committing, and the separation also means a package that turns out to be
 * corrupt has touched nothing.
 *
 * @see <a href="../../../../../../../../docs/16-export-package-specification.md">docs/16</a>
 */
class PackageImporter(
    private val repository: ObservationRepository,
    private val observers: ObserverDao,
    private val devices: ManagedDeviceDao,
    private val batches: ImportBatchDao,
    private val engine: ImportEngine = ImportEngineV1(ObserverRegistry { true }),
    private val newBatchId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class PreviewResult(
        val preview: ImportPreview,
        val packageName: String,

        /** The batch that already carries this exact file, when there is one. */
        val previousBatch: ImportBatchEntity?,
    ) {
        val canImport: Boolean get() = preview.canImport
        val isRepeatOfCommittedPackage: Boolean
            get() = previousBatch?.status == ImportBatchEntity.Status.COMMITTED.name
    }

    /**
     * Validates a package and records the attempt.
     *
     * The observer registry is read from the database for this call rather than captured once,
     * because an administrator enrolling an observer and immediately retrying an import is the
     * obvious recovery from `UNKNOWN_OBSERVER`, and a stale registry would make that fail twice.
     */
    suspend fun preview(packageName: String, stream: InputStream): PreviewResult =
        withContext(Dispatchers.IO) {
            val reader = ZipPackageReader.from(stream)
            val enrolled = observers.enrolledIds().toSet()
            val validator = ImportEngineV1(ObserverRegistry { it in enrolled })
            val preview = validator.preview(reader, repository.existingIdLookup())

            val previous = reader.packageSha256()?.let { batches.byPackageSha256(it) }

            if (preview.manifest != null) {
                batches.upsert(
                    batchRow(
                        importBatchId = previous?.importBatchId ?: newBatchId(),
                        packageName = packageName,
                        sha256 = reader.packageSha256().orEmpty(),
                        preview = preview,
                        acceptedCount = 0,
                        status = if (preview.canImport) {
                            ImportBatchEntity.Status.PREVIEWED
                        } else {
                            ImportBatchEntity.Status.REJECTED
                        },
                    ),
                )
            }

            PreviewResult(preview, packageName, previous)
        }

    data class CommitResult(
        val importBatchId: String,
        val inserted: Int,
        val duplicates: Int,
        val invalid: Int,
        val attributed: Int,
    )

    /**
     * Writes the confirmed rows.
     *
     * Attribution runs here rather than at collection time: the Collector has no device registry,
     * and an identifier's enrolment can change between the day it was observed and the day it was
     * imported. Only an explicit registry match sets `target_device_id` — a randomised identifier
     * is never attributed, whatever it correlates with
     * (`docs/17-identity-and-attribution-policy.md`).
     */
    suspend fun commit(result: PreviewResult, operator: String?): CommitResult =
        withContext(Dispatchers.IO) {
            require(result.canImport) {
                "refusing to commit a package with blocking issues: " +
                    result.preview.blockingIssues.joinToString { "${it.code}: ${it.message}" }
            }
            val plan = DeduplicationPlanner.plan(result.preview)
            val sha256 = result.preview.packageSha256.orEmpty()
            val batchId = batches.byPackageSha256(sha256)?.importBatchId ?: newBatchId()

            val attributed = attribute(plan.toInsert)
            val outcome = repository.insertImported(attributed.observations, batchId)

            batches.upsert(
                batchRow(
                    importBatchId = batchId,
                    packageName = result.packageName,
                    sha256 = sha256,
                    preview = result.preview,
                    acceptedCount = outcome.inserted.toLong(),
                    status = ImportBatchEntity.Status.COMMITTED,
                    operator = operator,
                ),
            )

            for ((deviceId, lastSeen) in attributed.lastSeenByDevice) {
                devices.touchLastSeen(deviceId, lastSeen)
            }

            CommitResult(
                importBatchId = batchId,
                inserted = outcome.inserted,
                duplicates = outcome.duplicates + plan.duplicateCount,
                invalid = plan.invalidCount,
                attributed = attributed.attributedCount,
            )
        }

    private data class Attributed(
        val observations: List<Observation>,
        val attributedCount: Int,
        val lastSeenByDevice: Map<String, String>,
    )

    private suspend fun attribute(observations: List<Observation>): Attributed {
        if (observations.isEmpty()) return Attributed(observations, 0, emptyMap())

        // Only durable identifiers are looked up at all. An ephemeral one has no stable identity to
        // match against, so querying for it would be meaningless even before the policy forbids it.
        val lookupKeys = observations
            .filterNot { it.identifierType.isEphemeral }
            .map { it.radioIdentifier }
            .distinct()

        val byIdentifier = lookupKeys
            .chunked(ObservationRepository.SQLITE_PARAMETER_LIMIT)
            .flatMap { devices.attributionsFor(it) }
            .associateBy { it.identifier to it.identifierType }

        var attributedCount = 0
        val lastSeen = HashMap<String, String>()
        val resolved = observations.map { observation ->
            if (observation.identifierType.isEphemeral) return@map observation
            val match = byIdentifier[observation.radioIdentifier to observation.identifierType.name]
                ?: return@map observation

            attributedCount++
            lastSeen.merge(match.deviceId, observation.timestampUtc) { a, b -> maxOf(a, b) }
            observation.copy(targetDeviceId = match.deviceId)
        }

        return Attributed(resolved, attributedCount, lastSeen)
    }

    private fun batchRow(
        importBatchId: String,
        packageName: String,
        sha256: String,
        preview: ImportPreview,
        acceptedCount: Long,
        status: ImportBatchEntity.Status,
        operator: String? = null,
    ) = ImportBatchEntity(
        importBatchId = importBatchId,
        observerId = preview.manifest?.observerId ?: UNKNOWN_OBSERVER,
        packageName = packageName,
        packageSha256 = sha256,
        importedAtUtc = Iso8601.format(nowMillis()),
        schemaVersion = preview.manifest?.schemaVersion ?: "unknown",
        declaredCount = preview.manifest?.observationCount ?: 0,
        acceptedCount = acceptedCount,
        duplicateCount = preview.duplicateCount.toLong(),
        invalidCount = preview.invalidCount.toLong(),
        manifestJson = preview.manifest
            ?.let { RfMapperJson.compact.encodeToString(com.rfmapper.core.model.ExportManifest.serializer(), it) }
            .orEmpty(),
        issuesJson = encodeIssues(preview.issues),
        operator = operator,
        status = status.name,
    )

    /**
     * Issues as human-readable lines, capped.
     *
     * A package with ten thousand malformed rows produces ten thousand issues, and storing all of
     * them would bloat the batch row without telling an administrator anything the first hundred
     * did not. The stored text says how many were elided.
     */
    private fun encodeIssues(issues: List<ImportIssue>): String? {
        if (issues.isEmpty()) return null
        val lines = issues.take(MAX_STORED_ISSUES).map { issue ->
            buildString {
                append(issue.code.name)
                issue.rowNumber?.let { append(" row $it") }
                append(": ")
                append(issue.message)
            }
        }
        val elided = issues.size - lines.size
        val all = if (elided > 0) lines + "... and $elided more" else lines
        return RfMapperJson.compact.encodeToString(ListSerializer(String.serializer()), all)
    }

    private companion object {
        const val UNKNOWN_OBSERVER = "UNKNOWN"
        const val MAX_STORED_ISSUES = 100
    }
}
