package com.rfmapper.data.room.reference

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagedDeviceDao {

    @Upsert
    suspend fun upsert(device: ManagedDeviceEntity)

    @Upsert
    suspend fun upsertAll(devices: List<ManagedDeviceEntity>)

    @Delete
    suspend fun delete(device: ManagedDeviceEntity)

    @Query("SELECT * FROM ref_managed_device ORDER BY friendly_name")
    fun observeAll(): Flow<List<ManagedDeviceEntity>>

    @Query("SELECT * FROM ref_managed_device WHERE device_id = :deviceId")
    suspend fun byId(deviceId: String): ManagedDeviceEntity?

    @Query("SELECT * FROM ref_managed_device WHERE status = :status ORDER BY friendly_name")
    suspend fun byStatus(status: String): List<ManagedDeviceEntity>

    @Query("SELECT COUNT(*) FROM ref_managed_device")
    fun observeCount(): Flow<Int>

    // -- identifiers ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentifiers(identifiers: List<DeviceIdentifierEntity>)

    @Query("DELETE FROM ref_device_identifier WHERE device_id = :deviceId")
    suspend fun deleteIdentifiersFor(deviceId: String)

    @Query("SELECT * FROM ref_device_identifier WHERE device_id = :deviceId ORDER BY identifier")
    suspend fun identifiersFor(deviceId: String): List<DeviceIdentifierEntity>

    @Query("SELECT * FROM ref_device_identifier ORDER BY identifier")
    suspend fun allIdentifiers(): List<DeviceIdentifierEntity>

    /**
     * The attribution lookup, run against observations in bulk.
     *
     * Bulk rather than per-row because attributing a day's import one identifier at a time would be
     * tens of thousands of round trips; the caller batches the distinct identifiers it saw and joins
     * the answer back in memory.
     */
    @Query(
        """
        SELECT * FROM ref_device_identifier
        WHERE identifier IN (:identifiers)
        """,
    )
    suspend fun attributionsFor(identifiers: List<String>): List<DeviceIdentifierEntity>

    @Query(
        """
        SELECT device_id FROM ref_device_identifier
        WHERE identifier = :identifier AND identifier_type = :identifierType
        """,
    )
    suspend fun findDeviceIdFor(identifier: String, identifierType: String): String?

    @Query(
        """
        UPDATE ref_managed_device
        SET last_seen_utc = :timestampUtc,
            first_seen_utc = COALESCE(first_seen_utc, :timestampUtc),
            updated_at_utc = :timestampUtc
        WHERE device_id = :deviceId
          AND (last_seen_utc IS NULL OR last_seen_utc < :timestampUtc)
        """,
    )
    suspend fun touchLastSeen(deviceId: String, timestampUtc: String)

    /** Replaces a device and its identifier set atomically, so attribution is never half-applied. */
    @Transaction
    suspend fun replaceDevice(device: ManagedDeviceEntity, identifiers: List<DeviceIdentifierEntity>) {
        upsert(device)
        deleteIdentifiersFor(device.deviceId)
        if (identifiers.isNotEmpty()) upsertIdentifiers(identifiers)
    }
}

@Dao
interface InfrastructureDao {

    @Upsert
    suspend fun upsert(node: InfrastructureNodeEntity)

    @Upsert
    suspend fun upsertAll(nodes: List<InfrastructureNodeEntity>)

    @Delete
    suspend fun delete(node: InfrastructureNodeEntity)

    @Query("SELECT * FROM ref_infrastructure_node ORDER BY building_id, friendly_name")
    fun observeAll(): Flow<List<InfrastructureNodeEntity>>

    @Query("SELECT * FROM ref_infrastructure_node ORDER BY building_id, friendly_name")
    suspend fun all(): List<InfrastructureNodeEntity>

    @Query("SELECT * FROM ref_infrastructure_node WHERE node_id = :nodeId")
    suspend fun byId(nodeId: String): InfrastructureNodeEntity?

    @Query("SELECT * FROM ref_infrastructure_node WHERE building_id = :buildingId ORDER BY friendly_name")
    suspend fun byBuilding(buildingId: String): List<InfrastructureNodeEntity>

