package com.rfmapper.data.room.reference

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rfmapper.core.model.Building
import com.rfmapper.core.model.DeviceStatus
import com.rfmapper.core.model.FingerprintEntry
import com.rfmapper.core.model.FingerprintPoint
import com.rfmapper.core.model.FingerprintStatus
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.InfrastructureNode
import com.rfmapper.core.model.InfrastructureType
import com.rfmapper.core.model.ManagedDevice
import com.rfmapper.core.model.ObserverCalibration
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.Point
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SurveyPoint
import com.rfmapper.core.model.Zone
import com.rfmapper.core.model.ZoneEdge
import com.rfmapper.core.model.ZoneEdgeType
import com.rfmapper.core.model.ZoneKind
import kotlinx.serialization.builtins.ListSerializer

/**
 * The REFERENCE layer: everything an administrator curates by hand.
 *
 * Unlike RAW, these rows are editable — a zone gets renamed, a device is retired, an access point
 * moves. What is *not* editable is history: a change here alters how future derived estimates are
 * computed, which is why every derived record stamps the reference model version it was computed
 * against (`docs/14-algorithm-versioning-strategy.md`).
 *
 * @see <a href="../../../../../../../../../../docs/04-room-entity-dao-design.md">docs/04, §3</a>
 */
@Entity(tableName = "ref_managed_device")
data class ManagedDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "friendly_name") val friendlyName: String,
    @ColumnInfo(name = "device_type") val deviceType: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "first_seen_utc") val firstSeenUtc: String?,
    @ColumnInfo(name = "last_seen_utc") val lastSeenUtc: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String,
    @ColumnInfo(name = "updated_at_utc") val updatedAtUtc: String,
) {
    fun toModel(identifiers: List<DeviceIdentifierEntity>) = ManagedDevice(
        deviceId = deviceId,
        friendlyName = friendlyName,
        deviceType = deviceType,
        status = DeviceStatus.entries.firstOrNull { it.name == status } ?: DeviceStatus.UNKNOWN,
        knownWifiIdentifiers = identifiers.filter { it.identifierType == IdentifierType.WIFI_BSSID.name }
            .map { it.identifier },
        knownBleIdentifiers = identifiers.filter {
            it.identifierType == IdentifierType.BLE_MAC_PUBLIC.name ||
                it.identifierType == IdentifierType.BLE_MAC_RANDOM.name
        }.map { it.identifier },
        knownServiceUuids = identifiers.filter { it.identifierType == IdentifierType.BLE_SERVICE_UUID.name }
            .map { it.identifier },
        notes = notes,
        firstSeen = firstSeenUtc,
        lastSeen = lastSeenUtc,
    )

    companion object {
        fun from(device: ManagedDevice, nowUtc: String, createdAtUtc: String = nowUtc) =
            ManagedDeviceEntity(
                deviceId = device.deviceId,
                friendlyName = device.friendlyName,
                deviceType = device.deviceType,
                status = device.status.name,
                notes = device.notes,
                firstSeenUtc = device.firstSeen,
                lastSeenUtc = device.lastSeen,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = nowUtc,
            )
    }
}

/**
 * Attribution, normalised out of the model's three `known_*` lists.
 *
 * A table rather than a JSON array for two reasons. It is a hot lookup — *is this identifier
 * enrolled?* runs against every single observation, and an indexed join answers it where a JSON
 * array would force a full scan per row. And the composite primary key makes "one identifier
 * claimed by two devices" impossible at the storage level, rather than something the application
 * has to remember to check.
 */
