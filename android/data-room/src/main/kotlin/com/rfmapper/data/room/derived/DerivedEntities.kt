package com.rfmapper.data.room.derived

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MovementEstimate
import com.rfmapper.core.model.MovementState
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.core.model.QualityFlag
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.TopologyStatus
import com.rfmapper.core.model.ZoneEventType
import com.rfmapper.core.model.ZoneTransition
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The DERIVED layer: everything the Positioning Lab computed, and nothing the Master computed
 * itself.
 *
 * Every row carries [algorithmVersion], and reprocessing inserts a *new generation* rather than
 * overwriting an old one. Two consequences follow, both deliberate. An estimate shown on a map last
 * month can still be reproduced exactly, because the inputs and the algorithm that produced it are
 * both still identified. And a bad algorithm version is discarded by deleting a generation, which
 * cannot damage the raw evidence underneath it.
 *
 * @see <a href="../../../../../../../../../../docs/04-room-entity-dao-design.md">docs/04, §4</a>
 */
@Entity(
    tableName = "der_position_estimate",
    indices = [
        Index("device_id", "timestamp_epoch_ms"),
        Index("algorithm_version"),
        Index("building_id", "zone_id"),
        Index("timestamp_epoch_ms"),
    ],
)
data class PositionEstimateEntity(
    @PrimaryKey @ColumnInfo(name = "estimate_id") val estimateId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "engine_versions") val engineVersions: String,
    @ColumnInfo(name = "parameter_set_sha256") val parameterSetSha256: String?,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: String,
    @ColumnInfo(name = "timestamp_epoch_ms") val timestampEpochMs: Long,
    @ColumnInfo(name = "computed_at_utc") val computedAtUtc: String,
    @ColumnInfo(name = "precision_tier") val precisionTier: String,
    @ColumnInfo(name = "building_id") val buildingId: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "x") val x: Double?,
    @ColumnInfo(name = "y") val y: Double?,
    @ColumnInfo(name = "horizontal_uncertainty_m") val horizontalUncertaintyM: Double?,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "confidence_factors") val confidenceFactors: String,
    @ColumnInfo(name = "method") val method: String,
    @ColumnInfo(name = "supporting_observer_ids") val supportingObserverIds: List<String>,
    @ColumnInfo(name = "supporting_observation_ids") val supportingObservationIds: List<String>,
    @ColumnInfo(name = "source_dataset_ids") val sourceDatasetIds: List<String>,
    @ColumnInfo(name = "reference_model_id") val referenceModelId: String?,
    @ColumnInfo(name = "calibration_set_id") val calibrationSetId: String?,
    @ColumnInfo(name = "quality_flags") val qualityFlags: List<String>,
) {
    fun toModel() = PositionEstimate(
        estimateId = estimateId,
        algorithmVersion = algorithmVersion,
        engineVersions = DerivedJson.decodeStringMap(engineVersions),
        parameterSetSha256 = parameterSetSha256,
        deviceId = deviceId,
        timestampUtc = timestampUtc,
        computedAtUtc = computedAtUtc,
        precisionTier = PrecisionTier.entries.first { it.name == precisionTier },
        buildingId = buildingId,
        zoneId = zoneId,
        x = x,
        y = y,
        horizontalUncertaintyM = horizontalUncertaintyM,
        confidence = confidence,
        confidenceFactors = DerivedJson.decodeDoubleMap(confidenceFactors),
        method = method,
        supportingObserverIds = supportingObserverIds,
        supportingObservationIds = supportingObservationIds,
        sourceDatasetIds = sourceDatasetIds,
        referenceModelId = referenceModelId,
        calibrationSetId = calibrationSetId,
        qualityFlags = qualityFlags,
    )

    companion object {
        fun from(estimate: PositionEstimate) = PositionEstimateEntity(
            estimateId = estimate.estimateId,
            algorithmVersion = estimate.algorithmVersion,
            engineVersions = DerivedJson.encodeStringMap(estimate.engineVersions),
            parameterSetSha256 = estimate.parameterSetSha256,
            deviceId = estimate.deviceId,
            timestampUtc = estimate.timestampUtc,
            timestampEpochMs = requireNotNull(Iso8601.parseToEpochMillis(estimate.timestampUtc)) {
                "estimate ${estimate.estimateId} has an unparseable timestamp"
            },
            computedAtUtc = estimate.computedAtUtc,
            precisionTier = estimate.precisionTier.name,
            buildingId = estimate.buildingId,
            zoneId = estimate.zoneId,
            x = estimate.x,
            y = estimate.y,
            horizontalUncertaintyM = estimate.horizontalUncertaintyM,
            confidence = estimate.confidence,
            confidenceFactors = DerivedJson.encodeDoubleMap(estimate.confidenceFactors),
            method = estimate.method,
            supportingObserverIds = estimate.supportingObserverIds,
            supportingObservationIds = estimate.supportingObservationIds,
            sourceDatasetIds = estimate.sourceDatasetIds,
            referenceModelId = estimate.referenceModelId,
            calibrationSetId = estimate.calibrationSetId,
            qualityFlags = estimate.qualityFlags,
        )
    }
}

