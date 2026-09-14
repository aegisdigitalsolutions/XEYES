package com.rfmapper.radio.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.Platform
import com.rfmapper.core.radio.ScanProfile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidRadioStackTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun grantEverything() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
        )
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LOCATION, true)
    }

    /**
     * The distinction this asserts is the reason `ObserverIdentity.unsupported` exists. An observer
     * that reports no RTT rows because it has no RTT chip is saying something permanent; one that
     * reports none because a permission is momentarily denied is not. Conflating them would make
     * the Lab treat "cannot see" as "saw nothing" for the life of the observer.
     */
    @Test
    fun `unsupported is derived from hardware and not from the current permission state`() {
        grantEverything()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_RTT, false)
        // Wi-Fi hardware present but permission denied right now.
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val stack = AndroidRadioStack(app)
        val identity = stack.describeObserver(
            observerId = "OBS-04",
            friendlyName = "Field phone 4",
            appVersion = "1.0.0",
        )

        assertTrue(
            ObserverCapability.RTT in identity.unsupported,
            "no RTT chip is a permanent fact about this observer",
        )
        assertFalse(
            ObserverCapability.WIFI_SCAN in identity.unsupported,
            "a denied permission is transient and belongs in a session degradation, not here",
        )
        assertTrue(ObserverCapability.WIFI_SCAN in identity.capabilities)
    }

    @Test
    fun `the identity carries the platform provenance the lab needs`() {
        grantEverything()

        val identity = AndroidRadioStack(app).describeObserver(
            observerId = "OBS-04",
            friendlyName = "Field phone 4",
            appVersion = "1.2.3",
            installationId = "install-abc",
            buildingId = "B7",
        )

        assertEquals(Platform.ANDROID, identity.platform)
        assertEquals("1.2.3", identity.appVersion)
        assertEquals("install-abc", identity.installationId)
        assertEquals("B7", identity.buildingId)
        assertTrue(identity.osVersion?.startsWith("Android ") == true)
        assertEquals("OBS04", identity.fileSafeId)
    }

    @Test
    fun `capabilities and unsupported never overlap`() {
        grantEverything()
        val identity = AndroidRadioStack(app).describeObserver("OBS-1", "One", "1.0.0")

        assertTrue(identity.capabilities.intersect(identity.unsupported).isEmpty())
    }

    /**
     * A provider whose sensor is unavailable must complete rather than hang. A hanging flow would
     * hold a collection coroutine open forever and the session would look healthy while producing
     * nothing from that sensor.
     */
    @Test
    fun `an unavailable sensor completes its flow instead of hanging`() = runTest {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)

        val stack = AndroidRadioStack(app, ScanProfile.AGGRESSIVE)

        assertEquals(emptyList(), stack.ble.samples().toList())
        assertEquals(emptyList(), stack.location.samples().toList())
    }

    @Test
    fun `rtt ranging without responders costs nothing`() = runTest {
        grantEverything()
        val stack = AndroidRadioStack(app)

        assertEquals(emptyList(), stack.rtt.range(emptyList()))
        assertEquals(emptyList(), stack.rtt.availableResponders())
    }

    @Test
    fun `device context reports the state that explains a sample rate collapse`() = runTest {
        val snapshot = AndroidDeviceContextProvider(app).snapshot()

        // Battery exemption is the field that turns an unexplained multi-hour gap into an
        // expected one, so it must always be present rather than conditionally omitted.
        assertTrue("ignoring_battery_optimizations" in snapshot)
        assertTrue("screen_on" in snapshot)
        assertTrue("power_save_mode" in snapshot)
    }
}
