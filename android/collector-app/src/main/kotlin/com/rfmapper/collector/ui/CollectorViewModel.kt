package com.rfmapper.collector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfmapper.collector.CollectorGraph
import com.rfmapper.collector.collection.CollectionCoordinator
import com.rfmapper.collector.export.ExportCoordinator
import com.rfmapper.collector.settings.ObserverSettings
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.CollectionStatus
import com.rfmapper.core.radio.ObserverPlace
import com.rfmapper.core.radio.ScanProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the whole Collector.
 *
 * One view model rather than one per screen: every screen reads the same session status and the same
 * observer configuration, and splitting them would mean either duplicating those subscriptions or
 * inventing a shared holder that is this class under another name.
 */
class CollectorViewModel(
    private val graph: CollectorGraph,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
) : ViewModel() {

    val status: StateFlow<CollectionStatus> = graph.coordinator.status

    val capabilities: StateFlow<Map<ObserverCapability, Capability>> = graph.coordinator.capabilities

    val settings: StateFlow<ObserverSettings.Snapshot> = graph.settings.snapshots
        .stateIn(viewModelScope, SharingStarted.Eagerly, EMPTY_SETTINGS)

    val totalObservations: StateFlow<Long> = graph.repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val recentSessions = graph.repository.observeRecentSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _survey = MutableStateFlow<SurveyUiState>(SurveyUiState.Idle)
    val survey: StateFlow<SurveyUiState> = _survey.asStateFlow()

    /** One-shot user-facing messages. A channel, so a rotation cannot replay a stale toast. */
    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()

    init {
        refreshCapabilities()
    }

    fun refreshCapabilities() = graph.coordinator.refreshCapabilities()

    fun startCollection() {
        if (!settings.value.isConfigured) {
            viewModelScope.launch { messages.send("Set an observer id before collecting") }
            return
        }
        startService()
    }

    fun stopCollection() = stopService()

    fun setScanProfile(profile: ScanProfile) {
        viewModelScope.launch { graph.settings.setScanProfile(profile) }
    }

    fun saveIdentity(
        observerId: String,
        friendlyName: String,
        buildingId: String,
        zoneId: String,
        notes: String,
    ) {
        viewModelScope.launch {
            graph.settings.setIdentity(
                observerId = observerId,
                friendlyName = friendlyName,
                buildingId = buildingId.ifBlank { null },
                defaultZoneId = zoneId.ifBlank { null },
                notes = notes.ifBlank { null },
            )
            messages.send("Observer identity saved")
        }
    }

    fun setExportFolder(uri: String) {
        viewModelScope.launch {
            graph.settings.setExportTree(uri)
            messages.send("Export folder set")
        }
    }

    fun exportToday() = runExport { graph.export.exportDay() }

    fun exportSession(sessionId: String) = runExport { graph.export.exportSession(sessionId) }

    private fun runExport(block: suspend () -> ExportCoordinator.Outcome) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { block() }.getOrElse { failure ->
                ExportCoordinator.Outcome.Failed(failure.message ?: "unknown error")
            }
            _busy.value = false
            messages.send(describe(outcome))
        }
    }

    private fun describe(outcome: ExportCoordinator.Outcome): String = when (outcome) {
        is ExportCoordinator.Outcome.Written ->
            "${outcome.fileName}: ${outcome.observations} observations, " +
                "${outcome.bytes / 1024} KiB, written to ${outcome.location}"
        ExportCoordinator.Outcome.Empty -> "Nothing to export for that selection"
        is ExportCoordinator.Outcome.NoDestination -> outcome.reason
        is ExportCoordinator.Outcome.Failed -> "Export failed: ${outcome.reason}"
    }

    /**
     * Runs a timed ground-truth capture.
     *
     * The capture requires a live session, and the button that reaches here is disabled without
     * one. Ground truth captured outside a session would have no scan cadence behind it and would
     * report a sample count of zero — an empty calibration point that looks like a real one.
     */
    fun startSurvey(surveyPointId: String, buildingId: String, zoneId: String, seconds: Int) {
        if (_survey.value is SurveyUiState.Capturing) return
        if (!status.value.isRunning) {
            viewModelScope.launch { messages.send("Start a session before capturing ground truth") }
            return
        }
        if (surveyPointId.isBlank() || buildingId.isBlank()) {
            viewModelScope.launch { messages.send("A survey point needs an id and a building") }
            return
        }

        _survey.value = SurveyUiState.Capturing(surveyPointId, seconds)
        viewModelScope.launch {
            val outcome = runCatching {
                graph.coordinator.survey(
                    surveyPointId = surveyPointId.trim(),
                    place = ObserverPlace(
                        buildingId = buildingId.trim(),
                        zoneId = zoneId.trim().ifBlank { null },
                    ),
                    durationMillis = seconds * 1000L,
                )
            }
            _survey.value = outcome.fold(
                onSuccess = { SurveyUiState.Complete(it) },
                onFailure = { SurveyUiState.Failed(it.message ?: "capture failed") },
            )
        }
    }

    fun clearSurvey() {
        _survey.value = SurveyUiState.Idle
    }

    sealed interface SurveyUiState {
        data object Idle : SurveyUiState
        data class Capturing(val surveyPointId: String, val seconds: Int) : SurveyUiState
        data class Complete(val outcome: CollectionCoordinator.SurveyOutcome) : SurveyUiState
        data class Failed(val reason: String) : SurveyUiState
    }

    companion object {
        private val EMPTY_SETTINGS = ObserverSettings.Snapshot(
            observerId = null,
            friendlyName = "",
            buildingId = null,
            defaultZoneId = null,
            scanProfile = ScanProfile.DEFAULT,
            installationId = "",
            exportTreeUri = null,
            fixedObserver = false,
            xCoordinate = null,
            yCoordinate = null,
            notes = null,
        )

        fun factory(
            graph: CollectorGraph,
            startService: () -> Unit,
            stopService: () -> Unit,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CollectorViewModel(graph, startService, stopService) as T
        }
    }
}
