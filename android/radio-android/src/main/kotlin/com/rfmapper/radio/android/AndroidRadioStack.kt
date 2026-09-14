package com.rfmapper.radio.android

import android.content.Context
import android.os.Build
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.ScanProfile
import com.rfmapper.core.radio.ThrottleState

/**
 * Assembles the platform providers for one collection session.
 *
 * The Collector app talks to this and never to `android.net.wifi` or `android.bluetooth` directly,
 * so an OEM quirk or a scan-throttling workaround is fixed in one module. The RTT provider is wired
 * to the Wi-Fi provider's responder list here because only the Wi-Fi provider holds recent scan
 * results, and re-scanning to discover responders would spend scan quota that the Wi-Fi sample rate
 * needs.
 */
class AndroidRadioStack(
    context: Context,
    val profile: ScanProfile = ScanProfile.DEFAULT,
    onThrottleState: (ThrottleState) -> Unit = {},
) {
    private val appContext: Context = context.applicationContext

    val wifi = AndroidWifiObservationProvider(
        context = appContext,
        profile = profile,
        onThrottleState = onThrottleState,
    )

    val ble = AndroidBleObservationProvider(context = appContext, profile = profile)

    val rtt = AndroidRttObservationProvider(context = appContext).apply {
        scanResultSource = { wifi.rawScanResults() }
    }

    val location = AndroidLocationProvider(context = appContext, profile = profile)

    val deviceContext = AndroidDeviceContextProvider(appContext)

    /** Live capability per sensor, for the onboarding matrix and the dashboard. */
    fun capabilities(): Map<ObserverCapability, Capability> = RadioPermissions.matrix(appContext)

    /**
     * Fills in the platform-derived half of [ObserverIdentity].
     *
     * [ObserverIdentity.unsupported] is populated from the *hardware*, not from the current
     * permission state, and the distinction is the whole point of the field. "This phone has no RTT
     * chip" is permanent and tells the Lab that zero RTT rows mean zero capability. "Location
     * permission is currently denied" is a transient condition that a degradation note on the
     * session should describe instead — declaring it unsupported would permanently mislabel the
     * observer on the strength of a temporary state.
     */
    fun describeObserver(
        observerId: String,
        friendlyName: String,
        appVersion: String,
        installationId: String? = null,
        buildingId: String? = null,
        defaultZoneId: String? = null,
        deviceType: ObserverDeviceType = ObserverDeviceType.ANDROID_PHONE,
        xCoordinate: Double? = null,
        yCoordinate: Double? = null,
        fixedObserver: Boolean = false,
        notes: String? = null,
    ): ObserverIdentity {
        val matrix = capabilities()
        val supported = matrix.filterValues { it.supported }.keys
        val unsupported = matrix.filterValues { !it.supported }.keys

        return ObserverIdentity(
            observerId = observerId,
            friendlyName = friendlyName,
            observerDeviceType = deviceType,
            buildingId = buildingId,
            defaultZoneId = defaultZoneId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            manufacturer = Build.MANUFACTURER,
            platform = Platform.ANDROID,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = appVersion,
            installationId = installationId,
            capabilities = supported,
            unsupported = unsupported,
            xCoordinate = xCoordinate,
            yCoordinate = yCoordinate,
            fixedObserver = fixedObserver,
            notes = notes,
        )
    }
}
