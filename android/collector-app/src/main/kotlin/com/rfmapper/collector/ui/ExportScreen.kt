package com.rfmapper.collector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.collector.ui.components.KeyValue
import com.rfmapper.collector.ui.components.SectionCard
import com.rfmapper.collector.ui.components.formatCount
import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.model.Iso8601

/**
 * `EXPORT TODAY` and per-session export.
 *
 * The screen states what the package contains and how to verify it, because the package is the only
 * interface between the Collector and the rest of the system: there is no network sync, and an
 * administrator has to be able to say exactly what a file holds before importing it.
 */
@Composable
fun ExportScreen(viewModel: CollectorViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val total by viewModel.totalObservations.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "Export today",
            subtitle = "Everything recorded in the current UTC day, whichever sessions it spans.",
        ) {
            KeyValue("UTC date", Iso8601.utcDateStamp(System.currentTimeMillis()))
            KeyValue("Rows in database", formatCount(total))
            KeyValue("Destination", settings.exportTreeUri?.substringAfterLast('/') ?: "not set")

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Button(
                onClick = viewModel::exportToday,
                enabled = !busy && settings.exportTreeUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export today")
            }

            if (settings.exportTreeUri == null) {
                Text(
                    "Choose an export folder in setup first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status.isRunning) {
                Text(
                    "A session is running. Buffered observations are flushed before the package " +
                        "is written, so nothing collected so far is left out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = "What a package contains",
            subtitle = "Version 1 of the package contract. The Master validates all of it on import.",
        ) {
            Text(
                ExportPackage.REQUIRED_ENTRIES.joinToString("\n") { entry ->
                    "· $entry" + when (entry) {
                        ExportPackage.MANIFEST -> " — counts, range, generator, schema version"
                        ExportPackage.OBSERVATIONS_CSV -> " — the canonical column order"
                        ExportPackage.OBSERVATIONS_JSON -> " — the same rows, losslessly"
                        ExportPackage.OBSERVER -> " — identity and declared capabilities"
                        ExportPackage.CHECKSUM -> " — SHA-256 per entry, sorted by name"
                        else -> ""
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "CSV and JSON carry the same rows on purpose: the CSV is what a human or a " +
                    "spreadsheet can read, the JSON is what round-trips without ambiguity, and the " +
                    "importer cross-checks them against each other and against the manifest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Transfer",
            subtitle = "Manual, by design.",
        ) {
            Text(
                "Copy the .zip to the Master by USB, SD card or a file share. There is no " +
                    "background sync: a transfer that happens invisibly cannot be audited, and " +
                    "every package must be something an administrator can point at and account for.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "A file ending in .part is an interrupted export. Delete it and export again — " +
                    "the package is renamed into place only once it is complete.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "Per-session export",
            subtitle = "Available on each session in the dashboard's session list.",
        ) {
            Text(
                "A session export covers one continuous collection run even if it crossed " +
                    "midnight, which is the right unit when comparing two phones that walked the " +
                    "same route together.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
