package com.rfmapper.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.core.model.DatasetKind
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.data.room.derived.DerivedGenerationEntity
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard
import com.rfmapper.master.ui.components.TierChip
import com.rfmapper.master.ui.components.describePosition
import com.rfmapper.master.ui.components.formatCount
import com.rfmapper.master.ui.components.formatPercent
import com.rfmapper.master.ui.components.tierColour

/**
 * What the Positioning Lab concluded, and how strongly.
 *
 * Every estimate is shown with its tier, its confidence and — when it has coordinates — its
 * uncertainty. None of those is optional in the UI, because a result stripped of them reads as
 * certainty the data does not contain.
 */
@Composable
fun DerivedScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val generations by viewModel.generations.collectAsStateWithLifecycle()
    val activeVersion by viewModel.activeVersion.collectAsStateWithLifecycle()
    val estimates by viewModel.latestEstimates.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()

    LaunchedEffect(activeVersion) { viewModel.refreshSummary() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (generations.isEmpty()) {
            item {
                SectionCard("No derived results") {
                    Text(
                        "The Master computes nothing itself. Run the Positioning Lab over exported " +
                            "observations and import the resulting DERIVED package.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        items(generations, key = { it.algorithmVersion }) { generation ->
            GenerationCard(
                generation = generation,
                isActive = generation.algorithmVersion == activeVersion,
                onDisplay = { viewModel.setActiveVersion(generation.algorithmVersion) },
                onDiscard = { viewModel.discardGeneration(generation.algorithmVersion) },
            )
        }

        summary?.let { item { QualityCard(it) } }

        if (estimates.isNotEmpty()) {
            item {
                Text(
                    "Latest position per device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(estimates, key = { it.estimateId }) { estimate ->
                EstimateCard(
                    estimate = estimate,
                    zoneName = zones.firstOrNull { it.zoneId == estimate.zoneId }?.name,
                    onTrack = { viewModel.loadTrack(estimate.deviceId) },
                )
            }
        }

        track?.let { deviceTrack ->
            item {
                SectionCard(
                    "Track: ${deviceTrack.deviceId}",
                    subtitle = "${deviceTrack.estimates.size} estimates, ${deviceTrack.transitions.size} transitions",
                ) {
                    deviceTrack.transitions.take(10).forEach { transition ->
                        val latencyMs = latencyMillis(
                            transition.transitionStartUtc,
                            transition.transitionConfirmedUtc,
                        )
                        KeyValue(
                            transition.eventType.name.removePrefix("RF_"),
                            buildString {
                                append(transition.originZoneId ?: "outside")
                                append(" → ")
                                append(transition.destinationZoneId ?: "outside")
                                // confirmed − start is the engine's own hysteresis delay. Showing
                                // it keeps the system's lag measurable rather than invisible.
                                latencyMs?.let { append("  (confirmed after %.1fs)".format(it / 1000.0)) }
                            },
                        )
                        if (transition.topologyStatus.name == "NON_ADJACENT") {
                            Text(
                                "Topology says these zones do not connect. The site graph may be " +
                                    "wrong — a new door, a moved access point — so the transition " +
                                    "is reported rather than suppressed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    TextButton(onClick = viewModel::clearTrack) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun GenerationCard(
    generation: DerivedGenerationEntity,
    isActive: Boolean,
    onDisplay: () -> Unit,
    onDiscard: () -> Unit,
) {
    SectionCard(generation.algorithmVersion, subtitle = "Imported ${generation.importedAtUtc}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isActive) Chip("displayed", MaterialTheme.colorScheme.primary)
            Chip(
                generation.datasetKind,
                if (generation.datasetKind == DatasetKind.REAL.name) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (generation.datasetKind != DatasetKind.REAL.name) {
            Text(
                "Synthetic data. Proves the pipeline runs; says nothing about this site's accuracy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        KeyValue("Estimates", formatCount(generation.estimateCount))
        KeyValue("Transitions", formatCount(generation.transitionCount))
        KeyValue("Computed from", generation.sourceDatasetIds.joinToString())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isActive) TextButton(onClick = onDisplay) { Text("Display this generation") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

@Composable
private fun QualityCard(summary: com.rfmapper.data.room.DerivedRepository.GenerationSummary) {
    SectionCard("How strong are these results?") {
        KeyValue("Devices located", summary.devices.toString())
        KeyValue("Assert a coordinate", formatPercent(summary.coordinateShare))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val total = summary.byTier.values.sum().coerceAtLeast(1)
            PrecisionTier.entries.forEach { tier ->
                val count = summary.byTier[tier] ?: 0
                if (count == 0) return@forEach
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TierChip(tier)
                    Text("$count", style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    color = tierColour(tier),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        /*
         * A pipeline that suddenly starts producing coordinates has not necessarily got better.
         * Saying so here is cheaper than discovering it after somebody has acted on a map.
         */
        if (summary.coordinateShare > COORDINATE_SHARE_WARNING) {
            Text(
                "Most estimates assert coordinates. That is unusual for RSSI evidence — check the " +
                    "benchmark report before treating these as measured positions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun EstimateCard(estimate: PositionEstimate, zoneName: String?, onTrack: () -> Unit) {
    SectionCard(estimate.deviceId, subtitle = estimate.timestampUtc) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TierChip(estimate.precisionTier)
            Chip("confidence %.2f".format(estimate.confidence), MaterialTheme.colorScheme.secondary)
        }
        Text(
            describePosition(
                tier = estimate.precisionTier,
                zoneName = zoneName ?: estimate.zoneId,
                buildingName = estimate.buildingId,
                x = estimate.x,
                y = estimate.y,
                uncertaintyM = estimate.horizontalUncertaintyM,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        KeyValue("Method", estimate.method)
        KeyValue("Evidence", "${estimate.supportingObservationIds.size} observations")
        KeyValue("Observers", estimate.supportingObserverIds.joinToString())

        // The named factors whose product is the confidence. Without them a number like 0.71 is
        // an opinion; with them it is an argument the reader can check.
        if (estimate.confidenceFactors.isNotEmpty()) {
            estimate.confidenceFactors.entries.sortedBy { it.key }.forEach { (factor, value) ->
                KeyValue(factor, "%.2f".format(value))
            }
        }
        if (estimate.qualityFlags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                estimate.qualityFlags.forEach { Chip(it, MaterialTheme.colorScheme.tertiary) }
            }
        }
        TextButton(onClick = onTrack) { Text("Show track") }
    }
}

private fun latencyMillis(startUtc: String, confirmedUtc: String): Long? {
    val start = com.rfmapper.core.model.Iso8601.parseToEpochMillis(startUtc) ?: return null
    val confirmed = com.rfmapper.core.model.Iso8601.parseToEpochMillis(confirmedUtc) ?: return null
    return confirmed - start
}

private const val COORDINATE_SHARE_WARNING = 0.6
