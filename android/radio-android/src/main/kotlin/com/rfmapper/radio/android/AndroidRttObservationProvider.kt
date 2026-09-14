package com.rfmapper.radio.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.RadioIdentifierNormalizer
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.Clock
import com.rfmapper.core.radio.RadioSample
import com.rfmapper.core.radio.RttObservationProvider
import com.rfmapper.core.radio.RttTarget
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wi-Fi RTT (802.11mc FTM) ranging.
 *
 * RTT is the only sensor in the system that produces a *measured distance* rather than a signal
 * strength that a model must turn into one, which makes it disproportionately valuable: a handful of
 * RTT ranges pins down a position that a hundred RSSI readings can only suggest. It is also the
 * scarcest — most access points are not responders, few phones have the hardware, and the platform
 * caps ranging at [RangingRequest.getMaxPeers] peers per request.
 *
 * Ranging is driven off the Wi-Fi provider's scan cache rather than off BSSIDs alone, because
 * `RangingRequest` needs the `ScanResult` itself: the request carries the peer's channel and
 * bandwidth, not just its address. [scanResultSource] is supplied by the provider that already
 * holds recent results, so discovering responders costs no scan quota — quota the Wi-Fi sample rate
 * needs more than RTT does.
 *
 * Three properties are enforced here rather than left to the caller:
 *
 *  - **Failed ranges are recorded, not discarded.** A responder that was asked and did not answer is
 *    evidence about the environment (obstruction, range, contention). It becomes a sample with no
 *    `rtt_distance_mm` and `rtt_status` set, so the Lab can tell "not attempted" from "attempted and
 *    failed" — the difference between a gap and a measurement.
 *  - **`rtt_distance_mm` is never synthesised.** The canonical schema's first invariant is that RTT
 *    fields appear only on genuinely-ranged records, because an RSSI-derived distance travelling in
 *    an RTT field would give a path-loss guess the authority of a measurement.
 *  - **Requests are split to the platform's peer limit.** Exceeding it throws, and an exception here
 *    would abort a whole ranging round rather than cost one peer.
 *
 * @see <a href="../../../../../../../../../docs/05-android-permission-matrix.md">docs/05, §2</a>
 */