    @Query("SELECT * FROM ref_infrastructure_node WHERE known_bssid = :bssid LIMIT 1")
    suspend fun byBssid(bssid: String): InfrastructureNodeEntity?

    @Query("SELECT * FROM ref_infrastructure_node WHERE known_bssid IN (:bssids)")
    suspend fun byBssids(bssids: List<String>): List<InfrastructureNodeEntity>

    /** Ranging targets. Only nodes from which a genuine RTT range has actually been obtained. */
    @Query("SELECT * FROM ref_infrastructure_node WHERE rtt_capable = 1 AND known_bssid IS NOT NULL")
    suspend fun rttCapableNodes(): List<InfrastructureNodeEntity>

    @Query("SELECT COUNT(*) FROM ref_infrastructure_node")
    fun observeCount(): Flow<Int>
}

@Dao
interface ObserverDao {

    @Upsert
    suspend fun upsert(observer: ObserverEntity)

    @Query("SELECT * FROM ref_observer ORDER BY friendly_name")
    fun observeAll(): Flow<List<ObserverEntity>>

    @Query("SELECT * FROM ref_observer ORDER BY friendly_name")
    suspend fun all(): List<ObserverEntity>

    @Query("SELECT * FROM ref_observer WHERE observer_id = :observerId")
    suspend fun byId(observerId: String): ObserverEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM ref_observer WHERE observer_id = :observerId AND enrolled = 1)")
    suspend fun isEnrolled(observerId: String): Boolean

    @Query("SELECT observer_id FROM ref_observer WHERE enrolled = 1")
    suspend fun enrolledIds(): List<String>

    @Query("UPDATE ref_observer SET enrolled = :enrolled, enrolled_at_utc = :atUtc WHERE observer_id = :observerId")
    suspend fun setEnrolled(observerId: String, enrolled: Boolean, atUtc: String?)

    @Delete
    suspend fun delete(observer: ObserverEntity)
}

@Dao
interface SiteModelDao {

    @Upsert
    suspend fun upsertBuildings(buildings: List<BuildingEntity>)

    @Upsert
    suspend fun upsertZones(zones: List<ZoneEntity>)

    @Upsert
    suspend fun upsertEdges(edges: List<ZoneEdgeEntity>)

    @Upsert
    suspend fun upsertSurveyPoints(points: List<SurveyPointEntity>)

    @Query("SELECT * FROM ref_building ORDER BY building_id")
    fun observeBuildings(): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM ref_building ORDER BY building_id")
    suspend fun buildings(): List<BuildingEntity>

    @Query("SELECT * FROM ref_zone ORDER BY building_id, floor, zone_id")
    fun observeZones(): Flow<List<ZoneEntity>>

    @Query("SELECT * FROM ref_zone ORDER BY building_id, floor, zone_id")
    suspend fun zones(): List<ZoneEntity>

    @Query("SELECT * FROM ref_zone WHERE building_id = :buildingId ORDER BY floor, zone_id")
    suspend fun zonesIn(buildingId: String): List<ZoneEntity>

    @Query("SELECT * FROM ref_zone WHERE zone_id = :zoneId")
    suspend fun zone(zoneId: String): ZoneEntity?

    @Query("SELECT * FROM ref_zone_edge")
    suspend fun edges(): List<ZoneEdgeEntity>

    /**
     * Whether two zones are connected, in either direction for a bidirectional edge.
     *
     * A missing edge is *not* proof of impossibility — an unauthored door is far more likely than a
     * teleporting device — which is why the topology check reports `UNKNOWN_EDGE` rather than
     * rejecting the transition (`docs/10-positioning-mathematical-architecture.md`).
     */
    @Query(
        """
        SELECT * FROM ref_zone_edge
        WHERE (from_zone_id = :a AND to_zone_id = :b)
           OR (bidirectional = 1 AND from_zone_id = :b AND to_zone_id = :a)
        LIMIT 1
        """,
    )
    suspend fun edgeBetween(a: String, b: String): ZoneEdgeEntity?

    @Delete
    suspend fun deleteEdge(edge: ZoneEdgeEntity)

    @Query("SELECT * FROM ref_survey_point ORDER BY building_id, survey_point_id")
    fun observeSurveyPoints(): Flow<List<SurveyPointEntity>>

