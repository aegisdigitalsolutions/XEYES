package com.rfmapper.radio.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.radio.Degradation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission matrix is the part of the Collector most likely to fail *silently*: a denied
 * permission or a disabled master switch yields empty scan results rather than an error, which is
 * indistinguishable in the data from standing somewhere with no access points. These tests pin the
 * distinctions that let the dashboard say which of the two it is.
 */
@RunWith(RobolectricTestRunner::class)
class RadioPermissionsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun grant(vararg permissions: String) {
        shadowOf(app).grantPermissions(*permissions)
    }

    private fun deny(vararg permissions: String) {
        shadowOf(app).denyPermissions(*permissions)
    }

    private fun enableLocationService(enabled: Boolean) {
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(enabled)
    }

    private fun enableWifi(enabled: Boolean) {
        @Suppress("DEPRECATION")
        app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled = enabled
    }

    private fun haveFeature(feature: String, present: Boolean) {
        val shadow = shadowOf(app.packageManager)
        if (present) shadow.setSystemFeature(feature, true) else shadow.setSystemFeature(feature, false)
    }

    // -- the three flags must not collapse into one "unavailable" ----------------------------------

    @Test
    fun `absent hardware is not a permission problem`() {
        haveFeature(PackageManager.FEATURE_WIFI_RTT, false)

        val capability = RadioPermissions.rttCapability(app)

        assertFalse(capability.supported)
        assertFalse(capability.live)
        assertFalse(
            capability.fixableByAdministrator,
            "no amount of granting permissions adds an RTT chip",
        )
        assertEquals(setOf(Degradation.HARDWARE_ABSENT), capability.degradations)
    }

    @Test
    fun `a denied permission is reported as fixable and names what is missing`() {
        haveFeature(PackageManager.FEATURE_WIFI, true)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val capability = RadioPermissions.wifiCapability(app)

        assertFalse(capability.live)
        assertTrue(capability.fixableByAdministrator)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in capability.missingPermissions)
    }

    /**
     * The trap this whole object exists for. With the permission granted and the master switch off,
     * `getScanResults()` returns an empty list and reports no problem at all.
     */
    @Test
    fun `location services off is distinguished from a denied permission`() {
        haveFeature(PackageManager.FEATURE_WIFI, true)
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        enableWifi(true)
        enableLocationService(false)

        val capability = RadioPermissions.wifiCapability(app)

        assertTrue(capability.permitted, "the permission genuinely is granted")
        assertFalse(capability.enabled)
        assertEquals(setOf(Degradation.LOCATION_SERVICES_OFF), capability.degradations)
    }

    @Test
    fun `wifi radio off is reported separately from location services off`() {
        haveFeature(PackageManager.FEATURE_WIFI, true)
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        enableLocationService(true)
        enableWifi(false)

        assertEquals(
            setOf(Degradation.RADIO_OFF),
            RadioPermissions.wifiCapability(app).degradations,
        )
    }

    @Test
    fun `a fully granted wifi stack is live`() {
        haveFeature(PackageManager.FEATURE_WIFI, true)
        grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        enableWifi(true)
        enableLocationService(true)

        val capability = RadioPermissions.wifiCapability(app)

        assertTrue(capability.live)
        assertTrue(capability.degradations.isEmpty())
    }

    /**
     * Foreground-only collection is usable, so a missing background grant is a degradation rather
     * than a failure — but it has to be recorded, because it is the explanation for a coverage gap
     * that starts the moment the screen locks.
     */
    @Test
    fun `a missing background grant degrades a live sensor without disabling it`() {
        haveFeature(PackageManager.FEATURE_WIFI, true)
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        enableWifi(true)
        enableLocationService(true)

        val capability = RadioPermissions.wifiCapability(app)

        assertTrue(capability.live, "the session still collects while in the foreground")
        assertTrue(Degradation.BACKGROUND_PERMISSION_DENIED in capability.degradations)
        assertTrue(Manifest.permission.ACCESS_BACKGROUND_LOCATION in capability.missingPermissions)
    }

    // -- the request sets by API level -------------------------------------------------------------

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `api 33 asks for nearby wifi devices and notifications`() {
        val requested = RadioPermissions.foregroundOnboardingPermissions()

        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in requested)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in requested)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `api 28 asks for neither the 31 bluetooth permissions nor the 33 additions`() {
        val requested = RadioPermissions.foregroundOnboardingPermissions()

        assertFalse(Manifest.permission.BLUETOOTH_SCAN in requested)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in requested)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in requested)
    }

    /**
     * `neverForLocation` is deliberately not declared on BLUETOOTH_SCAN, so location permission is
     * still needed on 31+. Asserted because the temptation to add the flag (and lose the RSSI that
     * is the positioning evidence) will recur.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `api 31 ble still requires location because neverForLocation is not declared`() {
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in RadioPermissions.blePermissions())
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in RadioPermissions.blePermissions())
    }

    /** Background location must be a second, separate request or the whole first one can be denied. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `background location is excluded from the first request and needs settings from api 30`() {
        assertFalse(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION in
                RadioPermissions.foregroundOnboardingPermissions(),
        )
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            RadioPermissions.backgroundLocationPermission(),
        )
        assertTrue(RadioPermissions.backgroundLocationRequiresSettings)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun `rtt is unsupported below api 28 even when the device claims the feature`() {
        haveFeature(PackageManager.FEATURE_WIFI_RTT, true)

        assertFalse(
            RadioPermissions.rttCapability(app).supported,
            "the ranging API does not exist before API 28, whatever the hardware reports",
        )
    }

    @Test
    fun `the onboarding matrix covers every declared observer capability`() {
        assertEquals(
            ObserverCapability.entries.toSet(),
            RadioPermissions.matrix(app).keys,
            "a capability missing from the matrix would never be shown as unavailable",
        )
    }
}