@Entity(
    tableName = "ref_device_identifier",
    primaryKeys = ["identifier", "identifier_type"],
    indices = [Index("device_id")],
    foreignKeys = [
        ForeignKey(
            entity = ManagedDeviceEntity::class,
            parentColumns = ["device_id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeviceIdentifierEntity(
    @ColumnInfo(name = "identifier") val identifier: String,
    @ColumnInfo(name = "identifier_type") val identifierType: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "added_by") val addedBy: String?,
    @ColumnInfo(name = "added_at_utc") val addedAtUtc: String,
    @ColumnInfo(name = "notes") val notes: String?,
)

@Entity(
    tableName = "ref_infrastructure_node",
    indices = [Index("known_bssid"), Index("building_id", "zone_id")],
)
data class InfrastructureNodeEntity(
    @PrimaryKey @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "friendly_name") val friendlyName: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "building_id") val buildingId: String?,
    @ColumnInfo(name = "floor") val floor: Int?,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "x") val x: Double?,
    @ColumnInfo(name = "y") val y: Double?,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "known_bssid") val knownBssid: String?,
    @ColumnInfo(name = "known_ble_identifier") val knownBleIdentifier: String?,
    @ColumnInfo(name = "rtt_capable") val rttCapable: Boolean,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = InfrastructureNode(
        nodeId = nodeId,
        friendlyName = friendlyName,
        type = InfrastructureType.entries.firstOrNull { it.name == type } ?: InfrastructureType.OTHER,
        buildingId = buildingId,
        floor = floor,
        zoneId = zoneId,
        x = x,
        y = y,
        latitude = latitude,
        longitude = longitude,
        knownBssid = knownBssid,
        knownBleIdentifier = knownBleIdentifier,
        rttCapable = rttCapable,
        notes = notes,
    )

    companion object {
        fun from(node: InfrastructureNode) = InfrastructureNodeEntity(
            nodeId = node.nodeId,
            friendlyName = node.friendlyName,
            type = node.type.name,
            buildingId = node.buildingId,
            floor = node.floor,
            zoneId = node.zoneId,
            x = node.x,
            y = node.y,
            latitude = node.latitude,
            longitude = node.longitude,
            knownBssid = node.knownBssid,
            knownBleIdentifier = node.knownBleIdentifier,
            rttCapable = node.rttCapable,
            notes = node.notes,
        )
    }
}

@Entity(tableName = "ref_observer")
data class ObserverEntity(
    @PrimaryKey @ColumnInfo(name = "observer_id") val observerId: String,
    @ColumnInfo(name = "friendly_name") val friendlyName: String,
    @ColumnInfo(name = "observer_device_type") val observerDeviceType: String,
    @ColumnInfo(name = "building_id") val buildingId: String?,
    @ColumnInfo(name = "default_zone_id") val defaultZoneId: String?,
    @ColumnInfo(name = "device_model") val deviceModel: String?,
    @ColumnInfo(name = "manufacturer") val manufacturer: String?,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "os_version") val osVersion: String?,
    @ColumnInfo(name = "app_version") val appVersion: String?,
    @ColumnInfo(name = "installation_id") val installationId: String?,
    @ColumnInfo(name = "capabilities") val capabilities: List<String>,
    @ColumnInfo(name = "unsupported") val unsupported: List<String>,
    @ColumnInfo(name = "x_coordinate") val xCoordinate: Double?,
    @ColumnInfo(name = "y_coordinate") val yCoordinate: Double?,
    @ColumnInfo(name = "fixed_observer") val fixedObserver: Boolean,

    /**
     * Enrolled observers only. The Master refuses to import a package from an unknown observer:
     * unverified provenance must not enter an immutable raw layer.
     */
    @ColumnInfo(name = "enrolled") val enrolled: Boolean,
    @ColumnInfo(name = "enrolled_at_utc") val enrolledAtUtc: String?,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = ObserverIdentity(
        observerId = observerId,
        friendlyName = friendlyName,
        observerDeviceType = ObserverDeviceType.entries.firstOrNull { it.name == observerDeviceType }
            ?: ObserverDeviceType.OTHER,
        buildingId = buildingId,
        defaultZoneId = defaultZoneId,
        deviceModel = deviceModel,
        manufacturer = manufacturer,
        platform = Platform.entries.firstOrNull { it.name == platform } ?: Platform.OTHER,
        osVersion = osVersion,
        appVersion = appVersion.orEmpty(),
        installationId = installationId,
        capabilities = capabilities.mapNotNullTo(LinkedHashSet()) { name ->
            ObserverCapability.entries.firstOrNull { it.name == name }
        },
        unsupported = unsupported.mapNotNullTo(LinkedHashSet()) { name ->
            ObserverCapability.entries.firstOrNull { it.name == name }
        },
        xCoordinate = xCoordinate,
        yCoordinate = yCoordinate,
        fixedObserver = fixedObserver,
        notes = notes,
    )

    companion object {
        /**
         * [enrolled] is not taken from the document. Enrolment is the Master administrator's
         * decision about whose data it will accept, so a file that declared itself enrolled would
         * be an observer granting itself permission.
         */
        fun from(
            identity: ObserverIdentity,
            enrolled: Boolean = false,
            enrolledAtUtc: String? = null,
        ) = ObserverEntity(
            observerId = identity.observerId,
            friendlyName = identity.friendlyName,
            observerDeviceType = identity.observerDeviceType.name,
            buildingId = identity.buildingId,
            defaultZoneId = identity.defaultZoneId,
            deviceModel = identity.deviceModel,
            manufacturer = identity.manufacturer,
            platform = identity.platform.name,
            osVersion = identity.osVersion,
            appVersion = identity.appVersion,
            installationId = identity.installationId,
            capabilities = identity.capabilities.map { it.name },
            unsupported = identity.unsupported.map { it.name },
            xCoordinate = identity.xCoordinate,
            yCoordinate = identity.yCoordinate,
            fixedObserver = identity.fixedObserver,
            enrolled = enrolled,
            enrolledAtUtc = enrolledAtUtc,
            notes = identity.notes,
        )
    }
}

