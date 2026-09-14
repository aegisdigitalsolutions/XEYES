package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A derived spatial estimate for one managed device at one instant.
 *
 * Coordinates are optional; uncertainty is not. The invariants below make the specification's
 * anti-fabrication rules structural rather than advisory:
 *
 *  - [x]/[y] cannot be present without [horizontalUncertaintyM]. A bare point on a map reads as a
 *    fact, so a coordinate without an error bar is simply not constructible.
 *  - [PrecisionTier.PRECISION_RANGE] is unreachable without genuine RTT evidence in the supporting
 *    observation set, so no amount of confident RSSI can be promoted into it.
 *
 * A confident zone-only estimate (`ZONE`, null coordinates) is a valid and preferred result.
 */
@Serializable
data class PositionEstimate(
    @SerialName("estimate_id") val estimateId: String,
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("engine_versions") val engineVersions: Map<String, String> = emptyMap(),
    @SerialName("parameter_set_sha256") val parameterSetSha256: String? = null,

    /** A managed device id. Positioning is defined only for enrolled devices. */
    @SerialName("device_id") val deviceId: String,

    /** The instant the estimate refers to, not when it was computed. */
    @SerialName("timestamp_utc") val timestampUtc: String,
    @SerialName("computed_at_utc") val computedAtUtc: String,
    @SerialName("precision_tier") val precisionTier: PrecisionTier,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("x") val x: Double? = null,
    @SerialName("y") val y: Double? = null,
    @SerialName("horizontal_uncertainty_m") val horizontalUncertaintyM: Double? = null,
    @SerialName("confidence") val confidence: Double,

    /** The named factors whose weighted product is [confidence], so any value can be explained. */
    @SerialName("confidence_factors") val confidenceFactors: Map<String, Double> = emptyMap(),
    @SerialName("method") val method: String,
    @SerialName("supporting_observer_ids") val supportingObserverIds: List<String>,
    @SerialName("supporting_observation_ids") val supportingObservationIds: List<String>,
    @SerialName("source_dataset_ids") val sourceDatasetIds: List<String> = emptyList(),
    @SerialName("reference_model_id") val referenceModelId: String? = null,
    @SerialName("calibration_set_id") val calibrationSetId: String? = null,
    @SerialName("quality_flags") val qualityFlags: List<String> = emptyList(),
) {
    init {
        require(deviceId.isNotBlank()) { "device_id must not be blank" }
        require(confidence in 0.0..1.0) { "confidence $confidence outside [0,1]" }
        require((x == null) == (y == null)) { "x and y must both be present or both absent" }

        if (x != null) {
            require(horizontalUncertaintyM != null && horizontalUncertaintyM > 0) {
                "a coordinate requires a positive horizontal_uncertainty_m: never emit a position " +
                    "without uncertainty"
            }
            require(precisionTier.hasCoordinates) {
                "coordinates require tier APPROXIMATE_POSITION or PRECISION_RANGE (got $precisionTier)"
            }
        }
        if (precisionTier.hasCoordinates) {
            require(x != null) { "tier $precisionTier requires coordinates" }
        }
        if (zoneId != null) {
            require(buildingId != null) { "zone_id requires building_id" }
        }
        if (precisionTier == PrecisionTier.SITE_PRESENCE) {
            require(zoneId == null) { "SITE_PRESENCE must not assert a zone" }
        }
        require(supportingObservationIds.isNotEmpty()) {
            "an estimate with no supporting observation is not an estimate"
        }
        require(supportingObserverIds.isNotEmpty()) { "supporting_observer_ids must not be empty" }
    }
}

/**
 * A committed zone entry, exit or transition.
 *
 * [transitionStartUtc] and [transitionConfirmedUtc] are kept separate because their difference *is*
 * the hysteresis delay of the system. Collapsing them into one timestamp would hide the engine's own
 * lag and make transition-detection latency unmeasurable.
 */
