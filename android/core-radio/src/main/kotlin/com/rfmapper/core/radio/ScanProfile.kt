package com.rfmapper.core.radio

/**
 * Collection cadence for a session. Recorded on the session so that a sampling rate is never a
 * mystery after the fact: a sparse afternoon should be attributable to `ENDURANCE` rather than
 * investigated as data loss.
 *
 * The Wi-Fi numbers are bounded by the platform, not by preference. Android 28+ allows four
 * `startScan()` calls per two minutes in the foreground, so even `AGGRESSIVE` cannot request more
 * than that without being silently rejected — which is why the Collector's real Wi-Fi sample rate
 * comes mostly from listening to scans other apps trigger, not from its own requests.
 */
enum class ScanProfile(
    val wifiScanIntervalMillis: Long,
    val bleWindowMillis: Long,
    val bleIdleMillis: Long,
    val locationIntervalMillis: Long,
    val rttIntervalMillis: Long,
) {
    /** Bench testing, walk tests and surveys: the highest rate the platform permits. */
    AGGRESSIVE(
        wifiScanIntervalMillis = 30_000,
        bleWindowMillis = 0,
        bleIdleMillis = 0,
        locationIntervalMillis = 10_000,
        rttIntervalMillis = 60_000,
    ),

    /** The default. A full working day on a mid-range phone with the screen off. */
    BALANCED(
        wifiScanIntervalMillis = 45_000,
        bleWindowMillis = 8_000,
        bleIdleMillis = 22_000,
        locationIntervalMillis = 60_000,
        rttIntervalMillis = 300_000,
    ),

    /** Multi-day unattended deployment, or a phone that must survive the night. */
    ENDURANCE(
        wifiScanIntervalMillis = 120_000,
        bleWindowMillis = 6_000,
        bleIdleMillis = 114_000,
        locationIntervalMillis = 300_000,
        rttIntervalMillis = 0,
    ),
    ;

    /** True when BLE scanning runs continuously rather than in duty cycles. */
    val bleIsContinuous: Boolean get() = bleIdleMillis == 0L

    val rttEnabled: Boolean get() = rttIntervalMillis > 0

    companion object {
        val DEFAULT = BALANCED

        fun fromNameOrDefault(value: String?): ScanProfile =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