@Entity(
    tableName = "der_zone_transition",
    indices = [
        Index("device_id", "transition_start_epoch_ms"),
        Index("algorithm_version"),
        Index("topology_status"),
    ],
)
data class ZoneTransitionEntity(
    @PrimaryKey @ColumnInfo(name = "transition_id") val transitionId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "engine_versions") val engineVersions: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "origin_zone_id") val originZoneId: String?,
    @ColumnInfo(name = "destination_zone_id") val destinationZoneId: String?,
    @ColumnInfo(name = "transition_start_utc") val transitionStartUtc: String,
    @ColumnInfo(name = "transition_start_epoch_ms") val transitionStartEpochMs: Long,
    @ColumnInfo(name = "transition_confirmed_utc") val transitionConfirmedUtc: String,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "topology_status") val topologyStatus: String,
    @ColumnInfo(name = "supporting_observer_ids") val supportingObserverIds: List<String>,
    @ColumnInfo(name = "supporting_estimate_ids") val supportingEstimateIds: List<String>,
    @ColumnInfo(name = "quality_flags") val qualityFlags: List<String>,
) {
    fun toModel() = ZoneTransition(
        transitionId = transitionId,
        algorithmVersion = algorithmVersion,
        engineVersions = DerivedJson.decodeStringMap(engineVersions),
        deviceId = deviceId,
        eventType = ZoneEventType.entries.first { it.name == eventType },
        originZoneId = originZoneId,
        destinationZoneId = destinationZoneId,
        transitionStartUtc = transitionStartUtc,
        transitionConfirmedUtc = transitionConfirmedUtc,
        confidence = confidence,
        topologyStatus = TopologyStatus.entries.first { it.name == topologyStatus },
        supportingObserverIds = supportingObserverIds,
        supportingEstimateIds = supportingEstimateIds,
        qualityFlags = qualityFlags,
    )

    companion object {
        fun from(transition: ZoneTransition) = ZoneTransitionEntity(
            transitionId = transition.transitionId,
            algorithmVersion = transition.algorithmVersion,
            engineVersions = DerivedJson.encodeStringMap(transition.engineVersions),
            deviceId = transition.deviceId,
            eventType = transition.eventType.name,
            originZoneId = transition.originZoneId,
            destinationZoneId = transition.destinationZoneId,
            transitionStartUtc = transition.transitionStartUtc,
            transitionStartEpochMs = requireNotNull(
                Iso8601.parseToEpochMillis(transition.transitionStartUtc),
            ) { "transition ${transition.transitionId} has an unparseable start timestamp" },
            transitionConfirmedUtc = transition.transitionConfirmedUtc,
            confidence = transition.confidence,
            topologyStatus = transition.topologyStatus.name,
            supportingObserverIds = transition.supportingObserverIds,
            supportingEstimateIds = transition.supportingEstimateIds,
            qualityFlags = transition.qualityFlags,
        )
    }
}

