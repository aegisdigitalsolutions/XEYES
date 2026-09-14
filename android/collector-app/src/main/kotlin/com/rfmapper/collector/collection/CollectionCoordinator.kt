package com.rfmapper.collector.collection

import android.content.Context
import com.rfmapper.collector.settings.ObserverSettings
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.SessionSummary
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.CollectionEngine
import com.rfmapper.core.radio.CollectionStatus
import com.rfmapper.core.radio.DegradationContext
import com.rfmapper.core.radio.ObservationFactory
import com.rfmapper.core.radio.ObserverPlace
import com.rfmapper.core.radio.ScanProfile
import com.rfmapper.core.radio.SessionContext
import com.rfmapper.core.radio.SurveyContext
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.raw.SessionEntity
import com.rfmapper.radio.android.AndroidClock
import com.rfmapper.radio.android.AndroidRadioStack
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Owns the lifetime of a collection session: builds the radio stack, drives [CollectionEngine], and
 * keeps the session row in the database current.
 *
 * Single instance per process, held by the application, because there must be exactly one writer.
 * Two coordinators would each run their own Wi-Fi scan loop and race each other through the scan
 * quota, halving the effective sample rate while appearing to double it.
 *
 * The session row is persisted at start and refreshed periodically rather than only at stop. If the
 * OS kills the process mid-session — which OEM battery managers do routinely — the row still exists
 * with counters current to within [SESSION_FLUSH_INTERVAL_MILLIS], so the data collected before the
 * kill remains attributable to a known session instead of an orphan id.
 */
