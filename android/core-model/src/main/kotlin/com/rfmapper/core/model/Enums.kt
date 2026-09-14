package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which sensor produced an observation. Determines which optional fields are meaningful. */
@Serializable
enum class SensorType {
    WIFI_SCAN,
    WIFI_ASSOCIATION,
    BLE,
    RTT,
    GPS,

    /** An administrator-declared infrastructure sighting. A human assertion, not a measurement. */
    ZONE_ANCHOR,

    /** Administrator entry, e.g. "device physically seen here". */
    MANUAL,

    /** Synthesised from an external dataset; requires `metadata.import_source`. */
    IMPORT,
    ;

    val isRadioMeasurement: Boolean
        get() = this == WIFI_SCAN || this == WIFI_ASSOCIATION || this == BLE || this == RTT

    companion object {
        fun fromWireOrNull(value: String): SensorType? = entries.firstOrNull { it.name == value }
    }
}

/** What kind of identifier [Observation.radioIdentifier] holds. */
@Serializable
enum class IdentifierType {
    WIFI_BSSID,
    WIFI_SSID,
    BLE_MAC_PUBLIC,

    /**
     * A locally-administered (privacy-rotating) BLE address. Recorded, but never automatically
     * attributed to a device: see `docs/17-identity-and-attribution-policy.md`.
     */
    BLE_MAC_RANDOM,
    BLE_SERVICE_UUID,
    BLE_IBEACON,
    GNSS_FIX,
    OBSERVER_SELF,
    OTHER,
    ;

    /** True when this identifier is not a durable identity and must not be treated as one. */
    val isEphemeral: Boolean
        get() = this == BLE_MAC_RANDOM

    companion object {
        fun fromWireOrNull(value: String): IdentifierType? = entries.firstOrNull { it.name == value }
    }
}

@Serializable
enum class ObserverDeviceType {
    ANDROID_PHONE,
    ANDROID_TABLET,
    IOS_PHONE,
    IOS_TABLET,
    FIXED_OBSERVER,
    OTHER,
    ;

    companion object {
        fun fromWireOrNull(value: String): ObserverDeviceType? = entries.firstOrNull { it.name == value }
    }
}

@Serializable
enum class Platform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,

    @SerialName("other")
    OTHER,
}

/** Enrollment status of a managed device. */
@Serializable
enum class DeviceStatus {
    AUTHORIZED,
    INFRASTRUCTURE,
    BLOCKED,
    DISABLED,
    UNKNOWN,
}

@Serializable
enum class InfrastructureType {
    WIFI_AP,

    /**
     * Coarse zone evidence only. A long-range router without verified ranging capability belongs
     * here: a published coverage radius is not a ranging accuracy.
     */
    ZONE_ANCHOR,

    /** Only valid once a genuine RTT range has actually been obtained from this node. */
    RTT_ANCHOR,
    BLE_ANCHOR,
    OBSERVER_PHONE,
    ROUTER,
    OTHER,
}

/**
 * How deep an inference the evidence supports. The engine emits the deepest tier the evidence
 * justifies and stops there.
 */
@Serializable
enum class PrecisionTier {
    /** Device is somewhere in the monitored environment. */
    SITE_PRESENCE,

    BUILDING,

    ZONE,

    /** Coordinates with honest uncertainty. */
    APPROXIMATE_POSITION,

    /** Requires genuine ranging evidence (RTT) in the supporting observation set. */
    PRECISION_RANGE,
    ;

    val hasCoordinates: Boolean
        get() = this == APPROXIMATE_POSITION || this == PRECISION_RANGE
}

@Serializable
enum class MovementState {
    STATIONARY,
    MOVING,
    ZONE_TRANSITION,
    LOST,
    REAPPEARED,
    UNCERTAIN,
}

@Serializable
enum class ZoneEventType {
    RF_ZONE_ENTER,
    RF_ZONE_EXIT,
    RF_ZONE_TRANSITION,
}

@Serializable
enum class TopologyStatus {
    ADJACENT,
    NON_ADJACENT,
    RESTRICTED,

    /** The zone pair has no authored edge: suspicious, but not declared impossible. */
    UNKNOWN_EDGE,
}

@Serializable
enum class ZoneEdgeType {
    DOOR,
    CORRIDOR,
    OUTDOOR_PATH,
    STAIRS,
    RESTRICTED,
    IMPOSSIBLE,
}

@Serializable
enum class ZoneKind {
    ROOM,
    AREA,
    CORRIDOR,
    THRESHOLD,
    OUTDOOR,
    PATH,
}

/** Promotion state of a fingerprint. Only an administrator may move CANDIDATE to GROUND_TRUTH. */
@Serializable
enum class FingerprintStatus {
    CANDIDATE,
    GROUND_TRUTH,
    RETIRED,
}

/** Distinguishes deliberate survey capture from ordinary collection. */
@Serializable
enum class SampleKind {
    ORDINARY,
    GROUND_TRUTH,
    ;

    companion object {
        fun fromWireOrDefault(value: String?): SampleKind =
            entries.firstOrNull { it.name == value } ?: ORDINARY
    }
}

/** Whether a Wi-Fi scan result came from the scan that just completed, or from the platform cache. */
@Serializable
enum class ResultFreshness {
    FRESH,
    CACHED,
    UNKNOWN,
}

/** Sensor capabilities an observer installation can actually produce. */
@Serializable
enum class ObserverCapability {
    WIFI_SCAN,
    WIFI_ASSOCIATION,
    BLE,
    RTT,
    GPS,
}
