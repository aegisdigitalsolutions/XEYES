package com.rfmapper.master.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.core.importing.ImportPreview
import com.rfmapper.data.room.DerivedPackageImporter
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.data.room.raw.ImportBatchEntity
import com.rfmapper.master.importing.ImportCoordinator
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.Counter
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard
import com.rfmapper.master.ui.components.formatCount

/**
 * The only way data enters the Master, and always in two steps.
 *
 * The preview is not a courtesy. Raw observations are immutable once written, so an import is the
 * one irreversible action in the application; showing what a file contains, and what is wrong with
 * it, before anything is written is what makes that irreversibility acceptable.
 */
@Composable
fun ImportScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val staged by viewModel.staged.collectAsStateWithLifecycle()
    val busy by viewModel.importing.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val history by viewModel.importHistory.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::stage) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(
                "Import a package",
                subtitle = "Observation package, derived package, or a site model document",
            ) {
                Text(
                    "Transfer is manual and offline by design: no server, no sync, no background " +
                        "upload. A file arrives on this device and you decide whether it enters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                ) { Text("Choose a file") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        staged?.let { item { StagedCard(it, viewModel) } }

        outcome?.let { item { OutcomeCard(it) } }

        if (history.isNotEmpty()) {
            item {
                Text(
                    "Import history",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(history, key = { it.importBatchId }) { HistoryCard(it) }
        }
    }
}

@Composable
private fun StagedCard(staged: ImportCoordinator.Staged, viewModel: MasterViewModel) {
    SectionCard(staged.displayName, subtitle = stagedKind(staged)) {
        when (staged) {
            is ImportCoordinator.Staged.Observations -> ObservationsPreview(staged.result.preview)
            is ImportCoordinator.Staged.Derived -> DerivedPreview(staged.preview)
            is ImportCoordinator.Staged.Site -> SitePreview(staged.preview)
            is ImportCoordinator.Staged.Unreadable -> Text(
                staged.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::commitStaged,
                enabled = when (staged) {
                    is ImportCoordinator.Staged.Observations -> staged.canCommit
                    is ImportCoordinator.Staged.Derived -> staged.canCommit
                    is ImportCoordinator.Staged.Site -> staged.canCommit
                    is ImportCoordinator.Staged.Unreadable -> false
                },
            ) { Text("Import") }
            OutlinedButton(onClick = viewModel::discardStaged) { Text("Discard") }
        }
    }
}

private fun stagedKind(staged: ImportCoordinator.Staged) = when (staged) {
    is ImportCoordinator.Staged.Observations -> "Observation package"
    is ImportCoordinator.Staged.Derived -> "Derived package from the Positioning Lab"
    is ImportCoordinator.Staged.Site -> "Site model"
    is ImportCoordinator.Staged.Unreadable -> "Unrecognised file"
}

@Composable
private fun ObservationsPreview(preview: ImportPreview) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Counter("to import", formatCount(preview.newCount))
        Counter("already held", formatCount(preview.duplicateCount))
        Counter("invalid", formatCount(preview.invalidCount))
    }
    preview.manifest?.let { manifest ->
        KeyValue("Observer", manifest.observerId)
        KeyValue("Declared", formatCount(manifest.observationCount))
        manifest.dateRange?.let { KeyValue("Range", "${it.from} → ${it.to}") }
        KeyValue("Collector", "${manifest.generator.name} ${manifest.generator.version}")
    }
    preview.packageSha256?.let { KeyValue("sha256", it.take(16) + "…") }
    IssueList(
        blocking = preview.blockingIssues.map { "${it.code}: ${it.message}" },
        advisory = preview.issues.filterNot { it.code.blocking }
            .map { issue -> issue.rowNumber?.let { "row $it: ${issue.message}" } ?: issue.message },
    )
}

@Composable
private fun DerivedPreview(preview: DerivedPackageImporter.Preview) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Counter("estimates", formatCount(preview.estimates.size))
        Counter("transitions", formatCount(preview.transitions.size))
        Counter("movements", formatCount(preview.movements.size))
    }
    preview.manifest?.let { manifest ->
        KeyValue("Algorithm", manifest.algorithmVersion)
        KeyValue("Dataset kind", manifest.datasetKind.name)
        KeyValue("Sources", manifest.sourceDatasetIds.joinToString())
        manifest.engineVersions.entries.sortedBy { it.key }.forEach { (engine, version) ->
            KeyValue(engine, version)
        }

        // SYNTHETIC results prove the pipeline runs. They say nothing about this site, and a
        // console that displayed them like measurements would be lying quietly.
        if (manifest.datasetKind != com.rfmapper.core.model.DatasetKind.REAL) {
            Text(
                "This generation is ${manifest.datasetKind}. It demonstrates pipeline behaviour " +
                    "and must never be read as this site's accuracy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (preview.precisionRangeCount > 0) {
        Text(
            "${preview.precisionRangeCount} estimates claim ranged precision. That tier is only " +
                "reachable with genuine RTT evidence — worth checking against your anchor list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    IssueList(
        blocking = preview.blockingIssues.map { "${it.problem}: ${it.message}" },
        advisory = preview.issues.filterNot { it.problem.blocking }.map { "${it.problem}: ${it.message}" },
    )
}

@Composable
private fun SitePreview(preview: SiteModelIo.Preview) {
    preview.model?.let { model ->
        KeyValue("Reference model", model.referenceModelId)
        KeyValue("Authored", model.createdAt)
        KeyValue("Frame origin", "%.5f, %.5f".format(model.frame.originLat, model.frame.originLon))
        KeyValue("Rotation", "%.1f°".format(model.frame.rotationDeg))
        preview.counts.forEach { (label, count) -> KeyValue(label, count.toString()) }
        Text(
            "Fingerprint status is preserved on import. A file cannot promote anything to ground " +
                "truth; only you can.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    IssueList(
        blocking = preview.parseError?.let(::listOf).orEmpty() + preview.issues,
        advisory = emptyList(),
    )
}

@Composable
private fun IssueList(blocking: List<String>, advisory: List<String>) {
    if (blocking.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Blocking (${blocking.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            blocking.take(MAX_SHOWN).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (blocking.size > MAX_SHOWN) {
                Text(
                    "… and ${blocking.size - MAX_SHOWN} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (advisory.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Reported, not blocking (${advisory.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            advisory.take(MAX_SHOWN).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (advisory.size > MAX_SHOWN) {
                Text("… and ${advisory.size - MAX_SHOWN} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: ImportCoordinator.Outcome) {
    SectionCard(outcome.headline) {
        outcome.detail.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!outcome.success) {
            Text(
                "Nothing was written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HistoryCard(batch: ImportBatchEntity) {
    val tint = when (batch.status) {
        ImportBatchEntity.Status.COMMITTED.name -> MaterialTheme.colorScheme.primary
        ImportBatchEntity.Status.REJECTED.name -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    SectionCard(batch.packageName, subtitle = batch.importedAtUtc) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(batch.status, tint)
            Chip(batch.observerId, MaterialTheme.colorScheme.secondary)
        }
        KeyValue("Accepted", formatCount(batch.acceptedCount))
        KeyValue("Duplicates", formatCount(batch.duplicateCount))
        if (batch.invalidCount > 0) KeyValue("Invalid", formatCount(batch.invalidCount))
        batch.operator?.let { KeyValue("Operator", it) }
        KeyValue("sha256", batch.packageSha256.take(16) + "…")
    }
}

private const val MAX_SHOWN = 8
