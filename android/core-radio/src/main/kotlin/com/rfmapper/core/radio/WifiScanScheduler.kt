package com.rfmapper.core.radio

/**
 * Decides when the Collector may call `startScan()`.
 *
 * Android 28+ rejects more than four foreground scan requests per two minutes, and the rejection is
 * a `false` return value with no explanation — a scan that never happens, indistinguishable from a
 * scan that found nothing. A token bucket sized to the platform's own quota means the app stops
 * asking *before* it is refused, so every refusal that does occur is a genuine anomaly worth
 * recording.
 *
 * The scheduler is also why [ThrottleState.rejectedRequests] exists. A throttled request is a
 * data-quality fact: it belongs in the session summary so that a thin patch of Wi-Fi data has an
 * explanation attached to it.
 *
 * Pure and clock-injected, so the quota logic is unit-testable without waiting two minutes.
 *
 * @see <a href="../../../../../../../../../docs/05-android-permission-matrix.md">docs/05, §3</a>
 */
class WifiScanScheduler(
    private val clock: Clock = Clock.SYSTEM,
    private val quota: Quota = Quota.FOREGROUND,
    /** A result older than this describes the past, not the present. */
    private val freshnessWindowMillis: Long = DEFAULT_FRESHNESS_WINDOW_MILLIS,
) {

    /**
     * The platform's scan quota. Named after the situation rather than the API level because the
     * limit that applies depends on whether the app is in the foreground at the moment of the call,
     * not on the SDK it was built against.
     */
    data class Quota(val maxRequests: Int, val windowMillis: Long) {
        init {
            require(maxRequests > 0) { "a quota must allow at least one request" }
            require(windowMillis > 0) { "a quota window must be positive" }
        }

        companion object {
            /** Android 28+: four requests per two minutes. */
            val FOREGROUND = Quota(maxRequests = 4, windowMillis = 120_000)

            /** Android 29+ background: one request per thirty minutes. */
            val BACKGROUND = Quota(maxRequests = 1, windowMillis = 1_800_000)

            /** API 27 and below, where throttling was not enforced in practice. */
            val UNTHROTTLED = Quota(maxRequests = Int.MAX_VALUE, windowMillis = 1_000)
        }
    }

    /** Timestamps of the requests still inside the current window, oldest first. */
    private val requestTimes = ArrayDeque<Long>()
    private var rejectedRequests = 0L
    private var lastRequestMillis: Long? = null

    /**
     * Records that a scan request is being made, if the quota allows one.
     *
     * @param minIntervalMillis the profile's requested cadence. Honoured as a floor even when
     *   tokens remain, so `ENDURANCE` does not spend its whole quota in the first minute.
     * @return true when the caller should call `startScan()`.
     */
    fun tryAcquire(minIntervalMillis: Long = 0): Boolean {
        val now = clock.monotonicElapsedMillis()
        evictExpired(now)

        val tooSoon = lastRequestMillis?.let { now - it < minIntervalMillis } ?: false
        if (tooSoon || requestTimes.size >= quota.maxRequests) return false

        requestTimes.addLast(now)
        lastRequestMillis = now
        return true
    }

    /**
     * Records that `startScan()` returned false despite the quota allowing it. The platform refused
     * for a reason we cannot see, and the session summary should say so.
     */
    fun recordRejection() {
        rejectedRequests++
    }

    /** Milliseconds until another request becomes permissible, or 0 when one is permissible now. */
    fun millisUntilNextRequest(minIntervalMillis: Long = 0): Long {
        val now = clock.monotonicElapsedMillis()
        evictExpired(now)

        val quotaWait = if (requestTimes.size < quota.maxRequests) {
            0L
        } else {
            (requestTimes.first() + quota.windowMillis - now).coerceAtLeast(0L)
        }
        val cadenceWait = lastRequestMillis
            ?.let { (it + minIntervalMillis - now).coerceAtLeast(0L) }
            ?: 0L
        return maxOf(quotaWait, cadenceWait)
    }

    fun state(): ThrottleState {
        val now = clock.monotonicElapsedMillis()
        evictExpired(now)
        return ThrottleState(
            requestsInWindow = requestTimes.size,
            maxRequestsInWindow = quota.maxRequests,
            rejectedRequests = rejectedRequests,
            millisUntilNextRequest = millisUntilNextRequest(),
        )
    }

    /**
     * Classifies a scan result by age.
     *
     * Wi-Fi scan results are served from a platform cache, so the same reading can be delivered
     * repeatedly for up to half an hour on a background observer. Labelling every row lets the Lab
     * avoid counting sixty deliveries of one scan as sixty independent samples — the difference
     * between a well-supported fingerprint and a badly overconfident one.
     *
     * @param resultTimestampMillis the result's own monotonic timestamp (`ScanResult.timestamp`,
     *   microseconds, converted by the caller).
     */
    fun classify(resultTimestampMillis: Long, nowMonotonicMillis: Long = clock.monotonicElapsedMillis()): ResultAge {
        val age = (nowMonotonicMillis - resultTimestampMillis).coerceAtLeast(0L)
        return ResultAge(ageMillis = age, isFresh = age <= freshnessWindowMillis)
    }

    private fun evictExpired(now: Long) {
        val cutoff = now - quota.windowMillis
        while (requestTimes.isNotEmpty() && requestTimes.first() <= cutoff) {
            requestTimes.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_FRESHNESS_WINDOW_MILLIS = 5_000L
    }
}

data class ThrottleState(
    val requestsInWindow: Int,
    val maxRequestsInWindow: Int,
    val rejectedRequests: Long,
    val millisUntilNextRequest: Long,
) {
    val isThrottled: Boolean get() = millisUntilNextRequest > 0 || rejectedRequests > 0
}

data class ResultAge(val ageMillis: Long, val isFresh: Boolean)
