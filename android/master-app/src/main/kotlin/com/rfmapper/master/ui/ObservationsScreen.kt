package com.rfmapper.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.SensorType
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard
import com.rfmapper.master.ui.components.formatCount

/**
 * The raw layer, browsable row by row.
 *
 * Worth having even though nothing here is editable — precisely because nothing here is editable.
 * When a derived estimate looks wrong, the only way to find out whether the algorithm or the
 * evidence is at fault is to read the evidence.
 */
@Composable
fun ObservationsScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val pages = viewModel.observationPages.collectAsLazyPagingItems()
    val total by viewModel.observationCount.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var identifierQuery by remember { mutableStateOf(filter.identifier.orEmpty()) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard("Raw observations", subtitle = "${formatCount(total)} held, immutable") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = filter.sensorType == null,
                        onClick = { viewModel.setFilter(filter.copy(sensorType = null)) },
                        label = { Text("all") },
                    )
                    listOf(SensorType.WIFI_SCAN, SensorType.BLE, SensorType.RTT, SensorType.GPS)
                        .forEach { sensor ->
                            FilterChip(
                                selected = filter.sensorType == sensor,
                                onClick = { viewModel.setFilter(filter.copy(sensorType = sensor)) },
                                label = { Text(sensor.name.lowercase().replace('_', ' ')) },
                            )
                        }
                }
                OutlinedTextField(
                    value = identifierQuery,
                    onValueChange = {
                        identifierQuery = it
                        viewModel.setFilter(filter.copy(identifier = it.trim().ifBlank { null }))
                    },
                    label = { Text("Radio identifier") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        items(count = pages.itemCount, key = pages.itemKey { it.observationId }) { index ->
            pages[index]?.let { ObservationRow(it) }
        }

        if (pages.itemCount == 0) {
            item {
                Text(
                    "Nothing matches. Import an observation package to populate the raw layer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ObservationRow(observation: Observation) {
    SectionCard(observation.radioIdentifier, subtitle = observation.timestampUtc) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(observation.sensorType.name, MaterialTheme.colorScheme.primary)
            Chip(observation.identifierType.name, MaterialTheme.colorScheme.secondary)

            // An ephemeral identifier is never attributed, whatever it correlates with, so the
            // browser marks it rather than leaving the reader to infer it from the type name.
            if (observation.identifierType.isEphemeral) {
                Chip("ephemeral", MaterialTheme.colorScheme.tertiary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyValue("Observer", observation.observerId)
            observation.rssi?.let { KeyValue("RSSI", "$it dBm") }
            observation.rttDistanceMm?.let { distance ->
                // A range is only ever shown with its standard deviation: a bare distance from an
                // RTT measurement invites exactly the false precision the RTT tier exists to guard.
                val spread = observation.rttStddevMm
                    ?.let { "±%.2f m".format(it / 1000.0) }
                    ?: "spread not reported"
                KeyValue("Range", "%.2f m  %s".format(distance / 1000.0, spread))
            }
            observation.ssid?.let { KeyValue("SSID", it) }
            observation.zoneId?.let { KeyValue("Zone claimed", it) }
            observation.targetDeviceId?.let { KeyValue("Attributed to", it) }
        }
    }
}
