package com.rfmapper.radio.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.rfmapper.core.radio.DeviceContextProvider

/**
 * Non-radio device state, attached to observations as metadata.
 *
 * This exists to make sample-rate changes explainable. A session whose Wi-Fi rate collapses at 40%
 * battery was almost certainly throttled by the OEM's power manager, and without that context the
 * same data looks like the radio environment went quiet. Distinguishing the two after the fact is
 * impossible unless the state was recorded at the time.
 *
 * [isIgnoringBatteryOptimizations] is the single most diagnostic field here. Vendor battery
 * managers on several major OEMs will suspend a foreground service that is not exempted, producing
 * exactly the multi-hour gap that `CollectionEngine`'s gap detector flags — and knowing the app was
 * not exempt turns "unexplained gap" into "expected gap, fix the exemption".
 */
class AndroidDeviceContextProvider(
    private val context: Context,
) : DeviceContextProvider {

    override suspend fun snapshot(): Map<String, String> = buildMap {
        batteryPercent()?.let { put(KEY_BATTERY_PCT, it.toString()) }
        isCharging()?.let { put(KEY_IS_CHARGING, it.toString()) }
        put(KEY_SCREEN_ON, isScreenOn().toString())
        put(KEY_POWER_SAVE, isPowerSaveMode().toString())
        put(KEY_BATTERY_EXEMPT, isIgnoringBatteryOptimizations().toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            put(KEY_DEVICE_IDLE, isDeviceIdleMode().toString())
        }
        put(KEY_APP_FOREGROUND, isAppInForeground().toString())
    }

    fun batteryPercent(): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (manager != null) {
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) return level
        }
        // Sticky-broadcast fallback: `BATTERY_PROPERTY_CAPACITY` is unimplemented on some devices
        // and returns Integer.MIN_VALUE rather than failing.
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    fun isCharging(): Boolean? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return when (status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            -1 -> null
            else -> false
        }
    }

    fun isScreenOn(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    fun isPowerSaveMode(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { power.isPowerSaveMode }.getOrDefault(false)
    }

    fun isDeviceIdleMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { power.isDeviceIdleMode }.getOrDefault(false)
    }

    /**
     * True when the app is exempt from Doze and App Standby.
     *
     * Surfaced in onboarding as a recommendation rather than a requirement: the exemption is
     * genuinely needed for multi-hour unattended sessions, but demanding it up front for a
     * ten-minute walk test would be disproportionate.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    private fun isAppInForeground(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val processes = runCatching { manager.runningAppProcesses }.getOrNull() ?: return false
        return processes.any {
            it.processName == context.packageName &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }
    }

    private companion object {
        const val KEY_BATTERY_PCT = "battery_pct"
        const val KEY_IS_CHARGING = "is_charging"
        const val KEY_SCREEN_ON = "screen_on"
        const val KEY_POWER_SAVE = "power_save_mode"
        const val KEY_DEVICE_IDLE = "device_idle_mode"
        const val KEY_BATTERY_EXEMPT = "ignoring_battery_optimizations"
        const val KEY_APP_FOREGROUND = "app_foreground"
    }
}
