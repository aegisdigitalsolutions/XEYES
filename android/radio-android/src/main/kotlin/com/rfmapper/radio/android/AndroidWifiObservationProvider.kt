package com.rfmapper.radio.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.RadioIdentifierNormalizer
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.Clock
import com.rfmapper.core.radio.RadioSample
import com.rfmapper.core.radio.ScanProfile
import com.rfmapper.core.radio.ThrottleState
import com.rfmapper.core.radio.WifiObservationProvider
import com.rfmapper.core.radio.WifiScanScheduler
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wi-Fi scanning on Android.
 *
 * The design decision that matters most here is that collection is **broadcast-driven, not
 * poll-driven**. The provider registers for `SCAN_RESULTS_AVAILABLE_ACTION` and harvests every scan
 * the platform performs — including scans triggered by other apps and by the system's own
 * connectivity logic — and only asks for its own scan when the throttle budget allows one. In
 * practice this yields a *higher* sample rate than calling `startScan()` as often as possible,
 * because from Android 28 the fifth request in two minutes is simply refused.
 *
 * Every result is labelled `FRESH` or `CACHED` from its own timestamp. This is not bookkeeping: a
 * background observer on Android 29+ can legitimately be handed the same cached scan for half an
 * hour, and a fingerprint built from sixty deliveries of one scan is not a sixty-sample
 * fingerprint. Labelling is what lets the Positioning Lab tell the difference.
 *
 * @see <a href="../../../../../../../../../docs/05-android-permission-matrix.md">docs/05, §3</a>
 */
