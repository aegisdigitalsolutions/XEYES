package com.rfmapper.master.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rfmapper.core.importing.ImportIssue
import com.rfmapper.data.room.DerivedPackageImporter
import com.rfmapper.data.room.PackageImporter
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.master.settings.MasterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Turns a file the operator picked into a reviewed change.
 *
 * Every path through this class is preview-then-confirm, never one step. Import is the only way
 * data enters the Master, the raw layer it writes to is immutable, and a mistaken import therefore
 * cannot be undone by editing — only by deleting a whole batch. Showing counts and issues first is
 * what makes that irreversibility safe.
 */
class ImportCoordinator(
    private val context: Context,
    private val observations: PackageImporter,
    private val derived: DerivedPackageImporter,
    private val siteModel: SiteModelIo,
    private val settings: MasterSettings,
) {

    sealed interface Staged {
        val displayName: String

        data class Observations(
            override val displayName: String,
            val result: PackageImporter.PreviewResult,
        ) : Staged {
            val canCommit: Boolean get() = result.canImport
        }

        data class Derived(
            override val displayName: String,
            val preview: DerivedPackageImporter.Preview,
        ) : Staged {
            val canCommit: Boolean get() = preview.canImport
        }

        data class Site(
            override val displayName: String,
            val preview: SiteModelIo.Preview,
        ) : Staged {
            val canCommit: Boolean get() = preview.canApply
        }

        data class Unreadable(override val displayName: String, val reason: String) : Staged
    }

    /**
     * Inspects a picked file and stages it.
     *
     * The kind is decided by content — an observation manifest, a derived manifest, a bare site
     * model document — rather than by filename, because a file renamed in transit is ordinary and
     * a package that imports as the wrong kind is not.
     */
    suspend fun stage(uri: Uri): Staged = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val head = runCatching { readHead(uri) }.getOrElse {
            return@withContext Staged.Unreadable(name, it.message ?: "the file could not be opened")
        }

        when {
            head.looksLikeZip && head.text.contains("\"package_type\":\"DERIVED\"") ->
                Staged.Derived(name, derived.preview(open(uri)))

            head.looksLikeZip ->
                Staged.Observations(name, observations.preview(name, open(uri)))

            head.text.contains("reference_model_id") ->
                Staged.Site(name, siteModel.preview(open(uri)))

            else -> Staged.Unreadable(
                name,
                "not an observation package, a derived package or a site model",
            )
        }
    }

    data class Outcome(val headline: String, val detail: List<String>, val success: Boolean)

    suspend fun commit(staged: Staged): Outcome = withContext(Dispatchers.IO) {
        when (staged) {
            is Staged.Observations -> commitObservations(staged)
            is Staged.Derived -> commitDerived(staged)
            is Staged.Site -> commitSite(staged)
            is Staged.Unreadable -> Outcome(staged.reason, emptyList(), success = false)
        }
    }

    private suspend fun commitObservations(staged: Staged.Observations): Outcome {
        if (!staged.canCommit) return blocked(staged.result.preview.blockingIssues)
        val operator = settings.currentOperator().ifBlank { null }
        val result = observations.commit(staged.result, operator)
        return Outcome(
            headline = "${result.inserted} observations imported",
            detail = buildList {
                add("${result.duplicates} already present")
                if (result.invalid > 0) add("${result.invalid} rows rejected as invalid")
                add("${result.attributed} attributed to a managed device")
                add("batch ${result.importBatchId}")
            },
            success = true,
        )
    }

    private suspend fun commitDerived(staged: Staged.Derived): Outcome {
        if (!staged.canCommit) {
            return Outcome(
                headline = "Derived package rejected",
                detail = staged.preview.blockingIssues.map { "${it.problem}: ${it.message}" },
                success = false,
            )
        }
        val result = derived.commit(staged.preview, makeActive = false)
        return Outcome(
            headline = "Generation ${result.algorithmVersion} imported",
            detail = listOf(
                "${result.estimates} position estimates",
                "${result.transitions} zone transitions",
                "${result.movements} movement estimates",
                "Not displayed until you make it the active generation",
            ),
            success = true,
        )
    }

    private suspend fun commitSite(staged: Staged.Site): Outcome {
        val model = staged.preview.model
        if (!staged.canCommit || model == null) {
            return Outcome(
                headline = "Site model not applied",
                detail = staged.preview.parseError?.let(::listOf) ?: staged.preview.issues,
                success = false,
            )
        }
        val applied = siteModel.apply(model)
        settings.setReferenceModelId(model.referenceModelId)
        return Outcome(
            headline = "Site model ${model.referenceModelId} applied",
            detail = listOf(
                "${applied.buildings} buildings, ${applied.zones} zones, ${applied.edges} edges",
                "${applied.infrastructure} infrastructure nodes, ${applied.surveyPoints} survey points",
                "${applied.fingerprints} fingerprints (status preserved), ${applied.calibrations} calibration entries",
            ),
            success = true,
        )
    }

    private fun blocked(issues: List<ImportIssue>) = Outcome(
        headline = "Package rejected",
        detail = issues.map { "${it.code}: ${it.message}" },
        success = false,
    )

    // -- file plumbing ----------------------------------------------------------------------------

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri)
        ?: throw IOException("the picked file could not be opened")

    private data class Head(val looksLikeZip: Boolean, val text: String)

    /**
     * Reads enough of the file to classify it.
     *
     * A zip's manifest is at an arbitrary offset, so the head of the *file* would not reveal the
     * package type; the whole archive is scanned as text instead, which is cheap relative to
     * parsing it and avoids decompressing twice just to learn what it is.
     */
    private fun readHead(uri: Uri): Head {
        val bytes = open(uri).use { it.readBytes() }
        val isZip = bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val text = if (isZip) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                buildString {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "manifest.json") append(zip.readBytes().decodeToString())
                        zip.closeEntry()
                    }
                }
            }.replace(" ", "")
        } else {
            bytes.decodeToString(0, minOf(bytes.size, MAX_SNIFF_BYTES))
        }
        return Head(isZip, text)
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        return uri.lastPathSegment ?: "package"
    }

    private companion object {
        const val MAX_SNIFF_BYTES = 4096
    }
}
