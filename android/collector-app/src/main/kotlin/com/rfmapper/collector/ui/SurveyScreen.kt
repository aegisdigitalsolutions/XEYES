package com.rfmapper.collector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.collector.ui.components.KeyValue
import com.rfmapper.collector.ui.components.SectionCard

/**
 * Timed ground-truth capture at a labelled point.
 *
 * Survey Mode is in Milestone 1 even though the specification lists survey work later, for one
 * reason: without it the very first field trip produces no ground truth, and every fingerprint,
 * every calibration offset and every error metric downstream is built on ground truth. It reuses
 * the identical write path as ordinary collection — the only difference is the `SurveyContext` the
 * factory stamps onto each row.
 *
 * The operator must stand still at the named point for the whole window. That is stated on screen
 * because a survey walked through is worse than no survey: it produces a confident-looking
 * fingerprint for a location nobody actually measured.
 */
@Composable
fun SurveyScreen(viewModel: CollectorViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val survey by viewModel.survey.collectAsStateWithLifecycle()

    var surveyPointId by rememberSaveable { mutableStateOf("") }
    var buildingId by rememberSaveable { mutableStateOf("") }
    var zoneId by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableIntStateOf(60) }

    if (buildingId.isBlank() && settings.buildingId != null) buildingId = settings.buildingId!!

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!status.isRunning) {
            SectionCard(
                title = "No session running",
                subtitle = "Ground truth needs a live session behind it. A capture with no scan " +
                    "cadence would record zero samples and look like a real survey point.",
            ) {}
        }

        SectionCard(
            title = "Survey point",
            subtitle = "The id must match the point in the site model, exactly.",
        ) {
            OutlinedTextField(
                value = surveyPointId,
                onValueChange = { surveyPointId = it },
                label = { Text("Survey point id (e.g. B7_CENTER)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = buildingId,
                onValueChange = { buildingId = it },
                label = { Text("Building id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = zoneId,
                onValueChange = { zoneId = it },
                label = { Text("Zone id (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(
            title = "Capture window",
            subtitle = "Sixty seconds is the specification's default. Shorter windows on a " +
                "throttled device may catch only one Wi-Fi scan, which is not a fingerprint.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in listOf(30, 60, 120, 300)) {
                    FilterChip(
                        selected = seconds == option,
                        onClick = { seconds = option },
                        label = { Text("${option}s") },
                    )
                }
            }
        }

        when (val state = survey) {
            CollectorViewModel.SurveyUiState.Idle -> {
                Button(
                    onClick = {
                        viewModel.startSurvey(surveyPointId, buildingId, zoneId, seconds)
                    },
                    enabled = status.isRunning &&
                        surveyPointId.isNotBlank() &&
                        buildingId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Capture ${seconds}s of ground truth")
                }
            }

            is CollectorViewModel.SurveyUiState.Capturing -> {
                SectionCard(
                    title = "Capturing at ${state.surveyPointId}",
                    subtitle = "Stand still. Do not walk while the window is open.",
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    KeyValue("Ground truth rows", status.counts.groundTruth.toString())
                }
            }

            is CollectorViewModel.SurveyUiState.Complete -> {
                SectionCard(
                    title = "Captured ${state.outcome.sampleCount} observations",
                    subtitle = "Tagged GROUND_TRUTH at ${state.outcome.surveyPointId}",
                ) {
                    KeyValue("Survey session", state.outcome.surveySessionId.take(8))
                    if (state.outcome.sampleCount == 0L) {
                        Text(
                            "Nothing was captured. Check the sensor panel on the dashboard: a " +
                                "radio that is off returns empty results without reporting an error.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::clearSurvey,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Capture another point")
                    }
                }
            }

            is CollectorViewModel.SurveyUiState.Failed -> {
                SectionCard(title = "Capture failed", subtitle = state.reason) {
                    OutlinedButton(
                        onClick = viewModel::clearSurvey,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }

        SectionCard(
            title = "Suggested sweep",
            subtitle = "From the Milestone 1 field-test protocol. Survey these before leaving site.",
        ) {
            Text(
                listOf(
                    "B7_CENTER", "B7_NORTH_DOOR", "B7_SOUTH_DOOR",
                    "B7_B9_PATH", "B9_CENTER", "B4_CENTER",
                ).joinToString("\n") { "· $it" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