class AndroidWifiObservationProvider(
    private val context: Context,
    private val profile: ScanProfile = ScanProfile.DEFAULT,
    private val clock: Clock = AndroidClock,
    private val scheduler: WifiScanScheduler = WifiScanScheduler(clock),
    /** Notified whenever the throttle picture changes, so the session summary can record it. */
    private val onThrottleState: (ThrottleState) -> Unit = {},
) : WifiObservationProvider {

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override suspend fun capability(): Capability = RadioPermissions.wifiCapability(context)

    @SuppressLint("MissingPermission")
    override fun samples(): Flow<RadioSample> = callbackFlow {
        val manager = wifiManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                // EXTRA_RESULTS_UPDATED distinguishes a genuine new scan from a stale-cache
                // broadcast. Absent on older versions, where every broadcast is treated as real.
                val updated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true
                } else {
                    true
                }
                val trigger = if (updated) TRIGGER_BROADCAST else TRIGGER_CACHE
                for (sample in readResults(manager, trigger)) {
                    trySend(sample)
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Deliver whatever the platform already has, so a session's first observation does not
        // wait for the next scan somewhere else in the system to complete.
        for (sample in readResults(manager, TRIGGER_INITIAL_CACHE)) {
            trySend(sample)
        }

        val requester = launch {
            while (isActive) {
                if (scheduler.tryAcquire(profile.wifiScanIntervalMillis)) {
                    @Suppress("DEPRECATION")
                    val accepted = runCatching { manager.startScan() }.getOrDefault(false)
                    // A refusal despite an available token means the platform declined for a
                    // reason we cannot see. Counted, because it explains a thin patch of data.
                    if (!accepted) scheduler.recordRejection()
                    onThrottleState(scheduler.state())
                }
                val wait = scheduler.millisUntilNextRequest(profile.wifiScanIntervalMillis)
                delay(wait.coerceAtLeast(MIN_POLL_DELAY_MILLIS))
            }
        }

        awaitClose {
            requester.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun associationSample(): RadioSample? {
        val manager = wifiManager ?: return null
        if (!RadioPermissions.wifiCapability(context).live) return null

        @Suppress("DEPRECATION")
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        val bssid = RadioIdentifierNormalizer.normalizeMac(info.bssid) ?: return null

        val monotonic = clock.monotonicElapsedMillis()
        return RadioSample(
            sensorType = SensorType.WIFI_ASSOCIATION,
            identifier = bssid,
            identifierType = IdentifierType.WIFI_BSSID,
            wallClockMillis = clock.wallClockMillis(),
            monotonicElapsedMillis = monotonic,
            rssi = info.rssi,
            frequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                info.frequency
            } else {
                null
            },
            ssid = RadioIdentifierNormalizer.normalizeSsid(info.ssid),
            bssid = bssid,
            freshness = ResultFreshness.FRESH,
            resultAgeMillis = 0,
            metadata = buildMap {
                put(MetadataKeys.IS_CONNECTED, "true")
                put(MetadataKeys.LINK_SPEED_MBPS, info.linkSpeed.toString())
                put(MetadataKeys.SCAN_TRIGGER, TRIGGER_ASSOCIATION)
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun readResults(manager: WifiManager, trigger: String): List<RadioSample> {
        val capability = RadioPermissions.wifiCapability(context)
        if (!capability.live) return emptyList()

        val results = runCatching { manager.scanResults }.getOrNull() ?: return emptyList()
        val nowMonotonic = clock.monotonicElapsedMillis()
        val nowWall = clock.wallClockMillis()

        return results.mapNotNull { result -> toSample(result, trigger, nowMonotonic, nowWall) }
    }

    private fun toSample(
        result: ScanResult,
        trigger: String,
        nowMonotonicMillis: Long,
        nowWallMillis: Long,
    ): RadioSample? {
        val bssid = RadioIdentifierNormalizer.normalizeMac(result.BSSID) ?: return null

        // `ScanResult.timestamp` is microseconds on the monotonic clock, so the wall-clock instant
        // of measurement is derived by subtracting the measured age rather than by reading the
        // clock now. A result delivered five minutes late must not be timestamped as the present.
        val resultMonotonicMillis = result.timestamp / 1_000
        val age = scheduler.classify(resultMonotonicMillis, nowMonotonicMillis)
        val measuredWallMillis = (nowWallMillis - age.ageMillis).coerceAtLeast(1L)

        val metadata = buildMap {
            put(MetadataKeys.SCAN_TRIGGER, trigger)
            result.capabilities?.takeIf { it.isNotBlank() }?.let { put(MetadataKeys.CAPABILITIES, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                put(MetadataKeys.CHANNEL_WIDTH_MHZ, channelWidthMhz(result.channelWidth).toString())
                if (result.centerFreq0 != 0) put(MetadataKeys.CENTER_FREQ0_MHZ, result.centerFreq0.toString())
                if (result.centerFreq1 != 0) put(MetadataKeys.CENTER_FREQ1_MHZ, result.centerFreq1.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                put(MetadataKeys.WIFI_STANDARD, result.wifiStandard.toString())
                put(MetadataKeys.RTT_IS_80211MC, result.is80211mcResponder.toString())
            }
        }

        return RadioSample(
            sensorType = SensorType.WIFI_SCAN,
            identifier = bssid,
            identifierType = IdentifierType.WIFI_BSSID,
            wallClockMillis = measuredWallMillis,
            monotonicElapsedMillis = resultMonotonicMillis,
            rssi = result.level,
            frequencyMhz = result.frequency,
            channel = RadioIdentifierNormalizer.channelForFrequency(result.frequency),
            ssid = RadioIdentifierNormalizer.normalizeSsid(result.SSID),
            bssid = bssid,
            freshness = if (age.isFresh) ResultFreshness.FRESH else ResultFreshness.CACHED,
            resultAgeMillis = age.ageMillis,
            metadata = metadata,
        )
    }

    /**
     * The platform's current scan cache, unconverted.
     *
     * Exposed for the RTT provider alone, which needs the `ScanResult` object itself rather than a
     * BSSID: `RangingRequest` carries the peer's channel and bandwidth, so a MAC address is not
     * enough to range against. Nothing else should read raw platform types — that is the boundary
     * this module exists to hold.
     */
    @SuppressLint("MissingPermission")
    fun rawScanResults(): List<ScanResult> {
        val manager = wifiManager ?: return emptyList()
        if (!RadioPermissions.wifiCapability(context).live) return emptyList()
        return runCatching { manager.scanResults }.getOrNull().orEmpty()
    }

    /** Responders the last scan saw, for the caller to intersect with the site registry. */
    fun rttResponders(): List<com.rfmapper.core.radio.RttTarget> =
        rawScanResults()
            .filter { runCatching { it.is80211mcResponder }.getOrDefault(false) }
            .mapNotNull { result ->
                RadioIdentifierNormalizer.normalizeMac(result.BSSID)?.let { bssid ->
                    com.rfmapper.core.radio.RttTarget(
                        bssid = bssid,
                        frequencyMhz = result.frequency,
                        supports80211mc = true,
                    )
                }
            }

    private companion object {
        const val TRIGGER_BROADCAST = "SCAN_RESULTS_AVAILABLE"
        const val TRIGGER_CACHE = "SCAN_RESULTS_CACHED"
        const val TRIGGER_INITIAL_CACHE = "SESSION_START_CACHE"
        const val TRIGGER_ASSOCIATION = "CONNECTION_INFO"

        /** Keeps the request loop from spinning when the scheduler says "now". */
        const val MIN_POLL_DELAY_MILLIS = 1_000L

        fun channelWidthMhz(channelWidth: Int): Int = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            else -> 0
        }
    }
}

/** The platform clocks. `elapsedRealtime` keeps counting in deep sleep, unlike `uptimeMillis`. */
object AndroidClock : Clock {
    override fun wallClockMillis(): Long = System.currentTimeMillis()
    override fun monotonicElapsedMillis(): Long = SystemClock.elapsedRealtime()
}
