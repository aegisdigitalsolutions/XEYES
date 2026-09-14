package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An explicitly enrolled device. The closed set of managed devices is the entire authorized scope of
 * the system; everything else observed is environmental RF context.
 */
@Serializable
data class ManagedDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("friendly_name") val friendlyName: String,
    @SerialName("device_type") val deviceType: String,
    @SerialName("status") val status: DeviceStatus = DeviceStatus.AUTHORIZED,
    @SerialName("known_wifi_identifiers") val knownWifiIdentifiers: List<String> = emptyList(),
    @SerialName("known_ble_identifiers") val knownBleIdentifiers: List<String> = emptyList(),
    @SerialName("known_service_uuids") val knownServiceUuids: List<String> = emptyList(),
    @SerialName("notes") val notes: String? = null,
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "device_id must not be blank" }
    }

    /**
     * Every identifier that attributes to this device, normalized. Service UUIDs are the
     * recommended enrollment mechanism: they are the only identifier class that survives MAC
     * randomization and works across Android and iOS.
     */
    val allIdentifiers: List<Pair<String, IdentifierType>>
        get() = buildList {
            knownWifiIdentifiers.mapNotNullTo(this) { raw ->
                RadioIdentifierNormalizer.normalizeMac(raw)?.let { it to IdentifierType.WIFI_BSSID }
            }
            knownBleIdentifiers.mapNotNullTo(this) { raw ->
                RadioIdentifierNormalizer.normalizeMac(raw)
                    ?.let { it to RadioIdentifierNormalizer.identifierTypeForBleAddress(it) }
            }
            knownServiceUuids.mapNotNullTo(this) { raw ->
                RadioIdentifierNormalizer.normalizeUuid(raw)?.let { it to IdentifierType.BLE_SERVICE_UUID }
            }
        }
}

@Serializable
data class InfrastructureNode(
    @SerialName("node_id") val nodeId: String,
    @SerialName("friendly_name") val friendlyName: String,
    @SerialName("type") val type: InfrastructureType,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("floor") val floor: Int? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("x") val x: Double? = null,
    @SerialName("y") val y: Double? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("known_bssid") val knownBssid: String? = null,
    @SerialName("known_ble_identifier") val knownBleIdentifier: String? = null,

    /**
     * Set true **only** after a genuine RTT range has been obtained from this node. Never inferred
     * from a datasheet: a published coverage radius is not a ranging accuracy, and treating one as
     * the other is the most likely source of fabricated precision in a system like this.
     */
    @SerialName("rtt_capable") val rttCapable: Boolean = false,
    @SerialName("notes") val notes: String? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "node_id must not be blank" }
        require(!(rttCapable && type != InfrastructureType.RTT_ANCHOR)) {
            "rtt_capable requires type=RTT_ANCHOR (node $nodeId is ${type.name})"
        }
        if (x != null || y != null) {
            require(x != null && y != null) { "x and y must both be present or both absent" }
        }
    }

    /** True when this node can serve as a positioning anchor with a known site-frame location. */
    val isLocatedAnchor: Boolean get() = x != null && y != null
}

