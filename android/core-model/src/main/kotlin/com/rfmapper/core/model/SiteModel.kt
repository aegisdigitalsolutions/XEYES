package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The single site-wide Cartesian frame, in metres.
 *
 * Every `x`/`y` in the system — zone polygons, anchor positions, survey points, position estimates —
 * is expressed in this one frame. Recording the frame alongside the geometry is what lets a set of
 * metre coordinates be related back to the earth later without guessing which corner of which
 * building somebody measured from.
 */
@Serializable
data class SiteFrame(
    @SerialName("origin_lat") val originLat: Double,
    @SerialName("origin_lon") val originLon: Double,

    /** Bearing of the frame's +y axis, degrees clockwise from true north. */
    @SerialName("rotation_deg") val rotationDeg: Double = 0.0,
    @SerialName("projection") val projection: String = LOCAL_TANGENT_PLANE,
) {
    init {
        require(originLat in -90.0..90.0) { "origin_lat $originLat outside [-90,90]" }
        require(originLon in -180.0..180.0) { "origin_lon $originLon outside [-180,180]" }
        require(rotationDeg in -360.0..360.0) { "rotation_deg $rotationDeg outside [-360,360]" }
    }

    companion object {
        const val LOCAL_TANGENT_PLANE = "LOCAL_TANGENT_PLANE"
    }
}

/**
 * A snapshot of the whole REFERENCE layer, exchanged as one JSON document.
 *
 * Carried as a single file rather than a set of tables because a site model is only meaningful as a
 * whole: zones that reference a missing building, or an edge between zones that no longer exist,
 * are not a partially-valid model but an invalid one. [referenceModelId] stamps the snapshot so a
 * derived estimate computed last month can still say which geometry it was computed against, even
 * after a wall moved.
 *
 * @see <a href="../../../../../../../../docs/18-site-model-specification.md">docs/18</a>
 */
@Serializable
data class SiteModel(
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,
    @SerialName("reference_model_id") val referenceModelId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("notes") val notes: String? = null,
    @SerialName("frame") val frame: SiteFrame,
    @SerialName("buildings") val buildings: List<Building> = emptyList(),
    @SerialName("zones") val zones: List<Zone> = emptyList(),
    @SerialName("zone_edges") val zoneEdges: List<ZoneEdge> = emptyList(),
    @SerialName("infrastructure_nodes") val infrastructureNodes: List<InfrastructureNode> = emptyList(),
    @SerialName("observers") val observers: List<ObserverIdentity> = emptyList(),
    @SerialName("survey_points") val surveyPoints: List<SurveyPoint> = emptyList(),
    @SerialName("fingerprints") val fingerprints: List<FingerprintPoint> = emptyList(),
    @SerialName("observer_calibration") val observerCalibration: List<ObserverCalibration> = emptyList(),
) {
    init {
        require(referenceModelId.isNotBlank()) { "reference_model_id must not be blank" }
    }

    /**
     * Referential problems, as a list rather than an exception.
     *
     * A model is imported by a human who needs to see everything wrong with the file at once; a
     * validator that threw on the first dangling zone would make fixing a hand-authored site model
     * a twenty-round guessing game.
     */
    fun referentialIssues(): List<String> = buildList {
        val buildingIds = buildings.mapTo(HashSet()) { it.buildingId }
        val zoneIds = zones.mapTo(HashSet()) { it.zoneId }

        duplicates(buildings.map { it.buildingId }).forEach { add("duplicate building_id '$it'") }
        duplicates(zones.map { it.zoneId }).forEach { add("duplicate zone_id '$it'") }
        duplicates(surveyPoints.map { it.surveyPointId })
            .forEach { add("duplicate survey_point_id '$it'") }
        duplicates(infrastructureNodes.map { it.nodeId }).forEach { add("duplicate node_id '$it'") }
        duplicates(observers.map { it.observerId }).forEach { add("duplicate observer_id '$it'") }

        zones.filterNot { it.buildingId in buildingIds }
            .forEach { add("zone '${it.zoneId}' references unknown building '${it.buildingId}'") }

        for (edge in zoneEdges) {
            if (edge.fromZoneId !in zoneIds) {
                add("zone_edge references unknown zone '${edge.fromZoneId}'")
            }
            if (edge.toZoneId !in zoneIds) {
                add("zone_edge references unknown zone '${edge.toZoneId}'")
            }
        }

        for (point in surveyPoints) {
            if (point.buildingId !in buildingIds) {
                add("survey point '${point.surveyPointId}' references unknown building '${point.buildingId}'")
            }
            if (point.zoneId !in zoneIds) {
                add("survey point '${point.surveyPointId}' references unknown zone '${point.zoneId}'")
            }
        }

        val surveyPointIds = surveyPoints.mapTo(HashSet()) { it.surveyPointId }
        fingerprints.filterNot { it.surveyPointId in surveyPointIds }.forEach {
            add("fingerprint '${it.fingerprintId}' references unknown survey point '${it.surveyPointId}'")
        }

        infrastructureNodes.filter { it.zoneId != null && it.zoneId !in zoneIds }
            .forEach { add("infrastructure node '${it.nodeId}' references unknown zone '${it.zoneId}'") }

        // An anchor with a position but no ranging evidence is the classic source of fabricated
        // precision, so the model names it rather than letting the positioning engine discover it.
        infrastructureNodes.filter { it.type == InfrastructureType.RTT_ANCHOR && !it.isLocatedAnchor }
            .forEach { add("RTT anchor '${it.nodeId}' has no site-frame coordinates and cannot anchor a range") }
    }

    private fun duplicates(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
}