@Entity(
    tableName = "der_movement_estimate",
    indices = [Index("device_id", "timestamp_epoch_ms"), Index("algorithm_version")],
)
data class MovementEstimateEntity(
    @PrimaryKey @ColumnInfo(name = "movement_id") val movementId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "engine_versions") val engineVersions: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: String,
    @ColumnInfo(name = "timestamp_epoch_ms") val timestampEpochMs: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "origin_zone_id") val originZoneId: String?,
    @ColumnInfo(name = "candidate_destination_zone_id") val candidateDestinationZoneId: String?,
    @ColumnInfo(name = "confirmed_destination_zone_id") val confirmedDestinationZoneId: String?,
    @ColumnInfo(name = "direction") val direction: String?,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "supporting_estimate_ids") val supportingEstimateIds: List<String>,
    @ColumnInfo(name = "quality_flags") val qualityFlags: List<String>,
) {
    fun toModel() = MovementEstimate(
        movementId = movementId,
        algorithmVersion = algorithmVersion,
        engineVersions = DerivedJson.decodeStringMap(engineVersions),
        deviceId = deviceId,
        timestampUtc = timestampUtc,
        state = MovementState.entries.first { it.name == state },
        originZoneId = originZoneId,
        candidateDestinationZoneId = candidateDestinationZoneId,
        confirmedDestinationZoneId = confirmedDestinationZoneId,
        direction = direction,
        confidence = confidence,
        supportingEstimateIds = supportingEstimateIds,
        qualityFlags = qualityFlags,
    )

    companion object {
        fun from(movement: MovementEstimate) = MovementEstimateEntity(
            movementId = movement.movementId,
            algorithmVersion = movement.algorithmVersion,
            engineVersions = DerivedJson.encodeStringMap(movement.engineVersions),
            deviceId = movement.deviceId,
            timestampUtc = movement.timestampUtc,
            timestampEpochMs = requireNotNull(Iso8601.parseToEpochMillis(movement.timestampUtc)) {
                "movement ${movement.movementId} has an unparseable timestamp"
            },
            state = movement.state.name,
            originZoneId = movement.originZoneId,
            candidateDestinationZoneId = movement.candidateDestinationZoneId,
            confirmedDestinationZoneId = movement.confirmedDestinationZoneId,
            direction = movement.direction,
            confidence = movement.confidence,
            supportingEstimateIds = movement.supportingEstimateIds,
            qualityFlags = movement.qualityFlags,
        )
    }
}

/**
 * An advisory finding for a human to act on.
 *
 * Nothing consumes a flag to change behaviour automatically. "Building 4's fingerprint could be
 * improved" is a prompt to go and resurvey, not a licence for the software to start adjusting its
 * own calibration — which is the difference between a system whose accuracy an administrator can
 * reason about and one that drifts on its own.
 */
@Entity(
    tableName = "der_quality_flag",
    indices = [Index("algorithm_version"), Index("severity"), Index("scope", "scope_id")],
)
data class QualityFlagEntity(
    @PrimaryKey @ColumnInfo(name = "flag_id") val flagId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String,
    @ColumnInfo(name = "severity") val severity: String,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "evidence") val evidence: Map<String, String>,
    @ColumnInfo(name = "acknowledged_at_utc") val acknowledgedAtUtc: String?,
    @ColumnInfo(name = "acknowledged_by") val acknowledgedBy: String?,
) {
    fun toModel() = QualityFlag(
        flagId = flagId,
        algorithmVersion = algorithmVersion,
        createdAtUtc = createdAtUtc,
        severity = QualityFlag.Severity.entries.first { it.name == severity },
        code = code,
        scope = scope,
        scopeId = scopeId,
        message = message,
        evidence = evidence,
        acknowledgedAtUtc = acknowledgedAtUtc,
        acknowledgedBy = acknowledgedBy,
    )

    companion object {
        fun from(flag: QualityFlag) = QualityFlagEntity(
            flagId = flag.flagId,
            algorithmVersion = flag.algorithmVersion,
            createdAtUtc = flag.createdAtUtc,
            severity = flag.severity.name,
            code = flag.code,
            scope = flag.scope,
            scopeId = flag.scopeId,
            message = flag.message,
            evidence = flag.evidence,
            acknowledgedAtUtc = flag.acknowledgedAtUtc,
            acknowledgedBy = flag.acknowledgedBy,
        )
    }
}