    @Query("SELECT * FROM ref_survey_point ORDER BY building_id, survey_point_id")
    suspend fun surveyPoints(): List<SurveyPointEntity>

    @Query("SELECT * FROM ref_survey_point WHERE survey_point_id = :surveyPointId")
    suspend fun surveyPoint(surveyPointId: String): SurveyPointEntity?

    @Query("SELECT * FROM ref_survey_point WHERE building_id = :buildingId ORDER BY survey_point_id")
    suspend fun surveyPointsIn(buildingId: String): List<SurveyPointEntity>

    @Delete
    suspend fun deleteSurveyPoint(point: SurveyPointEntity)
}

@Dao
interface FingerprintDao {

    @Upsert
    suspend fun upsertFingerprint(fingerprint: FingerprintEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<FingerprintEntryEntity>)

    @Query("DELETE FROM ref_fingerprint_entry WHERE fingerprint_id = :fingerprintId")
    suspend fun deleteEntriesFor(fingerprintId: String)

    /** A fingerprint and its source distributions are one fact, so they are written as one. */
    @Transaction
    suspend fun replaceFingerprint(
        fingerprint: FingerprintEntity,
        entries: List<FingerprintEntryEntity>,
    ) {
        upsertFingerprint(fingerprint)
        deleteEntriesFor(fingerprint.fingerprintId)
        if (entries.isNotEmpty()) upsertEntries(entries)
    }

    @Query("SELECT * FROM ref_fingerprint WHERE fingerprint_id = :fingerprintId")
    suspend fun byId(fingerprintId: String): FingerprintEntity?

    @Query("SELECT * FROM ref_fingerprint ORDER BY building_id, survey_point_id")
    fun observeAll(): Flow<List<FingerprintEntity>>

    @Query("SELECT * FROM ref_fingerprint WHERE status = 'GROUND_TRUTH' ORDER BY building_id, survey_point_id")
    suspend fun groundTruthFingerprints(): List<FingerprintEntity>

    @Query("SELECT * FROM ref_fingerprint WHERE status = 'CANDIDATE' ORDER BY created_at_utc")
    suspend fun candidates(): List<FingerprintEntity>

    @Query("SELECT * FROM ref_fingerprint WHERE survey_point_id = :surveyPointId")
    suspend fun forSurveyPoint(surveyPointId: String): List<FingerprintEntity>

    @Query("SELECT * FROM ref_fingerprint_entry WHERE fingerprint_id = :fingerprintId ORDER BY radio_identifier")
    suspend fun entriesFor(fingerprintId: String): List<FingerprintEntryEntity>

    @Query("SELECT * FROM ref_fingerprint_entry WHERE fingerprint_id IN (:fingerprintIds)")
    suspend fun entriesForAll(fingerprintIds: List<String>): List<FingerprintEntryEntity>

    /**
     * The promotion gate, and the only way a fingerprint becomes ground truth.
     *
     * [promotedBy] and the timestamp are required arguments because "who decided this was ground
     * truth, and when" is the entire difference between calibration data and a guess.
     */
    @Query(
        """
        UPDATE ref_fingerprint
        SET status = 'GROUND_TRUTH', updated_at_utc = :atUtc, promoted_by = :promotedBy
        WHERE fingerprint_id = :fingerprintId AND status = 'CANDIDATE'
        """,
    )
    suspend fun promote(fingerprintId: String, promotedBy: String, atUtc: String): Int

    @Query(
        """
        UPDATE ref_fingerprint SET status = 'RETIRED', updated_at_utc = :atUtc
        WHERE fingerprint_id = :fingerprintId
        """,
    )
    suspend fun retire(fingerprintId: String, atUtc: String): Int
}

@Dao
interface ObserverCalibrationDao {

    @Upsert
    suspend fun upsert(calibration: ObserverCalibrationEntity)

    @Query("SELECT * FROM ref_observer_calibration")
    suspend fun all(): List<ObserverCalibrationEntity>

    @Query("SELECT * FROM ref_observer_calibration WHERE observer_id = :observerId")
    suspend fun byObserver(observerId: String): ObserverCalibrationEntity?

    @Delete
    suspend fun delete(calibration: ObserverCalibrationEntity)
}
