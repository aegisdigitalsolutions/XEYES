package com.rfmapper.master.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.rfmapper.core.model.DeviceStatus
import com.rfmapper.core.model.InfrastructureNode
import com.rfmapper.core.model.ManagedDevice
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.QualityFlag
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.model.Zone
import com.rfmapper.data.room.DerivedRepository
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.ReferenceRepository
import com.rfmapper.data.room.derived.DerivedGenerationEntity
import com.rfmapper.data.room.derived.ZoneOccupancyRow
import com.rfmapper.data.room.raw.ImportBatchDao
import com.rfmapper.data.room.raw.ImportBatchEntity
import com.rfmapper.data.room.reference.FingerprintEntity
import com.rfmapper.data.room.reference.ManagedDeviceEntity
import com.rfmapper.data.room.reference.ObserverEntity
import com.rfmapper.master.MasterGraph
import com.rfmapper.master.importing.ImportCoordinator
import com.rfmapper.master.importing.ReferenceExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the whole Master console.
 *
 * One ViewModel rather than one per screen: the screens share the active generation, the operator
 * identity and the reference registries, and splitting them would mean either duplicating those
 * subscriptions or inventing a shared holder that is this class under another name.
 */
class MasterViewModel(
    private val reference: ReferenceRepository,
    private val derived: DerivedRepository,
    private val observations: ObservationRepository,
    private val batches: ImportBatchDao,
    private val importer: ImportCoordinator,
    private val exporter: ReferenceExporter,
    private val settings: com.rfmapper.master.settings.MasterSettings,
) : ViewModel() {

    // -- identity ---------------------------------------------------------------------------------

    val operator: StateFlow<String> =
        settings.operator.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setOperator(value: String) = viewModelScope.launch { settings.setOperator(value) }

    // -- registries -------------------------------------------------------------------------------

    val devices: StateFlow<List<ManagedDeviceEntity>> = reference.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val observers: StateFlow<List<ObserverEntity>> = reference.observeObservers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val infrastructure: StateFlow<List<com.rfmapper.data.room.reference.InfrastructureNodeEntity>> =
        reference.observeInfrastructure()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val zones: StateFlow<List<Zone>> = reference.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val buildings: StateFlow<List<com.rfmapper.core.model.Building>> = reference.observeBuildings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fingerprints: StateFlow<List<FingerprintEntity>> = reference.observeFingerprints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * The device being edited, or null when the editor is closed.
     *
     * A draft rather than a [ManagedDevice]: the model refuses to exist with a blank id, which is
     * the right rule for stored data and the wrong one for a half-typed form.
     */
    data class DeviceDraft(
        val deviceId: String = "",
        val friendlyName: String = "",
        val deviceType: String = "PHONE",
        val status: DeviceStatus = DeviceStatus.AUTHORIZED,
        val wifi: List<String> = emptyList(),
        val ble: List<String> = emptyList(),
        val serviceUuids: List<String> = emptyList(),
        val notes: String? = null,
        val isNew: Boolean = true,
    )

    private val _deviceDraft = MutableStateFlow<DeviceDraft?>(null)
    val deviceDraft: StateFlow<DeviceDraft?> = _deviceDraft.asStateFlow()

    fun beginNewDevice() {
        _deviceDraft.value = DeviceDraft()
    }

    fun beginEditDevice(deviceId: String) = viewModelScope.launch {
        val device = reference.device(deviceId) ?: return@launch
        _deviceDraft.value = DeviceDraft(
            deviceId = device.deviceId,
            friendlyName = device.friendlyName,
            deviceType = device.deviceType,
            status = device.status,
            wifi = device.knownWifiIdentifiers,
            ble = device.knownBleIdentifiers,
            serviceUuids = device.knownServiceUuids,
            notes = device.notes,
            isNew = false,
        )
    }

    fun cancelDeviceEdit() {
        _deviceDraft.value = null
    }

    fun saveDevice(draft: DeviceDraft) = viewModelScope.launch {
        val device = ManagedDevice(
            deviceId = draft.deviceId.trim(),
            friendlyName = draft.friendlyName.trim(),
            deviceType = draft.deviceType.trim().ifBlank { "UNKNOWN" },
            status = draft.status,
            knownWifiIdentifiers = draft.wifi,
            knownBleIdentifiers = draft.ble,
            knownServiceUuids = draft.serviceUuids,
            notes = draft.notes?.ifBlank { null },
        )
        val rejected = reference.saveDevice(device, addedBy = operator.value.ifBlank { null })
        _deviceDraft.value = null
        _message.value = if (rejected.isEmpty()) {
            "Saved ${device.friendlyName}"
        } else {
            "Saved, but ${rejected.size} identifier(s) were unreadable: ${rejected.joinToString()}"
        }
    }

    fun deleteDevice(deviceId: String) = viewModelScope.launch {
        reference.deleteDevice(deviceId)
        _message.value = "Removed $deviceId"
    }

    fun setDeviceStatus(entity: ManagedDeviceEntity, status: DeviceStatus) = viewModelScope.launch {
        val model = reference.device(entity.deviceId) ?: return@launch
        reference.saveDevice(model.copy(status = status), addedBy = operator.value.ifBlank { null })
    }

    fun setObserverEnrolled(observerId: String, enrolled: Boolean) = viewModelScope.launch {
        reference.setEnrolled(observerId, enrolled)
        _message.value = if (enrolled) "$observerId enrolled" else "$observerId no longer enrolled"
    }

    fun saveInfrastructure(node: InfrastructureNode) = viewModelScope.launch {
        runCatching { reference.saveInfrastructure(node) }
            .onSuccess { _message.value = "Saved ${node.friendlyName}" }
            .onFailure { _message.value = it.message ?: "Could not save the node" }
    }

    /**
     * Promotes a candidate fingerprint.
     *
     * Refused outright without an operator name. An unattributed promotion would leave no way to
     * answer "who decided this was ground truth?", which is the question every later accuracy
     * dispute turns on.
     */
    fun promoteFingerprint(fingerprintId: String) = viewModelScope.launch {
        val who = operator.value
        if (who.isBlank()) {
            _message.value = "Set an operator name first: a promotion has to be attributable"
            return@launch
        }
        _message.value = if (reference.promoteFingerprint(fingerprintId, who)) {
            "$fingerprintId promoted to ground truth by $who"
        } else {
            "$fingerprintId was not a candidate"
        }
    }

    fun retireFingerprint(fingerprintId: String) = viewModelScope.launch {
        reference.retireFingerprint(fingerprintId)
        _message.value = "$fingerprintId retired"
    }

    // -- import -----------------------------------------------------------------------------------

    private val _staged = MutableStateFlow<ImportCoordinator.Staged?>(null)
    val staged: StateFlow<ImportCoordinator.Staged?> = _staged.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _outcome = MutableStateFlow<ImportCoordinator.Outcome?>(null)
    val outcome: StateFlow<ImportCoordinator.Outcome?> = _outcome.asStateFlow()

    val importHistory: StateFlow<List<ImportBatchEntity>> = batches.observeHistory(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stage(uri: Uri) = viewModelScope.launch {
        _importing.value = true
        _outcome.value = null
        _staged.value = runCatching { importer.stage(uri) }
            .getOrElse { ImportCoordinator.Staged.Unreadable("package", it.message ?: "unreadable") }
        _importing.value = false
    }

    fun commitStaged() = viewModelScope.launch {
        val staged = _staged.value ?: return@launch
        _importing.value = true
        _outcome.value = runCatching { importer.commit(staged) }
            .getOrElse { ImportCoordinator.Outcome(it.message ?: "import failed", emptyList(), false) }
        _importing.value = false
        _staged.value = null
    }

    fun discardStaged() {
        _staged.value = null
        _outcome.value = null
    }

    // -- reference export -------------------------------------------------------------------------

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /**
     * Suggested filenames, resolved eagerly because the document picker needs one synchronously
     * when the button is pressed.
     */
    val siteModelFileName: StateFlow<String> = settings.referenceModelId
        .map { id -> "site_model_${id.ifBlank { "site" }}.json" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "site_model.json")

    val registryFileName: StateFlow<String> =
        MutableStateFlow(exporter.suggestedRegistryName()).asStateFlow()

    fun exportSiteModel(destination: Uri) = export { exporter.exportSiteModel(destination) }

    fun exportDeviceRegistry(destination: Uri) = export { exporter.exportDeviceRegistry(destination) }

    private fun export(block: suspend () -> ReferenceExporter.Outcome) = viewModelScope.launch {
        _exporting.value = true
        val outcome = runCatching { block() }.getOrElse {
            ReferenceExporter.Outcome("Export failed", listOf(it.message.orEmpty()), false)
        }
        _exporting.value = false
        _outcome.value = ImportCoordinator.Outcome(
            headline = outcome.headline,
            detail = outcome.detail,
            success = outcome.success,
        )
    }

    // -- observations -----------------------------------------------------------------------------

    data class ObservationFilter(
        val observerId: String? = null,
        val sensorType: SensorType? = null,
        val identifier: String? = null,
        val buildingId: String? = null,
    )

    private val _filter = MutableStateFlow(ObservationFilter())
    val filter: StateFlow<ObservationFilter> = _filter.asStateFlow()

    fun setFilter(update: ObservationFilter) {
        _filter.value = update
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val observationPages: Flow<PagingData<Observation>> = _filter.flatMapLatest { filter ->
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            observations.browse(
                observerId = filter.observerId,
                sensorType = filter.sensorType?.name,
                identifier = filter.identifier,
                buildingId = filter.buildingId,
            )
        }.flow.map { page -> page.map { it.toObservation() } }
    }

    val observationCount: StateFlow<Long> = observations.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // -- derived ----------------------------------------------------------------------------------

    val generations: StateFlow<List<DerivedGenerationEntity>> = derived.observeGenerations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The generation on display, chosen by the administrator.
     *
     * Falls back to the newest import only when nothing has been chosen, and the UI says so. An
     * unchosen default that looked like a decision would let a pipeline change reach a
     * decision-maker without anybody approving it.
     */
    val activeVersion: StateFlow<String?> = combine(
        settings.activeAlgorithmVersion,
        derived.observeGenerations(),
    ) { chosen, all ->
        chosen?.takeIf { version -> all.any { it.algorithmVersion == version } }
            ?: all.firstOrNull()?.algorithmVersion
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setActiveVersion(algorithmVersion: String) = viewModelScope.launch {
        settings.setActiveAlgorithmVersion(algorithmVersion)
        derived.setActive(algorithmVersion)
        _message.value = "Displaying $algorithmVersion"
    }

    fun discardGeneration(algorithmVersion: String) = viewModelScope.launch {
        val removed = derived.discard(algorithmVersion)
        if (settings.activeAlgorithmVersion.first() == algorithmVersion) {
            settings.setActiveAlgorithmVersion(null)
        }
        _message.value = "Discarded $algorithmVersion ($removed derived rows; raw data untouched)"
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val latestEstimates: StateFlow<List<PositionEstimate>> = activeVersion
        .flatMapLatest { version ->
            if (version == null) flowOf(emptyList()) else derived.observeLatestPerDevice(version)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val zoneOccupancy: StateFlow<List<ZoneOccupancyRow>> = activeVersion
        .flatMapLatest { version ->
            if (version == null) flowOf(emptyList()) else derived.observeZoneOccupancy(version)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val openFlags: StateFlow<List<QualityFlag>> = derived.observeOpenFlags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _summary = MutableStateFlow<DerivedRepository.GenerationSummary?>(null)
    val summary: StateFlow<DerivedRepository.GenerationSummary?> = _summary.asStateFlow()

    fun refreshSummary() = viewModelScope.launch {
        _summary.value = activeVersion.value?.let { derived.summarise(it) }
    }

    fun acknowledgeFlag(flagId: String) = viewModelScope.launch {
        val who = operator.value
        if (who.isBlank()) {
            _message.value = "Set an operator name first: an acknowledgement has to be attributable"
            return@launch
        }
        derived.acknowledgeFlag(flagId, who)
    }

    private val _track = MutableStateFlow<DeviceTrack?>(null)
    val track: StateFlow<DeviceTrack?> = _track.asStateFlow()

    data class DeviceTrack(
        val deviceId: String,
        val estimates: List<PositionEstimate>,
        val transitions: List<com.rfmapper.core.model.ZoneTransition>,
    )

    fun loadTrack(deviceId: String) = viewModelScope.launch {
        val version = activeVersion.value ?: return@launch
        _track.value = DeviceTrack(
            deviceId = deviceId,
            estimates = derived.track(deviceId, version, 0L, Long.MAX_VALUE),
            transitions = derived.transitions(deviceId, version),
        )
    }

    fun clearTrack() {
        _track.value = null
    }

    // -- overview ---------------------------------------------------------------------------------

    data class Overview(
        val observations: Long,
        val devices: Int,
        val enrolledObservers: Int,
        val zones: Int,
        val groundTruthFingerprints: Int,
        val generations: Int,
        val openFlags: Int,
    )

    val overview: StateFlow<Overview> = combine(
        observations.observeCount(),
        reference.observeDevices(),
        reference.observeObservers(),
        reference.observeZones(),
        reference.observeFingerprints(),
    ) { count, devices, observers, zones, fingerprints ->
        Overview(
            observations = count,
            devices = devices.size,
            enrolledObservers = observers.count { it.enrolled },
            zones = zones.size,
            groundTruthFingerprints = fingerprints.count { it.status == "GROUND_TRUTH" },
            generations = 0,
            openFlags = 0,
        )
    }.combine(derived.observeGenerations()) { overview, generations ->
        overview.copy(generations = generations.size)
    }.combine(derived.observeOpenFlags()) { overview, flags ->
        overview.copy(openFlags = flags.size)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Overview(0, 0, 0, 0, 0, 0, 0),
    )

    class Factory(private val graph: MasterGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MasterViewModel(
            reference = graph.reference,
            derived = graph.derived,
            observations = graph.observations,
            batches = graph.database.importBatchDao(),
            importer = graph.importer,
            exporter = graph.exporter,
            settings = graph.settings,
        ) as T
    }
}
