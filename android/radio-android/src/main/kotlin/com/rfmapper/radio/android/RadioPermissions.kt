package com.rfmapper.radio.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.Degradation

/**
 * Deliverable E's permission matrix, as code.
 *
 * Which permissions a sensor needs changes with the API level in ways that are easy to get subtly
 * wrong, so the decision tree lives here once and every provider asks it rather than each provider
 * carrying its own version of the rules.
 *
 * Three of those rules are worth stating because getting them wrong produces *silence* rather than
 * an error, which is the worst failure mode a collection system can have:
 *
 *  - Location permission being granted is not enough. With the device's location master switch off,
 *    `getScanResults()` returns an empty list and reports no problem at all — indistinguishable
 *    from standing somewhere with no access points.
 *  - `ACCESS_BACKGROUND_LOCATION` cannot be requested alongside foreground location. It has to be a
 *    second request after foreground is granted, and from API 30 it opens Settings rather than a
 *    dialog.
 *  - On API 31+, `BLUETOOTH_SCAN` without `neverForLocation` still requires location permission.
 *    RFMapper deliberately does not use that flag: the system would strip location-derivable data
 *    from scan results, and BLE RSSI from fixed anchors *is* the positioning evidence.
 *
 * @see <a href="../../../../../../../../../docs/05-android-permission-matrix.md">docs/05</a>
 */
object RadioPermissions {

    /** Permissions to request for Wi-Fi scanning on this API level. */
    fun wifiPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    /** Permissions to request for BLE scanning on this API level. */
    fun blePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Still required on 31+ because `neverForLocation` is deliberately not declared.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun rttPermissions(): List<String> = wifiPermissions()

    fun locationPermissions(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * The permissions for the first request of the onboarding flow.
     *
     * Background location is excluded on purpose: bundling it makes the system deny the whole
     * request on some versions, and on 30+ it is a Settings redirect rather than a dialog.
     */
    fun foregroundOnboardingPermissions(): List<String> = buildList {
        addAll(wifiPermissions())
        addAll(blePermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.distinct()

    /** The second, separate request. Null below API 29, where the concept does not exist. */
    fun backgroundLocationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }

    /** True from API 30, where the background-location grant is a Settings trip, not a dialog. */
    val backgroundLocationRequiresSettings: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { isGranted(context, it) }

    /**
     * True when the device's location master switch is on.
     *
     * Checked separately from the permission because the two fail differently: a denied permission
     * throws or returns an error, whereas a disabled location service returns empty results
     * silently.
     */
    fun isLocationServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    fun hasFeature(context: Context, feature: String): Boolean =
        context.packageManager.hasSystemFeature(feature)

    // -- capability resolution ----------------------------------------------------------------------

    fun wifiCapability(context: Context): Capability {
        if (!hasFeature(context, PackageManager.FEATURE_WIFI)) return Capability.absent()

        val missing = missing(context, wifiPermissions())
        // On 33+ either NEARBY_WIFI_DEVICES or fine location unlocks scan results, so a single
        // missing entry from the pair is not a denial.
        val blocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            !isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES) &&
                !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (blocked) return Capability.denied(missing)

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled != true) return Capability.off(Degradation.RADIO_OFF)
        if (!isLocationServiceEnabled(context)) return Capability.off(Degradation.LOCATION_SERVICES_OFF)

        return Capability.LIVE.withBackgroundStatus(context)
    }

    fun bleCapability(context: Context): Capability {
        if (!hasFeature(context, PackageManager.FEATURE_BLUETOOTH_LE)) return Capability.absent()

        val missing = missing(context, blePermissions())
        if (missing.isNotEmpty()) return Capability.denied(missing)

        val adapter = bluetoothAdapter(context) ?: return Capability.absent()
        if (!adapter.isEnabled) return Capability.off(Degradation.RADIO_OFF)
        if (!isLocationServiceEnabled(context)) return Capability.off(Degradation.LOCATION_SERVICES_OFF)

        return Capability.LIVE.withBackgroundStatus(context)
    }

    fun rttCapability(context: Context): Capability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Capability.absent()
        }
        if (!hasFeature(context, PackageManager.FEATURE_WIFI_RTT)) return Capability.absent()

        val missing = missing(context, rttPermissions())
        if (!isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return Capability.denied(missing)
        }
        if (!isLocationServiceEnabled(context)) return Capability.off(Degradation.LOCATION_SERVICES_OFF)

        return Capability.LIVE.withBackgroundStatus(context)
    }

    fun locationCapability(context: Context): Capability {
        if (!hasFeature(context, PackageManager.FEATURE_LOCATION)) return Capability.absent()

        val missing = missing(context, locationPermissions())
        if (!isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return Capability.denied(missing)
        }
        if (!isLocationServiceEnabled(context)) return Capability.off(Degradation.LOCATION_SERVICES_OFF)

        return Capability.LIVE.withBackgroundStatus(context)
    }

    /**
     * The capability matrix shown at the end of onboarding.
     *
     * Step 7 of the flow exists because the most expensive failure in this system is discovering at
     * the end of a six-hour session that BLE was switched off the whole time.
     */
    fun matrix(context: Context): Map<ObserverCapability, Capability> = mapOf(
        ObserverCapability.WIFI_SCAN to wifiCapability(context),
        ObserverCapability.WIFI_ASSOCIATION to wifiCapability(context),
        ObserverCapability.BLE to bleCapability(context),
        ObserverCapability.RTT to rttCapability(context),
        ObserverCapability.GPS to locationCapability(context),
    )

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * A live sensor that will nonetheless stop producing data when the app leaves the foreground.
     *
     * Recorded as a degradation rather than treated as a failure: foreground-only collection is
     * perfectly usable, but a coverage gap after the screen locks has to be explainable later.
     */
    private fun Capability.withBackgroundStatus(context: Context): Capability {
        val background = backgroundLocationPermission() ?: return this
        if (isGranted(context, background)) return this
        return copy(
            degradations = degradations + Degradation.BACKGROUND_PERMISSION_DENIED,
            missingPermissions = missingPermissions + background,
        )
    }
}
