package com.rfmapper.core.radio

import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where accepted observations go. The engine never knows it is a database. */
fun interface ObservationWriter {
    /** Persists a batch. Must be atomic per batch, so a crash cannot leave half a batch written. */
    suspend fun write(batch: List<Observation>): Unit
}

/**
 * Runs one collection session: consumes provider samples, stamps them through
 * [ObservationFactory], batches them to an [ObservationWriter], and maintains the counters the
 * dashboard and the session summary are built from.
 *
 * Three behaviours here are deliberate and load-bearing:
 *
 * 1. **Batched writes.** A dense BLE environment produces hundreds of samples per second; one
 *    transaction each would make the database the bottleneck and the battery the victim. Batches
 *    flush on size or on a deadline, whichever comes first, so a quiet environment still persists
 *    promptly instead of holding data in RAM until the process dies.
 * 2. **Back-pressure that drops the cheapest sample and counts it.** When the writer cannot keep up,
 *    the oldest *BLE* sample is sacrificed first: advertisements arrive at hundreds per second and
 *    one more is worth little, whereas a Wi-Fi scan arrives at the platform's scan cadence and is
 *    effectively irreplaceable. Whatever is dropped is counted into the session summary, because
 *    silently losing samples leaves a coverage gap indistinguishable from a quiet radio
 *    environment.
 * 3. **A gap detector.** If no sample is written for longer than [GAP_THRESHOLD_MILLIS] while the
 *    session is supposedly running, the summary flags a suspected service kill. OEM battery
 *    managers do this routinely, and an unexplained six-hour hole in the data is worth far less
 *    than a five-hour hole with a note saying where it went.
 */