class AndroidRttObservationProvider(
    private val context: Context,
    private val clock: Clock = AndroidClock,
) : RttObservationProvider {

    /**
     * The last scan's results, supplied by the Wi-Fi provider. Held as a function rather than a
     * snapshot so that each ranging round sees the freshest scan the platform has.
     */
    var scanResultSource: () -> List<ScanResult> = { emptyList() }

    private val rttManager: WifiRttManager?
        @RequiresApi(Build.VERSION_CODES.P)
        get() = context.applicationContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE)
            as? WifiRttManager

    override suspend fun capability(): Capability = RadioPermissions.rttCapability(context)

    override suspend fun availableResponders(): List<RttTarget> {
        if (!RadioPermissions.rttCapability(context).live) return emptyList()
        return responders().mapNotNull { result ->
            RadioIdentifierNormalizer.normalizeMac(result.BSSID)?.let { bssid ->
                RttTarget(bssid = bssid, frequencyMhz = result.frequency, supports80211mc = true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun range(targets: List<RttTarget>): List<RadioSample> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        if (targets.isEmpty()) return emptyList()
        if (!RadioPermissions.rttCapability(context).live) return emptyList()

        val manager = rttManager ?: return emptyList()
        if (!runCatching { manager.isAvailable }.getOrDefault(false)) return emptyList()

        // Only responders the platform has actually seen can be ranged. A registry entry the site
        // model *claims* is an RTT anchor but which no scan has confirmed is skipped rather than
        // attempted: the request would fail for a reason that says nothing about the environment.
        val wanted = targets.filter { it.supports80211mc }.map { it.bssid }.toSet()
        val rangeable = responders().filter { result ->
            RadioIdentifierNormalizer.normalizeMac(result.BSSID) in wanted
        }.distinctBy { it.BSSID }

        if (rangeable.isEmpty()) return emptyList()

        val maxPeers = runCatching { RangingRequest.getMaxPeers() }.getOrDefault(DEFAULT_MAX_PEERS)

        return rangeable
            .chunked(maxPeers.coerceAtLeast(1))
            .flatMap { chunk -> rangeChunk(manager, chunk) }
    }

    /** Responders from the most recent scan. Empty when the platform cannot report any. */
    private fun responders(): List<ScanResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return scanResultSource().filter { result ->
            runCatching { result.is80211mcResponder }.getOrDefault(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private suspend fun rangeChunk(
        manager: WifiRttManager,
        chunk: List<ScanResult>,
    ): List<RadioSample> {
        val request = buildRequest(chunk) ?: return emptyList()

        val results: List<RangingResult> = suspendCancellableCoroutine { continuation ->
            val callback = object : RangingResultCallback() {
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    if (continuation.isActive) continuation.resume(results.toList())
                }

                override fun onRangingFailure(code: Int) {
                    // The whole round failed, which says something about the radio rather than
                    // about any one responder. Reported as an empty result: the session's RTT
                    // count stays at zero and the capability check explains why.
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }

            val started = runCatching {
                manager.startRanging(request, { command -> command.run() }, callback)
            }
            if (started.isFailure && continuation.isActive) continuation.resume(emptyList())
        }

        val nowWall = clock.wallClockMillis()
        val nowMonotonic = clock.monotonicElapsedMillis()
        return results.mapNotNull { result -> toSample(result, nowWall, nowMonotonic) }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildRequest(chunk: List<ScanResult>): RangingRequest? {
        val builder = RangingRequest.Builder()
        var added = 0
        for (result in chunk) {
            runCatching { builder.addAccessPoint(result) }.onSuccess { added++ }
        }
        if (added == 0) return null
        return runCatching { builder.build() }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun toSample(
        result: RangingResult,
        nowWallMillis: Long,
        nowMonotonicMillis: Long,
    ): RadioSample? {
        val bssid = RadioIdentifierNormalizer.normalizeMac(result.macAddress?.toString())
            ?: return null

        val succeeded = result.status == RangingResult.STATUS_SUCCESS

        // `rangingTimestampMillis` is on the monotonic clock and only meaningful on success; a
        // failed range has no instant of measurement beyond "now".
        val monotonic = if (succeeded) {
            runCatching { result.rangingTimestampMillis }.getOrDefault(nowMonotonicMillis)
        } else {
            nowMonotonicMillis
        }
        val age = (nowMonotonicMillis - monotonic).coerceIn(0, MAX_PLAUSIBLE_AGE_MILLIS)

        val metadata = buildMap {
            put(MetadataKeys.RTT_STATUS, statusName(result.status))
            put(MetadataKeys.RTT_IS_80211MC, "true")
            if (succeeded) {
                put(MetadataKeys.RTT_NUM_ATTEMPTED, result.numAttemptedMeasurements.toString())
                put(MetadataKeys.RTT_NUM_SUCCESSFUL, result.numSuccessfulMeasurements.toString())
                put(MetadataKeys.RTT_RSSI, result.rssi.toString())
            }
        }

        return RadioSample(
            sensorType = SensorType.RTT,
            identifier = bssid,
            identifierType = IdentifierType.WIFI_BSSID,
            wallClockMillis = (nowWallMillis - age).coerceAtLeast(1L),
            monotonicElapsedMillis = monotonic,
            // The responder's RSSI during ranging, which is a different (and usually better)
            // measurement than a scan RSSI because the exchange is directed.
            rssi = if (succeeded) plausibleRssi(result.rssi) else null,
            bssid = bssid,
            // Only ever set from a genuine range. Schema invariant 1.
            rttDistanceMm = if (succeeded) result.distanceMm else null,
            rttStddevMm = if (succeeded) result.distanceStdDevMm.coerceAtLeast(0) else null,
            freshness = ResultFreshness.FRESH,
            resultAgeMillis = age,
            metadata = metadata,
        )
    }

    /** RTT RSSI is occasionally reported as a sentinel outside the schema's dBm range. */
    private fun plausibleRssi(rssi: Int): Int? = rssi.takeIf { it in -127..20 }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun statusName(status: Int): String = when (status) {
        RangingResult.STATUS_SUCCESS -> "SUCCESS"
        RangingResult.STATUS_FAIL -> "FAIL"
        RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC -> "UNSUPPORTED_RESPONDER"
        else -> "UNKNOWN_$status"
    }

    private companion object {
        /** Android's documented floor for peers per request, used if the API call fails. */
        const val DEFAULT_MAX_PEERS = 4
        const val MAX_PLAUSIBLE_AGE_MILLIS = 60_000L
    }
}