@Serializable
data class ZoneTransition(
    @SerialName("transition_id") val transitionId: String,
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("engine_versions") val engineVersions: Map<String, String> = emptyMap(),
    @SerialName("device_id") val deviceId: String,
    @SerialName("event_type") val eventType: ZoneEventType,
    @SerialName("origin_zone_id") val originZoneId: String? = null,
    @SerialName("destination_zone_id") val destinationZoneId: String? = null,
    @SerialName("transition_start_utc") val transitionStartUtc: String,
    @SerialName("transition_confirmed_utc") val transitionConfirmedUtc: String,
    @SerialName("confidence") val confidence: Double,

    /**
     * A NON_ADJACENT or RESTRICTED transition is emitted with a quality flag rather than suppressed:
     * the site graph may be the thing that is wrong (a new door, a relocated access point).
     */
    @SerialName("topology_status") val topologyStatus: TopologyStatus,
    @SerialName("supporting_observer_ids") val supportingObserverIds: List<String>,
    @SerialName("supporting_estimate_ids") val supportingEstimateIds: List<String>,
    @SerialName("quality_flags") val qualityFlags: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence $confidence outside [0,1]" }
        when (eventType) {
            ZoneEventType.RF_ZONE_TRANSITION -> require(originZoneId != null && destinationZoneId != null) {
                "RF_ZONE_TRANSITION requires both origin and destination"
            }
            ZoneEventType.RF_ZONE_ENTER -> require(destinationZoneId != null) {
                "RF_ZONE_ENTER requires a destination"
            }
            ZoneEventType.RF_ZONE_EXIT -> require(originZoneId != null) {
                "RF_ZONE_EXIT requires an origin"
            }
        }
        require(supportingEstimateIds.isNotEmpty()) { "a transition requires supporting estimates" }
    }
}

@Serializable
data class MovementEstimate(
    @SerialName("movement_id") val movementId: String,
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("engine_versions") val engineVersions: Map<String, String> = emptyMap(),
    @SerialName("device_id") val deviceId: String,
    @SerialName("timestamp_utc") val timestampUtc: String,
    @SerialName("state") val state: MovementState,
    @SerialName("origin_zone_id") val originZoneId: String? = null,
    @SerialName("candidate_destination_zone_id") val candidateDestinationZoneId: String? = null,
    @SerialName("confirmed_destination_zone_id") val confirmedDestinationZoneId: String? = null,

    /**
     * Movement between named spatial regions, e.g. `B7->B9`. A geometric heading may only be used
     * when actual coordinate evidence supports a vector.
     */
    @SerialName("direction") val direction: String? = null,
    @SerialName("confidence") val confidence: Double,
    @SerialName("supporting_estimate_ids") val supportingEstimateIds: List<String> = emptyList(),
    @SerialName("quality_flags") val qualityFlags: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence $confidence outside [0,1]" }
        if (state == MovementState.ZONE_TRANSITION) {
            require(originZoneId != null && candidateDestinationZoneId != null) {
                "ZONE_TRANSITION requires an origin and a candidate destination"
            }
        }
        // A LOST device legitimately has no supporting estimates; every other state must be evidenced.
        if (state != MovementState.LOST && state != MovementState.UNCERTAIN) {
            require(supportingEstimateIds.isNotEmpty()) { "state $state requires supporting estimates" }
        }
    }
}

/**
 * An advisory finding for a human. Flags never drive automatic behaviour: the administrator decides
 * whether calibration changes, which is what keeps ordinary observations from redefining ground truth.
 */
@Serializable
data class QualityFlag(
    @SerialName("flag_id") val flagId: String,
    @SerialName("algorithm_version") val algorithmVersion: String? = null,
    @SerialName("created_at_utc") val createdAtUtc: String,
    @SerialName("severity") val severity: Severity,
    @SerialName("code") val code: String,
    @SerialName("scope") val scope: String,
    @SerialName("scope_id") val scopeId: String? = null,
    @SerialName("message") val message: String,
    @SerialName("evidence") val evidence: Map<String, String> = emptyMap(),
    @SerialName("acknowledged_at_utc") val acknowledgedAtUtc: String? = null,
    @SerialName("acknowledged_by") val acknowledgedBy: String? = null,
) {
    @Serializable
    enum class Severity { INFO, WARNING, ERROR }
}