class CollectionEngine(
    private val factory: ObservationFactory,
    private val writer: ObservationWriter,
    private val clock: Clock = Clock.SYSTEM,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val batchTimeoutMillis: Long = DEFAULT_BATCH_TIMEOUT_MILLIS,
    private val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {

    private val counters = SessionCounters()
    private val mutex = Mutex()
    private val pending = ArrayList<Observation>(batchSize)

    /**
     * Mirrors `pending.size` for the status snapshot. [publishStatus] is called from contexts that
     * do not hold [mutex], and reading an `ArrayList`'s size concurrently with a write to it is not
     * safe even for a display counter.
     */
    private val pendingCount = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var lastFlushMillis = 0L

    private val _status = MutableStateFlow(CollectionStatus.idle())
    val status: StateFlow<CollectionStatus> = _status.asStateFlow()

    private val _recent = MutableSharedFlow<Observation>(
        replay = RECENT_REPLAY,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The most recent observations, for the live dashboard. Dropping is correct here. */
    val recent: Flow<Observation> = _recent.asSharedFlow()

    @Volatile
    private var session: SessionContext? = null

    @Volatile
    private var survey: SurveyContext? = null

    @Volatile
    private var degradation: DegradationContext = DegradationContext.NONE

    @Volatile
    private var throttle: ThrottleState? = null

    fun start(session: SessionContext) {
        this.session = session
        counters.reset(session.startedAtMillis)
        lastFlushMillis = clock.monotonicElapsedMillis()
        publishStatus()
    }

    fun setDegradation(degradation: DegradationContext) {
        this.degradation = degradation
        publishStatus()
    }

    /**
     * Publishes the Wi-Fi scheduler's current throttle state, so the session summary can say how
     * many scan requests the platform refused rather than leaving a sparse hour unexplained.
     */
    fun setThrottleState(state: ThrottleState) {
        throttle = state
        if (state.isThrottled != degradation.throttled) {
            degradation = degradation.copy(throttled = state.isThrottled)
        }
        publishStatus()
    }

    fun beginSurvey(survey: SurveyContext) {
        this.survey = survey
        publishStatus()
    }

    fun endSurvey() {
        survey = null
        publishStatus()
    }

    /**
     * Collects one provider's samples for as long as [scope] lives.
     *
     * Each provider is consumed in its own coroutine rather than through a merged flow, so a
     * misbehaving provider — one that throws, or stalls — cannot silence the others.
     */
    fun collect(scope: CoroutineScope, samples: Flow<RadioSample>) {
        scope.launch {
            samples.collect { submit(it) }
        }
    }

    /** Offers one sample to the session. Returns false when it was dropped by back-pressure. */
    suspend fun submit(sample: RadioSample): Boolean {
        val active = session ?: return false

        val observation = try {
            factory.create(
                sample = sample,
                session = active,
                survey = survey,
                degradation = degradation,
                context = emptyMap(),
            )
        } catch (invalid: IllegalArgumentException) {
            // A sample the radio gave us that cannot become a valid observation. Counted, not
            // crashed on and not coerced into something plausible: a fabricated row would be worse
            // than a known-lost one.
            counters.recordRejected(invalid.message)
            publishStatus()
            return false
        }

        val accepted = mutex.withLock { admit(observation) }

        if (accepted) {
            _recent.tryEmit(observation)
            maybeFlush()
        }
        publishStatus()
        return accepted
    }

    /**
     * Buffers [observation], making room by discarding a BLE sample if the buffer is full and the
     * new sample is worth more than one. Must be called holding [mutex].
     *
     * @return false when the new observation itself was dropped.
     */
    private fun admit(observation: Observation): Boolean {
        if (pending.size >= bufferCapacity) {
            val sacrificeable = if (observation.sensorType == SensorType.BLE) {
                -1
            } else {
                pending.indexOfFirst { it.sensorType == SensorType.BLE }
            }
            if (sacrificeable < 0) {
                counters.recordDropped()
                return false
            }
            pending.removeAt(sacrificeable)
            counters.recordDropped()
        }

        pending += observation
        pendingCount.set(pending.size)
        counters.record(observation)
        return true
    }

    /** Writes any buffered observations. Called on flush deadlines, on stop, and before export. */
    suspend fun flush() {
        val batch = mutex.withLock {
            if (pending.isEmpty()) {
                emptyList()
            } else {
                val copy = ArrayList(pending)
                pending.clear()
                pendingCount.set(0)
                copy
            }
        }
        if (batch.isEmpty()) return

        try {
            writer.write(batch)
            lastFlushMillis = clock.monotonicElapsedMillis()
            counters.recordWritten(batch.size, clock.wallClockMillis())
        } catch (failure: Exception) {
            // Put the batch back so a transient storage failure costs a retry rather than the data,
            // unless the buffer has since filled — in which case the loss is at least counted.
            mutex.withLock {
                val room = bufferCapacity - pending.size
                if (room >= batch.size) {
                    pending.addAll(0, batch)
                } else {
                    counters.recordDropped(batch.size - room)
                    pending.addAll(0, batch.takeLast(room))
                }
                pendingCount.set(pending.size)
            }
            counters.recordWriteFailure(failure.message)
            throw failure
        } finally {
            publishStatus()
        }
    }

    /** Ends the session, flushing whatever remains, and returns its summary. */
    suspend fun stop(): SessionSummary {
        val active = session ?: error("stop() called without a running session")
        flush()
        val summary = summaryFor(active, endedAtMillis = clock.wallClockMillis())
        session = null
        survey = null
        publishStatus()
        return summary
    }

    /** The live summary, for a dashboard or a notification, without ending the session. */
    fun currentSummary(): SessionSummary? = session?.let { summaryFor(it, endedAtMillis = null) }

    private fun summaryFor(active: SessionContext, endedAtMillis: Long?) = counters.summarise(
        session = active,
        degradation = degradation,
        throttledRequests = throttle?.rejectedRequests ?: 0,
        suspectedServiceKill = hasSuspectedGap(),
        endedAtMillis = endedAtMillis,
    )

    private suspend fun maybeFlush() {
        val size = pendingCount.get()
        val overdue = clock.monotonicElapsedMillis() - lastFlushMillis >= batchTimeoutMillis
        if (size >= batchSize || (size > 0 && overdue)) flush()
    }

    /**
     * True when the session is nominally running but nothing has been written for long enough that
     * the most likely explanation is the process having been suspended or killed.
     */
    private fun hasSuspectedGap(): Boolean =
        session != null &&
            counters.hasEverWritten &&
            clock.monotonicElapsedMillis() - lastFlushMillis > GAP_THRESHOLD_MILLIS

    private fun publishStatus() {
        val active = session
        _status.value = CollectionStatus(
            isRunning = active != null,
            sessionId = active?.sessionId,
            scanProfile = active?.scanProfile,
            startedAtMillis = active?.startedAtMillis,
            counts = counters.snapshot(),
            pendingWrites = pendingCount.get(),
            lastObservationAtMillis = counters.lastObservationWallMillis,
            suspectedGap = hasSuspectedGap(),
            throttle = throttle,
            degradation = degradation,
            surveyPointId = survey?.surveyPointId,
        )
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 200
        const val DEFAULT_BATCH_TIMEOUT_MILLIS = 5_000L

        /**
         * Roughly ten seconds of a very dense BLE environment. Large enough to ride out a slow
         * write, small enough that a stalled writer cannot exhaust memory.
         */
        const val DEFAULT_BUFFER_CAPACITY = 5_000

        const val GAP_THRESHOLD_MILLIS = 10 * 60 * 1000L

        private const val RECENT_REPLAY = 50
    }
}

/** What the dashboard and the foreground notification display. */
data class CollectionStatus(
    val isRunning: Boolean,
    val sessionId: String?,
    val scanProfile: ScanProfile?,
    val startedAtMillis: Long?,
    val counts: SensorCounts,
    val pendingWrites: Int,
    val lastObservationAtMillis: Long?,
    val suspectedGap: Boolean,
    val throttle: ThrottleState?,
    val degradation: DegradationContext,
    val surveyPointId: String?,
) {
    val isSurveying: Boolean get() = surveyPointId != null

    fun elapsedMillis(nowMillis: Long): Long? = startedAtMillis?.let { nowMillis - it }

    fun lastObservationAgeMillis(nowMillis: Long): Long? =
        lastObservationAtMillis?.let { nowMillis - it }

    companion object {
        fun idle() = CollectionStatus(
            isRunning = false,
            sessionId = null,
            scanProfile = null,
            startedAtMillis = null,
            counts = SensorCounts(),
            pendingWrites = 0,
            lastObservationAtMillis = null,
            suspectedGap = false,
            throttle = null,
            degradation = DegradationContext.NONE,
            surveyPointId = null,
        )
    }
}

data class SensorCounts(
    val total: Long = 0,
    val wifi: Long = 0,
    val ble: Long = 0,
    val rtt: Long = 0,
    val gps: Long = 0,
    val groundTruth: Long = 0,
    val dropped: Long = 0,
    val rejected: Long = 0,
    val distinctIdentifiers: Int = 0,
)

/**
 * Session accounting. Separate from the engine so the counters can be asserted directly, and so
 * that what ends up in `SessionSummary` is demonstrably the same thing the dashboard showed.
 */
internal class SessionCounters {

    private var startedAtMillis = 0L
    private var total = 0L
    private var wifi = 0L
    private var ble = 0L
    private var rtt = 0L
    private var gps = 0L
    private var groundTruth = 0L
    private var dropped = 0L
    private var rejected = 0L
    private var written = 0L
    private val identifiers = HashSet<String>()
    private val failures = LinkedHashSet<String>()

    var lastObservationWallMillis: Long? = null
        private set

    val hasEverWritten: Boolean get() = written > 0

    fun reset(startedAtMillis: Long) {
        this.startedAtMillis = startedAtMillis
        total = 0; wifi = 0; ble = 0; rtt = 0; gps = 0
        groundTruth = 0; dropped = 0; rejected = 0; written = 0
        identifiers.clear()
        failures.clear()
        lastObservationWallMillis = null
    }

    fun record(observation: Observation) {
        total++
        when (observation.sensorType) {
            SensorType.WIFI_SCAN, SensorType.WIFI_ASSOCIATION -> wifi++
            SensorType.BLE -> ble++
            SensorType.RTT -> rtt++
            SensorType.GPS -> gps++
            else -> Unit
        }
        if (observation.sampleKind == com.rfmapper.core.model.SampleKind.GROUND_TRUTH) groundTruth++

        // Bounded so a long session in a churning BLE environment cannot grow this set without
        // limit. Past the cap the dashboard's distinct count becomes a floor, which is honest
        // enough for a live counter and costs nothing downstream: the Lab counts identifiers from
        // the data, not from this.
        if (identifiers.size < DISTINCT_IDENTIFIER_CAP) identifiers += observation.radioIdentifier

        lastObservationWallMillis = observation.timestampEpochMillis
    }

    fun recordDropped(count: Int = 1) {
        dropped += count
    }

    fun recordRejected(reason: String?) {
        rejected++
        reason?.let { failures += "rejected_sample: $it" }
    }

    fun recordWritten(count: Int, atWallMillis: Long) {
        written += count
        lastObservationWallMillis = lastObservationWallMillis ?: atWallMillis
    }

    fun recordWriteFailure(reason: String?) {
        failures += "write_failure: ${reason ?: "unknown"}"
    }

    fun snapshot() = SensorCounts(
        total = total,
        wifi = wifi,
        ble = ble,
        rtt = rtt,
        gps = gps,
        groundTruth = groundTruth,
        dropped = dropped,
        rejected = rejected,
        distinctIdentifiers = identifiers.size,
    )

    fun summarise(
        session: SessionContext,
        degradation: DegradationContext,
        throttledRequests: Long,
        suspectedServiceKill: Boolean,
        endedAtMillis: Long?,
    ): SessionSummary {
        val degradations = buildList {
            addAll(degradation.missing.sorted().map { "missing_permission: $it" })
            addAll(degradation.radiosOff.sorted().map { "radio_off: $it" })
            if (degradation.throttled) add("wifi_scan_throttled")
            if (dropped > 0) add("dropped_samples: $dropped")
            if (rejected > 0) add("rejected_samples: $rejected")
            addAll(failures)
        }

        return SessionSummary(
            sessionId = session.sessionId,
            startedAt = Iso8601.format(if (startedAtMillis > 0) startedAtMillis else session.startedAtMillis),
            endedAt = endedAtMillis?.let(Iso8601::format),
            scanProfile = session.scanProfile.name,
            buildingId = session.place.buildingId,
            zoneId = session.place.zoneId,
            observationCount = total,
            wifiCount = wifi,
            bleCount = ble,
            rttCount = rtt,
            gpsCount = gps,
            droppedSamples = dropped,
            throttledScanRequests = throttledRequests,
            backgroundDenied = degradation.missing.any { it.contains("BACKGROUND_LOCATION") },
            suspectedServiceKill = suspectedServiceKill,
            degradations = degradations,
        )
    }

    private companion object {
        const val DISTINCT_IDENTIFIER_CAP = 50_000
    }
}
