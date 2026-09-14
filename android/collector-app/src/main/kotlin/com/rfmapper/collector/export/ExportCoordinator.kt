package com.rfmapper.collector.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.rfmapper.collector.collection.CollectionCoordinator
import com.rfmapper.collector.settings.ObserverSettings
import com.rfmapper.core.export.ExportResult
import com.rfmapper.core.export.ZipExportSink
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.data.room.PackageExporter
import com.rfmapper.radio.android.AndroidRadioStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes an export package to a folder the operator chose through the Storage Access Framework.
 *
 * Export is deliberately manual and file-based. The specification forbids background sync: an
 * administrator must be able to point at a file and say what it contains, and a transfer that
 * happens invisibly cannot be audited (`docs/16-export-package-specification.md`).
 *
 * Two safeguards surround the write:
 *
 *  - **Pending writes are flushed first.** Otherwise the last few seconds of a session sit in the
 *    engine's buffer and the package silently omits them.
 *  - **The package is written under a temporary name and renamed on success.** A crash or a full
 *    disk then leaves a visibly-partial `.part` file rather than something that looks like a
 *    complete export. `checksum.txt` would eventually catch a truncated package on import, but by
 *    then the operator has already left the site.
 */
class ExportCoordinator(
    private val context: Context,
    private val exporter: PackageExporter,
    private val settings: ObserverSettings,
    private val collection: CollectionCoordinator,
    private val appVersion: String,
) {

    sealed interface Outcome {
        data class Written(
            val fileName: String,
            val observations: Long,
            val bytes: Long,
            val sha256: String?,
            val location: String,
        ) : Outcome

        /** Nothing to export. Reported rather than written: an empty package invites confusion. */
        data object Empty : Outcome

        data class NoDestination(val reason: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /** Everything collected on a UTC day, whichever sessions it spans. */
    suspend fun exportDay(dateStamp: String = Iso8601.utcDateStamp(System.currentTimeMillis())): Outcome =
        export { observer -> exporter.planDay(observer, dateStamp) }

    suspend fun exportSession(sessionId: String): Outcome =
        export { observer -> exporter.planSession(observer, sessionId) }

    private suspend fun export(plan: suspend (ObserverIdentity) -> PackageExporter.Plan): Outcome {
        val config = settings.snapshot()
        val observerId = config.observerId
            ?: return Outcome.NoDestination("No observer identity is configured")
        val treeUri = config.exportTreeUri
            ?: return Outcome.NoDestination("Choose an export folder first")

        // Anything still buffered belongs in this package, not the next one.
        collection.flush()

        val observer = AndroidRadioStack(context).describeObserver(
            observerId = observerId,
            friendlyName = config.friendlyName.ifBlank { observerId },
            appVersion = appVersion,
            installationId = settings.installationId(),
            buildingId = config.buildingId,
            defaultZoneId = config.defaultZoneId,
            xCoordinate = config.xCoordinate,
            yCoordinate = config.yCoordinate,
            fixedObserver = config.fixedObserver,
            notes = config.notes,
        )

        val exportPlan = plan(observer)
        if (exportPlan.isEmpty) return Outcome.Empty

        return withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: return@withContext Outcome.NoDestination("The export folder is no longer available")
            if (!tree.canWrite()) {
                // The grant was revoked or the volume was ejected. Said plainly, because the
                // operator can fix it in seconds while still on site.
                return@withContext Outcome.NoDestination(
                    "Permission for the export folder was lost. Choose it again.",
                )
            }

            val temporaryName = exportPlan.packageName + PART_SUFFIX
            tree.findFile(temporaryName)?.delete()
            val document = tree.createFile(MIME_ZIP, temporaryName)
                ?: return@withContext Outcome.Failed("Could not create the package file")

            val result = runCatching {
                context.contentResolver.openOutputStream(document.uri)?.use { stream ->
                    val sink = ZipExportSink(stream)
                    exporter.write(exportPlan, sink).also { stream.flush() }
                } ?: error("Could not open the package file for writing")
            }

            result.fold(
                onSuccess = { written -> finalise(tree, document, exportPlan, written) },
                onFailure = { failure ->
                    document.delete()
                    Outcome.Failed(failure.message ?: failure::class.java.simpleName)
                },
            )
        }
    }

    private fun finalise(
        tree: DocumentFile,
        document: DocumentFile,
        plan: PackageExporter.Plan,
        result: com.rfmapper.core.export.ExportResult,
    ): Outcome {
        // An existing package of the same name is replaced only once the new one is complete.
        tree.findFile(plan.packageName)?.delete()
        val renamed = runCatching { document.renameTo(plan.packageName) }.getOrDefault(false)
        if (!renamed) {
            return Outcome.Failed(
                "The package was written as ${document.name} but could not be renamed",
            )
        }

        return Outcome.Written(
            fileName = plan.packageName,
            observations = result.observationsWritten,
            bytes = result.bytesPerEntry.values.sum(),
            sha256 = result.entryDigests[com.rfmapper.core.export.ExportPackage.OBSERVATIONS_CSV],
            location = tree.name ?: tree.uri.toString(),
        )
    }

    private companion object {
        const val MIME_ZIP = "application/zip"

        /** A partial package must not be mistakable for a finished one. */
        const val PART_SUFFIX = ".part"
    }
}