class CollectionCoordinator(
    private val context: Context,
    private val repository: ObservationRepository,
    private val settings: ObserverSettings,
    private val appVersion: String,
) {

    private val scope = CoroutineScope(SupervisorJob())
    private val lifecycle = Mutex()

    private var engine: CollectionEngine? = null
    private var stack: AndroidRadioStack? = null
    private var sessionJobs: CoroutineScope? = null
    private var housekeeping: Job? = null
    private var activeObserver: ObserverIdentity? = null
    private var activeSession: SessionContext? = null
    private var batteryStartPct: Int? = null

    private val _status = MutableStateFlow(CollectionStatus.idle())
    val status: StateFlow<CollectionStatus> = _status.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<ObserverCapability, Capability>>(emptyMap())
    val capabilities: StateFlow<Map<ObserverCapability, Capability>> = _capabilities.asStateFlow()

    private val _lastSummary = MutableStateFlow<SessionSummary?>(null)
    val lastSummary: StateFlow<SessionSummary?> = _lastSummary.asStateFlow()

    val isRunning: Boolean get() = engine != null

    /** Refreshes the capability matrix. Cheap, and called whenever the UI comes forward. */
    fun refreshCapabilities() {
        _capabilities.value = AndroidRadioStack(context).capabilities()
    }

    /**
     * Starts a session and returns its id.
     *
     * @throws IllegalStateException when no observer identity has been configured. Refusing is
     *   deliberate: an observation with no `observer_id` cannot be attributed, cannot be calibrated,
     *   and cannot be fused, so it is worth less than no observation at all.
     */
    suspend fun start(): String = lifecycle.withLock {
        activeSession?.let { return@withLock it.sessionId }

        val config = settings.snapshot()
        val observerId = config.observerId
        check(!observerId.isNullOrBlank()) { "an observer identity must be configured before collecting" }

        val installationId = settings.installationId()
        val radios = AndroidRadioStack(
            context = context,
            profile = config.scanProfile,
            onThrottleState = { state -> engine?.setThrottleState(state) },
        )
        val observer = radios.describeObserver(
            observerId = observerId,
            friendlyName = config.friendlyName.ifBlank { observerId },
            appVersion = appVersion,
            installationId = installationId,
            buildingId = config.buildingId,
            defaultZoneId = config.defaultZoneId,
            xCoordinate = config.xCoordinate,
            yCoordinate = config.yCoordinate,
            fixedObserver = config.fixedObserver,
            notes = config.notes,
        )

        val collectionEngine = CollectionEngine(
            factory = ObservationFactory(observer = observer, clock = AndroidClock),
            writer = repository,
            clock = AndroidClock,
        )

        val session = SessionContext(
            sessionId = UUID.randomUUID().toString(),
            startedAtMillis = System.currentTimeMillis(),
            scanProfile = config.scanProfile,
            place = ObserverPlace.of(observer),
        )

        collectionEngine.start(session)
        collectionEngine.setDegradation(degradationFrom(radios.capabilities()))

        val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob())
        collectionEngine.collect(jobs, radios.wifi.samples())
        collectionEngine.collect(jobs, radios.ble.samples())
        collectionEngine.collect(jobs, radios.location.samples())

        engine = collectionEngine
        stack = radios
        sessionJobs = jobs
        activeObserver = observer
        activeSession = session
        _capabilities.value = radios.capabilities()
        batteryStartPct = radios.deviceContext.batteryPercent()

        persistSession(collectionEngine.currentSummary(), observerId, session.startedAtMillis)

        jobs.launch {
            collectionEngine.status.collect { _status.value = it }
        }
        housekeeping = jobs.launch { housekeep(collectionEngine, radios, observerId, session) }

        session.sessionId
    }

    /** Ends the session, flushing pending writes, and returns its final summary. */
    suspend fun stop(): SessionSummary? = lifecycle.withLock {
        val collectionEngine = engine ?: return@withLock null
        val session = activeSession
        val observerId = activeObserver?.observerId

        housekeeping?.cancel()
        // Providers are stopped before the final flush so that no sample arrives after the summary
        // has been computed; a count that disagrees with the row count would make the manifest lie.
        sessionJobs?.coroutineContext?.get(Job)?.cancelAndJoin()

        val summary = runCatching { collectionEngine.stop() }.getOrNull()
        if (summary != null && session != null && observerId != null) {
            persistSession(
                summary.copy(batteryStartPct = batteryStartPct, batteryEndPct = batteryPercent()),
                observerId,
                session.startedAtMillis,
            )
        }

        engine = null
        stack = null
        sessionJobs = null
        housekeeping = null
        activeObserver = null
        activeSession = null
        batteryStartPct = null
        _status.value = CollectionStatus.idle()
        _lastSummary.value = summary
        summary
    }

    /**
     * Captures ground truth at a labelled point for [durationMillis].
     *
     * Every row captured in this window is tagged `sample_kind=GROUND_TRUTH` with the survey point
     * and survey session, which is what makes the claim verifiable later. Survey mode overrides the
     * session's own place, because during a survey the operator is standing at a known point rather
     * than wherever the session began.
     */
    suspend fun survey(
        surveyPointId: String,
        place: ObserverPlace,
        durationMillis: Long,
        operator: String? = null,
        conditions: String? = null,
    ): SurveyOutcome {
        val collectionEngine = engine ?: error("a survey requires a running session")
        val surveySessionId = UUID.randomUUID().toString()
        val before = collectionEngine.status.value.counts.groundTruth

        collectionEngine.beginSurvey(
            SurveyContext(
                surveySessionId = surveySessionId,
                surveyPointId = surveyPointId,
                place = place,
                operator = operator,
                conditions = conditions,
            ),
        )
        try {
            delay(durationMillis)
        } finally {
            collectionEngine.endSurvey()
            // Flushed here rather than on the next batch deadline so the operator can see the
            // captured count before walking away from the point.
            runCatching { collectionEngine.flush() }
        }

        val after = collectionEngine.status.value.counts.groundTruth
        return SurveyOutcome(
            surveySessionId = surveySessionId,
            surveyPointId = surveyPointId,
            sampleCount = (after - before).coerceAtLeast(0),
        )
    }

    data class SurveyOutcome(
        val surveySessionId: String,
        val surveyPointId: String,
        val sampleCount: Long,
    )

    /** Persists whatever is buffered. Called before an export so the package is complete. */
    suspend fun flush() {
        runCatching { engine?.flush() }
    }

    /**
     * Periodic maintenance while a session runs: keeps the session row current, re-reads
     * capabilities so a radio switched off mid-session is noticed, and drives RTT rounds.
     */
    private suspend fun housekeep(
        collectionEngine: CollectionEngine,
        radios: AndroidRadioStack,
        observerId: String,
        session: SessionContext,
    ) {
        var sinceRtt = 0L
        while (coroutineContext.isActive) {
            delay(SESSION_FLUSH_INTERVAL_MILLIS)

            val matrix = radios.capabilities()
            _capabilities.value = matrix
            collectionEngine.setDegradation(degradationFrom(matrix))
            persistSession(collectionEngine.currentSummary(), observerId, session.startedAtMillis)

            if (radios.profile.rttEnabled) {
                sinceRtt += SESSION_FLUSH_INTERVAL_MILLIS
                if (sinceRtt >= radios.profile.rttIntervalMillis) {
                    sinceRtt = 0
                    rangeRtt(collectionEngine, radios)
                }
            }
        }
    }

    /**
     * One RTT round against whatever responders the last Wi-Fi scan saw.
     *
     * Opportunistic by design. RTT is the only genuine distance measurement available, but it is
     * scarce (few responders, few capable phones) and rate-limited, so it is attempted alongside
     * ordinary collection rather than being allowed to gate it.
     */
    private suspend fun rangeRtt(collectionEngine: CollectionEngine, radios: AndroidRadioStack) {
        val targets = runCatching { radios.rtt.availableResponders() }.getOrNull().orEmpty()
        if (targets.isEmpty()) return
        val samples = runCatching { radios.rtt.range(targets) }.getOrNull().orEmpty()
        for (sample in samples) collectionEngine.submit(sample)
    }

    private fun batteryPercent(): Int? = stack?.deviceContext?.batteryPercent()

    private suspend fun persistSession(
        summary: SessionSummary?,
        observerId: String,
        startedAtMillis: Long,
    ) {
        val current = summary ?: return
        repository.upsertSession(
            SessionEntity.from(
                summary = current.copy(
                    startedAt = current.startedAt.ifBlank { Iso8601.format(startedAtMillis) },
                    batteryStartPct = current.batteryStartPct ?: batteryStartPct,
                ),
                observerId = observerId,
                startedAtEpochMs = startedAtMillis,
            ),
        )
    }

    /**
     * Turns the live capability matrix into the degradation context stamped onto every observation.
     *
     * Absent hardware is deliberately excluded. "This phone has no RTT chip" is a permanent property
     * of the observer and already travels in `observer.json`; repeating it on every row as a
     * degradation would mark a perfectly healthy session as degraded for its whole life.
     */
    private fun degradationFrom(matrix: Map<ObserverCapability, Capability>): DegradationContext {
        val missing = mutableSetOf<String>()
        val radiosOff = mutableSetOf<String>()
        for ((sensor, capability) in matrix) {
            if (!capability.supported) continue
            missing += capability.missingPermissions.map { it.substringAfterLast('.') }
            if (!capability.enabled) radiosOff += sensor.name
        }
        return DegradationContext(missing = missing, radiosOff = radiosOff)
    }

    companion object {
        /**
         * How often the session row and the capability matrix are refreshed. Thirty seconds bounds
         * the counter loss from an unexpected process death to something an operator can reconcile
         * against the observation table, while costing one small write per half minute.
         */
        const val SESSION_FLUSH_INTERVAL_MILLIS = 30_000L

        fun scanProfileOf(name: String?): ScanProfile = ScanProfile.fromNameOrDefault(name)
    }
}