@Entity(tableName = "ref_building")
data class BuildingEntity(
    @PrimaryKey @ColumnInfo(name = "building_id") val buildingId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "floors") val floors: List<String>,
    @ColumnInfo(name = "outline_polygon") val outlinePolygon: String?,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = Building(
        buildingId = buildingId,
        name = name,
        floors = floors.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) },
        outlinePolygon = Polygons.decode(outlinePolygon),
        notes = notes,
    )

    companion object {
        fun from(building: Building) = BuildingEntity(
            buildingId = building.buildingId,
            name = building.name,
            floors = building.floors.map { it.toString() },
            outlinePolygon = Polygons.encode(building.outlinePolygon),
            notes = building.notes,
        )
    }
}

@Entity(
    tableName = "ref_zone",
    indices = [Index("building_id", "floor")],
)
data class ZoneEntity(
    @PrimaryKey @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "building_id") val buildingId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "floor") val floor: Int,
    @ColumnInfo(name = "zone_kind") val zoneKind: String,
    @ColumnInfo(name = "polygon") val polygon: String?,
    @ColumnInfo(name = "centroid_x") val centroidX: Double?,
    @ColumnInfo(name = "centroid_y") val centroidY: Double?,
    @ColumnInfo(name = "enclosing_radius_m") val enclosingRadiusM: Double?,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = Zone(
        zoneId = zoneId,
        buildingId = buildingId,
        name = name,
        floor = floor,
        zoneKind = ZoneKind.entries.firstOrNull { it.name == zoneKind } ?: ZoneKind.AREA,
        polygon = Polygons.decode(polygon),
        centroidX = centroidX,
        centroidY = centroidY,
        enclosingRadiusM = enclosingRadiusM,
        notes = notes,
    )

    companion object {
        fun from(zone: Zone) = ZoneEntity(
            zoneId = zone.zoneId,
            buildingId = zone.buildingId,
            name = zone.name,
            floor = zone.floor,
            zoneKind = zone.zoneKind.name,
            polygon = Polygons.encode(zone.polygon),
            centroidX = zone.centroidX ?: Polygons.centroid(zone.polygon)?.x,
            centroidY = zone.centroidY ?: Polygons.centroid(zone.polygon)?.y,
            enclosingRadiusM = zone.enclosingRadiusM ?: Polygons.enclosingRadius(zone.polygon),
            notes = zone.notes,
        )
    }
}

@Entity(
    tableName = "ref_zone_edge",
    primaryKeys = ["from_zone_id", "to_zone_id"],
    indices = [Index("to_zone_id")],
)
data class ZoneEdgeEntity(
    @ColumnInfo(name = "from_zone_id") val fromZoneId: String,
    @ColumnInfo(name = "to_zone_id") val toZoneId: String,
    @ColumnInfo(name = "edge_type") val edgeType: String,
    @ColumnInfo(name = "typical_traversal_s") val typicalTraversalS: Double?,
    @ColumnInfo(name = "bidirectional") val bidirectional: Boolean,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = ZoneEdge(
        fromZoneId = fromZoneId,
        toZoneId = toZoneId,
        edgeType = ZoneEdgeType.entries.firstOrNull { it.name == edgeType } ?: ZoneEdgeType.CORRIDOR,
        typicalTraversalS = typicalTraversalS,
        bidirectional = bidirectional,
        notes = notes,
    )

    companion object {
        fun from(edge: ZoneEdge) = ZoneEdgeEntity(
            fromZoneId = edge.fromZoneId,
            toZoneId = edge.toZoneId,
            edgeType = edge.edgeType.name,
            typicalTraversalS = edge.typicalTraversalS,
            bidirectional = edge.bidirectional,
            notes = edge.notes,
        )
    }
}