@Serializable
data class Building(
    @SerialName("building_id") val buildingId: String,
    @SerialName("name") val name: String,
    @SerialName("floors") val floors: List<Int> = listOf(0),
    @SerialName("outline_polygon") val outlinePolygon: List<Point> = emptyList(),
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class Point(
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
)

@Serializable
data class Zone(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("name") val name: String,
    @SerialName("floor") val floor: Int = 0,
    @SerialName("zone_kind") val zoneKind: ZoneKind = ZoneKind.AREA,
    @SerialName("polygon") val polygon: List<Point> = emptyList(),
    @SerialName("centroid_x") val centroidX: Double? = null,
    @SerialName("centroid_y") val centroidY: Double? = null,

    /**
     * Radius of the smallest circle enclosing [polygon]. Reported as the uncertainty of a
     * zone-centroid estimate, so a coarse estimate's error bar derives from real site geometry
     * rather than from a constant.
     */
    @SerialName("enclosing_radius_m") val enclosingRadiusM: Double? = null,
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class ZoneEdge(
    @SerialName("from_zone_id") val fromZoneId: String,
    @SerialName("to_zone_id") val toZoneId: String,
    @SerialName("edge_type") val edgeType: ZoneEdgeType,
    @SerialName("typical_traversal_s") val typicalTraversalS: Double? = null,
    @SerialName("bidirectional") val bidirectional: Boolean = true,
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class SurveyPoint(
    @SerialName("survey_point_id") val surveyPointId: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("zone_id") val zoneId: String,
    @SerialName("floor") val floor: Int? = null,
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
    @SerialName("label") val label: String? = null,

    /**
     * How to re-occupy this exact spot. Without it a resurvey is not repeatable, and drift becomes
     * unmeasurable.
     */
    @SerialName("physical_description") val physicalDescription: String? = null,
    @SerialName("created_at_utc") val createdAtUtc: String? = null,
    @SerialName("notes") val notes: String? = null,
)

/**
 * A statistical RF fingerprint for one surveyed location.
 *
 * Distributions, never single values: a source seen at -60 dBm with a 2 dB standard deviation carries
 * far more information than the same median with 11 dB, and a point-valued fingerprint discards that.
 */
@Serializable
data class FingerprintPoint(
    @SerialName("fingerprint_id") val fingerprintId: String,
    @SerialName("survey_point_id") val surveyPointId: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("zone_id") val zoneId: String,
    @SerialName("x") val x: Double? = null,
    @SerialName("y") val y: Double? = null,
    @SerialName("observer_id") val observerId: String? = null,

    /** Only an explicit administrator action may move this from CANDIDATE to GROUND_TRUTH. */
    @SerialName("status") val status: FingerprintStatus = FingerprintStatus.CANDIDATE,
    @SerialName("sample_count") val sampleCount: Int = 0,
    @SerialName("session_count") val sessionCount: Int? = null,
    @SerialName("source_survey_session_ids") val sourceSurveySessionIds: List<String> = emptyList(),
    @SerialName("engine_version") val engineVersion: String? = null,
    @SerialName("created_at_utc") val createdAtUtc: String? = null,
    @SerialName("entries") val entries: List<FingerprintEntry> = emptyList(),
)

@Serializable
data class FingerprintEntry(
    @SerialName("radio_identifier") val radioIdentifier: String,
    @SerialName("identifier_type") val identifierType: IdentifierType,
    @SerialName("sample_count") val sampleCount: Int,

    /**
     * Fraction of survey samples in which this source was seen. Used to penalise a candidate
     * location when a source it reliably sees is absent from the live vector — the absence of an
     * expected access point is strong evidence against a location.
     */
    @SerialName("visibility_probability") val visibilityProbability: Double,
    @SerialName("rssi_median") val rssiMedian: Double,
    @SerialName("rssi_mean") val rssiMean: Double? = null,
    @SerialName("rssi_stddev") val rssiStddev: Double? = null,
    @SerialName("rssi_p10") val rssiP10: Double? = null,
    @SerialName("rssi_p90") val rssiP90: Double? = null,
    @SerialName("rssi_min") val rssiMin: Double? = null,
    @SerialName("rssi_max") val rssiMax: Double? = null,
    @SerialName("temporal_stability") val temporalStability: Double? = null,
) {
    init {
        require(visibilityProbability in 0.0..1.0) {
            "visibility_probability must be in [0,1] (got $visibilityProbability)"
        }
    }
}

/**
 * An empirically measured per-observer RSSI offset.
 *
 * [spreadDb] is the guard against misapplying one: a "+3 dB" offset whose per-source values range
 * from -4 to +10 is not an offset, it is antenna-pattern difference, and applying it would make
 * results worse.
 */
@Serializable
data class ObserverCalibration(
    @SerialName("observer_id") val observerId: String,
    @SerialName("rssi_offset_db") val rssiOffsetDb: Double,
    @SerialName("measured_at_utc") val measuredAtUtc: String? = null,
    @SerialName("sample_count") val sampleCount: Int? = null,
    @SerialName("spread_db") val spreadDb: Double? = null,
    @SerialName("method") val method: String? = null,
    @SerialName("notes") val notes: String? = null,
)