/**
 * One imported derived generation. Recorded so the UI can offer "show estimates from version X" and
 * so an administrator can discard a superseded generation deliberately.
 */
@Entity(tableName = "der_generation", indices = [Index("imported_at_utc")])
data class DerivedGenerationEntity(
    @PrimaryKey @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "export_id") val exportId: String,
    @ColumnInfo(name = "imported_at_utc") val importedAtUtc: String,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String,
    @ColumnInfo(name = "engine_versions") val engineVersions: String,
    @ColumnInfo(name = "dataset_kind") val datasetKind: String,
    @ColumnInfo(name = "source_dataset_ids") val sourceDatasetIds: List<String>,
    @ColumnInfo(name = "estimate_count") val estimateCount: Long,
    @ColumnInfo(name = "transition_count") val transitionCount: Long,
    @ColumnInfo(name = "movement_count") val movementCount: Long,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

internal object DerivedJson {
    private val stringMap = MapSerializer(String.serializer(), String.serializer())
    private val doubleMap = MapSerializer(String.serializer(), Double.serializer())

    fun encodeStringMap(value: Map<String, String>): String =
        RfMapperJson.compact.encodeToString(stringMap, value.toSortedMap())

    fun decodeStringMap(value: String): Map<String, String> =
        if (value.isBlank()) emptyMap() else RfMapperJson.compact.decodeFromString(stringMap, value)

    fun encodeDoubleMap(value: Map<String, Double>): String =
        RfMapperJson.compact.encodeToString(doubleMap, value.toSortedMap())

    fun decodeDoubleMap(value: String): Map<String, Double> =
        if (value.isBlank()) emptyMap() else RfMapperJson.compact.decodeFromString(doubleMap, value)
}