@Entity(
    tableName = "ref_survey_point",
    indices = [Index("building_id", "zone_id")],
)
data class SurveyPointEntity(
    @PrimaryKey @ColumnInfo(name = "survey_point_id") val surveyPointId: String,
    @ColumnInfo(name = "building_id") val buildingId: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "floor") val floor: Int?,
    @ColumnInfo(name = "x") val x: Double,
    @ColumnInfo(name = "y") val y: Double,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "physical_description") val physicalDescription: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String?,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = SurveyPoint(
        surveyPointId = surveyPointId,
        buildingId = buildingId,
        zoneId = zoneId,
        floor = floor,
        x = x,
        y = y,
        label = label,
        physicalDescription = physicalDescription,
        createdAtUtc = createdAtUtc,
        notes = notes,
    )

    companion object {
        fun from(point: SurveyPoint) = SurveyPointEntity(
            surveyPointId = point.surveyPointId,
            buildingId = point.buildingId,
            zoneId = point.zoneId,
            floor = point.floor,
            x = point.x,
            y = point.y,
            label = point.label,
            physicalDescription = point.physicalDescription,
            createdAtUtc = point.createdAtUtc,
            notes = point.notes,
        )
    }
}

/**
 * A fingerprint's header. Split from its entries because a fingerprint has an unbounded number of
 * visible radio sources and the matcher iterates only the sources present in the live vector.
 *
 * [status] is the promotion gate. Survey Mode writes `CANDIDATE`; only an explicit administrator
 * action moves a row to `GROUND_TRUTH`. No automatic path performs that transition, because
 * calibration data that promoted itself would be indistinguishable from calibration data somebody
 * checked.
 */
@Entity(
    tableName = "ref_fingerprint",
    indices = [Index("survey_point_id"), Index("status"), Index("building_id", "zone_id")],
)
data class FingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "fingerprint_id") val fingerprintId: String,
    @ColumnInfo(name = "survey_point_id") val surveyPointId: String,
    @ColumnInfo(name = "building_id") val buildingId: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "x") val x: Double?,
    @ColumnInfo(name = "y") val y: Double?,
    @ColumnInfo(name = "observer_id") val observerId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "session_count") val sessionCount: Int?,
    @ColumnInfo(name = "source_survey_session_ids") val sourceSurveySessionIds: List<String>,
    @ColumnInfo(name = "engine_version") val engineVersion: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String?,
    @ColumnInfo(name = "updated_at_utc") val updatedAtUtc: String?,

    /** Who promoted this to ground truth. Null while it is still a candidate. */
    @ColumnInfo(name = "promoted_by") val promotedBy: String? = null,
) {
    fun toModel(entries: List<FingerprintEntryEntity>) = FingerprintPoint(
        fingerprintId = fingerprintId,
        surveyPointId = surveyPointId,
        buildingId = buildingId,
        zoneId = zoneId,
        x = x,
        y = y,
        observerId = observerId,
        status = FingerprintStatus.entries.firstOrNull { it.name == status }
            ?: FingerprintStatus.CANDIDATE,
        sampleCount = sampleCount,
        sessionCount = sessionCount,
        sourceSurveySessionIds = sourceSurveySessionIds,
        engineVersion = engineVersion,
        createdAtUtc = createdAtUtc,
        entries = entries.map { it.toModel() },
    )

    companion object {
        fun from(point: FingerprintPoint, nowUtc: String) = FingerprintEntity(
            fingerprintId = point.fingerprintId,
            surveyPointId = point.surveyPointId,
            buildingId = point.buildingId,
            zoneId = point.zoneId,
            x = point.x,
            y = point.y,
            observerId = point.observerId,
            status = point.status.name,
            sampleCount = point.sampleCount,
            sessionCount = point.sessionCount,
            sourceSurveySessionIds = point.sourceSurveySessionIds,
            engineVersion = point.engineVersion,
            createdAtUtc = point.createdAtUtc ?: nowUtc,
            updatedAtUtc = nowUtc,
            promotedBy = null,
        )
    }
}

