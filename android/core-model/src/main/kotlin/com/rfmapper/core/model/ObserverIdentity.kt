package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identity and capability declaration for one Collector installation. Serialized as `observer.json`
 * in every export package.
 *
 * [capabilities] and [unsupported] are load-bearing rather than informational. An observer that
 * cannot scan Wi-Fi and reports no Wi-Fi observations means *"this observer cannot see Wi-Fi"*, not
 * *"no access points were present"*. Without that distinction an iOS observer would silently drive
 * every fingerprint's Wi-Fi visibility probability toward zero — absence of evidence misread as
 * evidence of absence.
 */
@Serializable
data class ObserverIdentity(
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,
    @SerialName("observer_id") val observerId: String,
    @SerialName("friendly_name") val friendlyName: String,
    @SerialName("observer_device_type") val observerDeviceType: ObserverDeviceType,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("default_zone_id") val defaultZoneId: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("platform") val platform: Platform,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("capabilities") val capabilities: Set<ObserverCapability> = emptySet(),
    @SerialName("unsupported") val unsupported: Set<ObserverCapability> = emptySet(),
    @SerialName("x_coordinate") val xCoordinate: Double? = null,
    @SerialName("y_coordinate") val yCoordinate: Double? = null,

    /**
     * True when [xCoordinate]/[yCoordinate] are a trusted measured location. A fixed observer's
     * samples become reference-quality evidence in multi-observer fusion, so this must not be set
     * for a phone that merely happens to be sitting somewhere.
     */
    @SerialName("fixed_observer") val fixedObserver: Boolean = false,
    @SerialName("notes") val notes: String? = null,
) {
    init {
        require(observerId.isNotBlank()) { "observer_id must not be blank" }
        require(observerId.length <= ObservationInvariants.MAX_ID_LENGTH) {
            "observer_id exceeds ${ObservationInvariants.MAX_ID_LENGTH} characters"
        }
        require(capabilities.intersect(unsupported).isEmpty()) {
            "a capability cannot be both supported and unsupported"
        }
        if (fixedObserver) {
            require(xCoordinate != null && yCoordinate != null && buildingId != null) {
                "a fixed observer requires building_id and x/y coordinates"
            }
        }
    }

    /** Filename-safe form used in export package names: `OBS-04` -> `OBS04`. */
    val fileSafeId: String get() = observerId.filter { it.isLetterOrDigit() }
}

/**
 * Per-session context recorded alongside a package.
 *
 * [throttledScanRequests], [droppedSamples] and [suspectedServiceKill] turn an unexplained coverage
 * gap into a known one. Recording that data was lost is far more valuable than a clean-looking file
 * that quietly omits it.
 */
@Serializable
data class SessionSummary(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("scan_profile") val scanProfile: String,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("observation_count") val observationCount: Long = 0,
    @SerialName("wifi_count") val wifiCount: Long = 0,
    @SerialName("ble_count") val bleCount: Long = 0,
    @SerialName("rtt_count") val rttCount: Long = 0,
    @SerialName("gps_count") val gpsCount: Long = 0,
    @SerialName("dropped_samples") val droppedSamples: Long = 0,
    @SerialName("throttled_scan_requests") val throttledScanRequests: Long = 0,
    @SerialName("background_denied") val backgroundDenied: Boolean = false,
    @SerialName("suspected_service_kill") val suspectedServiceKill: Boolean = false,
    @SerialName("battery_start_pct") val batteryStartPct: Int? = null,
    @SerialName("battery_end_pct") val batteryEndPct: Int? = null,
    @SerialName("degradations") val degradations: List<String> = emptyList(),
)