@Dao
interface DerivedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEstimates(rows: List<PositionEstimateEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransitions(rows: List<ZoneTransitionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMovements(rows: List<MovementEstimateEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFlags(rows: List<QualityFlagEntity>): List<Long>

    @Upsert
    suspend fun upsertGeneration(generation: DerivedGenerationEntity)

    /**
     * One derived package is one fact about one algorithm version, so it lands atomically. A
     * half-imported generation would show a device's transitions without the estimates that
     * justified them.
     */
    @Transaction
    suspend fun importGeneration(
        generation: DerivedGenerationEntity,
        estimates: List<PositionEstimateEntity>,
        transitions: List<ZoneTransitionEntity>,
        movements: List<MovementEstimateEntity>,
        flags: List<QualityFlagEntity>,
    ) {
        upsertGeneration(generation)
        if (estimates.isNotEmpty()) insertEstimates(estimates)
        if (transitions.isNotEmpty()) insertTransitions(transitions)
        if (movements.isNotEmpty()) insertMovements(movements)
        if (flags.isNotEmpty()) insertFlags(flags)
    }

    @Query("SELECT * FROM der_generation ORDER BY imported_at_utc DESC")
    fun observeGenerations(): Flow<List<DerivedGenerationEntity>>

    @Query("SELECT * FROM der_generation WHERE is_active = 1 LIMIT 1")
    suspend fun activeGeneration(): DerivedGenerationEntity?

    @Query("UPDATE der_generation SET is_active = (algorithm_version = :algorithmVersion)")
    suspend fun setActiveGeneration(algorithmVersion: String)

    /**
     * The latest estimate per device for one algorithm version: the "where is everything now" view.
     *
     * The correlated subquery picks each device's own maximum timestamp, which a plain
     * `GROUP BY device_id` could not do without returning fields from arbitrary rows.
     */
    @Query(
        """
        SELECT e.* FROM der_position_estimate e
        WHERE e.algorithm_version = :algorithmVersion
          AND e.timestamp_epoch_ms = (
                SELECT MAX(inner_e.timestamp_epoch_ms) FROM der_position_estimate inner_e
                WHERE inner_e.device_id = e.device_id AND inner_e.algorithm_version = :algorithmVersion
              )
        GROUP BY e.device_id
        ORDER BY e.device_id
        """,
    )
    fun observeLatestPerDevice(algorithmVersion: String): Flow<List<PositionEstimateEntity>>

    @Query(
        """
        SELECT * FROM der_position_estimate
        WHERE device_id = :deviceId AND algorithm_version = :algorithmVersion
          AND timestamp_epoch_ms >= :fromEpochMs AND timestamp_epoch_ms <= :toEpochMs
        ORDER BY timestamp_epoch_ms ASC
        """,
    )
    suspend fun estimatesForDevice(
        deviceId: String,
        algorithmVersion: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<PositionEstimateEntity>

    @Query(
        """
        SELECT * FROM der_zone_transition
        WHERE device_id = :deviceId AND algorithm_version = :algorithmVersion
        ORDER BY transition_start_epoch_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun transitionsForDevice(
        deviceId: String,
        algorithmVersion: String,
        limit: Int,
    ): List<ZoneTransitionEntity>

    @Query(
        """
        SELECT * FROM der_movement_estimate
        WHERE device_id = :deviceId AND algorithm_version = :algorithmVersion
        ORDER BY timestamp_epoch_ms DESC LIMIT :limit
        """,
    )
    suspend fun movementsForDevice(
        deviceId: String,
        algorithmVersion: String,
        limit: Int,
    ): List<MovementEstimateEntity>

    @Query("SELECT * FROM der_quality_flag WHERE acknowledged_at_utc IS NULL ORDER BY created_at_utc DESC")
    fun observeOpenFlags(): Flow<List<QualityFlagEntity>>

    @Query(
        """
        UPDATE der_quality_flag SET acknowledged_at_utc = :atUtc, acknowledged_by = :by
        WHERE flag_id = :flagId
        """,
    )
    suspend fun acknowledgeFlag(flagId: String, by: String, atUtc: String)

    // -- generation management --------------------------------------------------------------------

    @Query("DELETE FROM der_position_estimate WHERE algorithm_version = :algorithmVersion")
    suspend fun deleteEstimatesByVersion(algorithmVersion: String): Int

    @Query("DELETE FROM der_zone_transition WHERE algorithm_version = :algorithmVersion")
    suspend fun deleteTransitionsByVersion(algorithmVersion: String): Int

    @Query("DELETE FROM der_movement_estimate WHERE algorithm_version = :algorithmVersion")
    suspend fun deleteMovementsByVersion(algorithmVersion: String): Int

    @Query("DELETE FROM der_quality_flag WHERE algorithm_version = :algorithmVersion")
    suspend fun deleteFlagsByVersion(algorithmVersion: String): Int

    @Query("DELETE FROM der_generation WHERE algorithm_version = :algorithmVersion")
    suspend fun deleteGeneration(algorithmVersion: String): Int

    /** Discards one superseded generation. Derived data is regenerable; raw data is not touched. */
    @Transaction
    suspend fun deleteByAlgorithmVersion(algorithmVersion: String): Int {
        val removed = deleteEstimatesByVersion(algorithmVersion) +
            deleteTransitionsByVersion(algorithmVersion) +
            deleteMovementsByVersion(algorithmVersion) +
            deleteFlagsByVersion(algorithmVersion)
        deleteGeneration(algorithmVersion)
        return removed
    }
}
