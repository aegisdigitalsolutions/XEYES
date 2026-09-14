package com.rfmapper.data.room.raw

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType

/**
 * The RAW layer: one immutable observation as it was collected.
 *
 * Enum-typed fields are stored as their names rather than as ordinals. An ordinal would silently
 * remap every stored row the day somebody inserts a value into the middle of an enum, and these
 * rows are field work that cannot be recollected.
 *
 * [timestampUtc] is kept as text *and* as [timestampEpochMs]. The text form is what makes an export
 * byte-identical to what was collected; the epoch form is what makes a range scan use an index
 * instead of comparing strings. Both are written in one place, [from], so they cannot drift apart.
 *
 * @see <a href="../../../../../../../../../../docs/04-room-entity-dao-design.md">docs/04, §2</a>
 */
@Entity(
    tableName = "raw_observation",
    indices = [
        Index("timestamp_epoch_ms"),
        Index("observer_id", "timestamp_epoch_ms"),
        Index("radio_identifier", "timestamp_epoch_ms"),
        Index("target_device_id", "timestamp_epoch_ms"),
        Index("sensor_type", "timestamp_epoch_ms"),
        Index("session_id"),
        Index("sample_kind"),
        Index("import_batch_id"),
    ],
)
data class RawObservationEntity(
    @PrimaryKey
    @ColumnInfo(name = "observation_id")
    val observationId: String,

    @ColumnInfo(name = "schema_version") val schemaVersion: String,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: String,
    @ColumnInfo(name = "timestamp_epoch_ms") val timestampEpochMs: Long,
    @ColumnInfo(name = "observer_id") val observerId: String,
    @ColumnInfo(name = "observer_device_type") val observerDeviceType: String,
    @ColumnInfo(name = "sensor_type") val sensorType: String,
    @ColumnInfo(name = "target_device_id") val targetDeviceId: String?,
    @ColumnInfo(name = "radio_identifier") val radioIdentifier: String,
    @ColumnInfo(name = "identifier_type") val identifierType: String,
    @ColumnInfo(name = "ssid") val ssid: String?,
    @ColumnInfo(name = "bssid") val bssid: String?,
    @ColumnInfo(name = "ble_service_uuid") val bleServiceUuid: String?,
    @ColumnInfo(name = "manufacturer_data") val manufacturerData: String?,
    @ColumnInfo(name = "rssi") val rssi: Int?,
    @ColumnInfo(name = "tx_power") val txPower: Int?,
    @ColumnInfo(name = "frequency") val frequency: Int?,
    @ColumnInfo(name = "channel") val channel: Int?,
    @ColumnInfo(name = "rtt_distance_mm") val rttDistanceMm: Int?,
    @ColumnInfo(name = "rtt_stddev_mm") val rttStddevMm: Int?,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "horizontal_accuracy") val horizontalAccuracy: Double?,
    @ColumnInfo(name = "building_id") val buildingId: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "x_coordinate") val xCoordinate: Double?,
    @ColumnInfo(name = "y_coordinate") val yCoordinate: Double?,
    @ColumnInfo(name = "confidence") val confidence: Double?,
    @ColumnInfo(name = "metadata") val metadata: Map<String, String>,

    // Storage-only columns. Denormalised out of [metadata] because the dashboard and the survey
    // flow query on them constantly, and `json_extract` over a TEXT column cannot use an index.
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "sample_kind") val sampleKind: String,
    @ColumnInfo(name = "survey_point_id") val surveyPointId: String?,

    /** Null on the Collector; set on the Master to the batch this row arrived in. */
    @ColumnInfo(name = "import_batch_id") val importBatchId: String?,
) {
    /**
     * Rebuilds the canonical observation.
     *
     * Any storage value the domain model rejects is a bug in a writer, not something to paper over,
     * so this throws rather than substituting a default. A corrupted row that reads back as a
     * plausible observation would be far more expensive to diagnose than one that fails loudly.
     */
    fun toObservation(): Observation = Observation(
        observationId = observationId,
        schemaVersion = schemaVersion,
        timestampUtc = timestampUtc,
        observerId = observerId,
        observerDeviceType = requireNotNull(ObserverDeviceType.fromWireOrNull(observerDeviceType)) {
            "row $observationId has unknown observer_device_type '$observerDeviceType'"
        },
        sensorType = requireNotNull(SensorType.fromWireOrNull(sensorType)) {
            "row $observationId has unknown sensor_type '$sensorType'"
        },
        targetDeviceId = targetDeviceId,
        radioIdentifier = radioIdentifier,
        identifierType = requireNotNull(IdentifierType.fromWireOrNull(identifierType)) {
            "row $observationId has unknown identifier_type '$identifierType'"
        },
        ssid = ssid,
        bssid = bssid,
        bleServiceUuid = bleServiceUuid,
        manufacturerData = manufacturerData,
        rssi = rssi,
        txPower = txPower,
        frequency = frequency,
        channel = channel,
        rttDistanceMm = rttDistanceMm,
        rttStddevMm = rttStddevMm,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracy = horizontalAccuracy,
        buildingId = buildingId,
        zoneId = zoneId,
        xCoordinate = xCoordinate,
        yCoordinate = yCoordinate,
        confidence = confidence,
        metadata = metadata,
    )

    companion object {
        fun from(observation: Observation, importBatchId: String? = null) = RawObservationEntity(
            observationId = observation.observationId,
            schemaVersion = observation.schemaVersion,
            timestampUtc = observation.timestampUtc,
            timestampEpochMs = observation.timestampEpochMillis,
            observerId = observation.observerId,
            observerDeviceType = observation.observerDeviceType.name,
            sensorType = observation.sensorType.name,
            targetDeviceId = observation.targetDeviceId,
            radioIdentifier = observation.radioIdentifier,
            identifierType = observation.identifierType.name,
            ssid = observation.ssid,
            bssid = observation.bssid,
            bleServiceUuid = observation.bleServiceUuid,
            manufacturerData = observation.manufacturerData,
            rssi = observation.rssi,
            txPower = observation.txPower,
            frequency = observation.frequency,
            channel = observation.channel,
            rttDistanceMm = observation.rttDistanceMm,
            rttStddevMm = observation.rttStddevMm,
            latitude = observation.latitude,
            longitude = observation.longitude,
            horizontalAccuracy = observation.horizontalAccuracy,
            buildingId = observation.buildingId,
            zoneId = observation.zoneId,
            xCoordinate = observation.xCoordinate,
            yCoordinate = observation.yCoordinate,
            confidence = observation.confidence,
            metadata = observation.metadata,
            sessionId = observation.sessionId,
            sampleKind = observation.sampleKind.name,
            surveyPointId = observation.metadata[MetadataKeys.SURVEY_POINT_ID],
            importBatchId = importBatchId,
        )

        val GROUND_TRUTH = SampleKind.GROUND_TRUTH.name
    }
}
