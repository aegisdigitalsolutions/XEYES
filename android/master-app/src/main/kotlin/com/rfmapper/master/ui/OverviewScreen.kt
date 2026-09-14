package com.rfmapper.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.core.model.QualityFlag
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.Counter
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard
import com.rfmapper.master.ui.components.formatCount

/**
 * What the Master holds, and what needs a human.
 *
 * Open quality flags are given their own section rather than a badge. Every flag is an advisory
 * finding that only a person can act on — nothing in the system consumes one to change its own
 * behaviour — so burying them would leave the recommendations permanently unread.
 */
@Composable
fun OverviewScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val operator by viewModel.operator.collectAsStateWithLifecycle()
    val flags by viewModel.openFlags.collectAsStateWithLifecycle()
    val activeVersion by viewModel.activeVersion.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard("Holdings", subtitle = "Everything this console has been given") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Counter("observations", formatCount(overview.observations))
                    Counter("managed\ndevices", formatCount(overview.devices))
                    Counter("enrolled\nobservers", formatCount(overview.enrolledObservers))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Counter("zones", formatCount(overview.zones))
                    Counter("ground-truth\nfingerprints", formatCount(overview.groundTruthFingerprints))
                    Counter("derived\ngenerations", formatCount(overview.generations))
                }
            }
        }

        item {
            SectionCard(
                "Displayed generation",
                subtitle = "Derived results are only ever shown for the version you choose",
            ) {
                KeyValue("Active", activeVersion ?: "none imported")
                if (overview.generations > 1) {
                    Text(
                        "${overview.generations} generations are held. Reprocessing never replaces " +
                            "an earlier one, so an estimate shown last month can still be reproduced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { OperatorCard(operator, viewModel::setOperator) }

        if (flags.isNotEmpty()) {
            item {
                Text(
                    "Findings for you (${flags.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(flags, key = { it.flagId }) { flag ->
                FlagCard(flag) { viewModel.acknowledgeFlag(flag.flagId) }
            }
        }
    }
}

@Composable
private fun OperatorCard(operator: String, onSave: (String) -> Unit) {
    var draft by remember(operator) { mutableStateOf(operator) }

    SectionCard(
        "Operator",
        subtitle = "Stamped on every import, promotion and acknowledgement",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { Text("Your name or identifier") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Promotion to ground truth is refused without this. An unattributed promotion cannot " +
                "be distinguished from one nobody made.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onSave(draft) },
            enabled = draft.isNotBlank() && draft != operator,
        ) { Text("Save") }
    }
}

@Composable
private fun FlagCard(flag: QualityFlag, onAcknowledge: () -> Unit) {
    val tint = when (flag.severity) {
        QualityFlag.Severity.ERROR -> MaterialTheme.colorScheme.error
        QualityFlag.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        QualityFlag.Severity.INFO -> MaterialTheme.colorScheme.primary
    }

    SectionCard(flag.code) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(flag.severity.name, tint)
            Text(
                listOfNotNull(flag.scope, flag.scopeId).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(flag.message, style = MaterialTheme.typography.bodyMedium)

        // The evidence travels with the finding so the administrator can judge it rather than
        // trust it.
        if (flag.evidence.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                flag.evidence.entries.sortedBy { it.key }.forEach { (key, value) ->
                    KeyValue(key, value)
                }
            }
        }
        TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
    }
}