/** One radio source's *distribution* at a fingerprint. Never a single RSSI value. */
@Entity(
    tableName = "ref_fingerprint_entry",
    primaryKeys = ["fingerprint_id", "radio_identifier"],
    indices = [Index("radio_identifier")],
    foreignKeys = [
        ForeignKey(
            entity = FingerprintEntity::class,
            parentColumns = ["fingerprint_id"],
            childColumns = ["fingerprint_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FingerprintEntryEntity(
    @ColumnInfo(name = "fingerprint_id") val fingerprintId: String,
    @ColumnInfo(name = "radio_identifier") val radioIdentifier: String,
    @ColumnInfo(name = "identifier_type") val identifierType: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "visibility_probability") val visibilityProbability: Double,
    @ColumnInfo(name = "rssi_median") val rssiMedian: Double,
    @ColumnInfo(name = "rssi_mean") val rssiMean: Double?,
    @ColumnInfo(name = "rssi_stddev") val rssiStddev: Double?,
    @ColumnInfo(name = "rssi_p10") val rssiP10: Double?,
    @ColumnInfo(name = "rssi_p90") val rssiP90: Double?,
    @ColumnInfo(name = "rssi_min") val rssiMin: Double?,
    @ColumnInfo(name = "rssi_max") val rssiMax: Double?,
    @ColumnInfo(name = "temporal_stability") val temporalStability: Double?,
) {
    fun toModel() = FingerprintEntry(
        radioIdentifier = radioIdentifier,
        identifierType = IdentifierType.fromWireOrNull(identifierType) ?: IdentifierType.OTHER,
        sampleCount = sampleCount,
        visibilityProbability = visibilityProbability,
        rssiMedian = rssiMedian,
        rssiMean = rssiMean,
        rssiStddev = rssiStddev,
        rssiP10 = rssiP10,
        rssiP90 = rssiP90,
        rssiMin = rssiMin,
        rssiMax = rssiMax,
        temporalStability = temporalStability,
    )

    companion object {
        fun from(fingerprintId: String, entry: FingerprintEntry) = FingerprintEntryEntity(
            fingerprintId = fingerprintId,
            radioIdentifier = entry.radioIdentifier,
            identifierType = entry.identifierType.name,
            sampleCount = entry.sampleCount,
            visibilityProbability = entry.visibilityProbability,
            rssiMedian = entry.rssiMedian,
            rssiMean = entry.rssiMean,
            rssiStddev = entry.rssiStddev,
            rssiP10 = entry.rssiP10,
            rssiP90 = entry.rssiP90,
            rssiMin = entry.rssiMin,
            rssiMax = entry.rssiMax,
            temporalStability = entry.temporalStability,
        )
    }
}

@Entity(tableName = "ref_observer_calibration")
data class ObserverCalibrationEntity(
    @PrimaryKey @ColumnInfo(name = "observer_id") val observerId: String,
    @ColumnInfo(name = "rssi_offset_db") val rssiOffsetDb: Double,
    @ColumnInfo(name = "measured_at_utc") val measuredAtUtc: String?,
    @ColumnInfo(name = "sample_count") val sampleCount: Int?,
    @ColumnInfo(name = "spread_db") val spreadDb: Double?,
    @ColumnInfo(name = "method") val method: String?,
    @ColumnInfo(name = "notes") val notes: String?,
) {
    fun toModel() = ObserverCalibration(
        observerId = observerId,
        rssiOffsetDb = rssiOffsetDb,
        measuredAtUtc = measuredAtUtc,
        sampleCount = sampleCount,
        spreadDb = spreadDb,
        method = method,
        notes = notes,
    )

    companion object {
        fun from(calibration: ObserverCalibration) = ObserverCalibrationEntity(
            observerId = calibration.observerId,
            rssiOffsetDb = calibration.rssiOffsetDb,
            measuredAtUtc = calibration.measuredAtUtc,
            sampleCount = calibration.sampleCount,
            spreadDb = calibration.spreadDb,
            method = calibration.method,
            notes = calibration.notes,
        )
    }
}

/**
 * Polygon storage and the geometry derived from it.
 *
 * [enclosingRadius] is not decoration: a zone-centroid estimate reports it as the estimate's
 * horizontal uncertainty, so the error bar on a coarse estimate comes from the site's real geometry
 * rather than from a constant somebody picked.
 */
internal object Polygons {

    private val serializer = ListSerializer(Point.serializer())

    fun encode(polygon: List<Point>): String? =
        if (polygon.isEmpty()) null else RfMapperJson.compact.encodeToString(serializer, polygon)

    fun decode(json: String?): List<Point> =
        if (json.isNullOrBlank()) emptyList() else RfMapperJson.compact.decodeFromString(serializer, json)

    fun centroid(polygon: List<Point>): Point? {
        if (polygon.isEmpty()) return null
        return Point(x = polygon.sumOf { it.x } / polygon.size, y = polygon.sumOf { it.y } / polygon.size)
    }

    /** Radius of the smallest circle centred on the centroid that encloses every vertex. */
    fun enclosingRadius(polygon: List<Point>): Double? {
        val centre = centroid(polygon) ?: return null
        return polygon.maxOfOrNull { vertex ->
            val dx = vertex.x - centre.x
            val dy = vertex.y - centre.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }
}
