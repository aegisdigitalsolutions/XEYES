package com.rfmapper.data.room.raw

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The RAW layer's data access, and the shape of this interface is the point of it.
 *
 * **There is no `@Update` and no `@Delete` here.** A raw observation is a statement about something
 * that happened at an instant; editing one would make the RAW layer unciteable and every derived
 * estimate built on it unreproducible. Adding a mutator would be a visible, reviewable diff rather
 * than an accident — which is why immutability lives in the type system instead of in a convention.
 *
 * Purging by retention policy is a separate, explicitly administrative operation: see
 * [com.rfmapper.data.room.raw.RetentionDao].
 *
 * @see <a href="../../../../../../../../../../docs/04-room-entity-dao-design.md">docs/04, §1</a>
 */
@Dao
interface ObservationDao {

    /**
     * The only write path, and the deduplication mechanism.
     *
     * `IGNORE` against a primary key of `observation_id` makes re-importing a package a no-op *by
     * construction*: atomic, transaction-safe, and immune to the check-then-write race that a
     * "select existing ids, then insert the rest" implementation would have. The returned row ids
     * carry `-1` for every row that was already present, which is exactly the duplicate count the
     * import report needs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RawObservationEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM raw_observation")
    suspend fun countAll(): Long

    @Query("SELECT COUNT(*) FROM raw_observation")
    fun observeCount(): Flow<Long>

    @Query(
        """
        SELECT sensor_type AS sensorType, COUNT(*) AS count
        FROM raw_observation
        GROUP BY sensor_type
        ORDER BY sensor_type
        """,
    )
    suspend fun countBySensorType(): List<SensorTypeCount>

    @Query(
        """
        SELECT sensor_type AS sensorType, COUNT(*) AS count
        FROM raw_observation
        WHERE timestamp_epoch_ms >= :fromEpochMs AND timestamp_epoch_ms <= :toEpochMs
        GROUP BY sensor_type
        ORDER BY sensor_type
        """,
    )
    suspend fun countBySensorTypeInRange(fromEpochMs: Long, toEpochMs: Long): List<SensorTypeCount>

    /** Push-driven dashboard counters, so the UI does not poll a database while the radios are busy. */
    @Query(
        """
        SELECT
            COUNT(*)                                                   AS total,
            SUM(CASE WHEN sensor_type IN ('WIFI_SCAN','WIFI_ASSOCIATION') THEN 1 ELSE 0 END) AS wifi,
            SUM(CASE WHEN sensor_type = 'BLE'  THEN 1 ELSE 0 END)      AS ble,
            SUM(CASE WHEN sensor_type = 'RTT'  THEN 1 ELSE 0 END)      AS rtt,
            SUM(CASE WHEN sensor_type = 'GPS'  THEN 1 ELSE 0 END)      AS gps,
            SUM(CASE WHEN sample_kind = 'GROUND_TRUTH' THEN 1 ELSE 0 END) AS groundTruth,
            COUNT(DISTINCT radio_identifier)                           AS distinctIdentifiers,
            MAX(timestamp_epoch_ms)                                    AS lastObservationEpochMs
        FROM raw_observation
        WHERE session_id = :sessionId
        """,
    )
    fun observeSessionCounters(sessionId: String): Flow<SessionCounterRow?>

    /**
     * One page of observations for export, keyed on the last row seen rather than on an offset.
     *
     * `LIMIT/OFFSET` would re-scan and re-sort the prefix on every page — quadratic over 500k rows —
     * and a concurrent insert would shift the offsets and silently skip or repeat a row. Keyset
     * paging on the same `(timestamp, id)` ordering the export uses is stable under concurrent
     * writes and costs one index seek per page.
     */
    @Query(
        """
        SELECT * FROM raw_observation
        WHERE timestamp_epoch_ms >= :fromEpochMs
          AND timestamp_epoch_ms <= :toEpochMs
          AND (
                timestamp_utc > :afterTimestampUtc
                OR (timestamp_utc = :afterTimestampUtc AND observation_id > :afterObservationId)
              )
        ORDER BY timestamp_utc ASC, observation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun pageForExport(
        fromEpochMs: Long,
        toEpochMs: Long,
        afterTimestampUtc: String,
        afterObservationId: String,
        limit: Int,
    ): List<RawObservationEntity>

    @Query(
        """
        SELECT * FROM raw_observation
        WHERE session_id = :sessionId
          AND (
                timestamp_utc > :afterTimestampUtc
                OR (timestamp_utc = :afterTimestampUtc AND observation_id > :afterObservationId)
              )
        ORDER BY timestamp_utc ASC, observation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun pageForSessionExport(
        sessionId: String,
        afterTimestampUtc: String,
        afterObservationId: String,
        limit: Int,
    ): List<RawObservationEntity>

    @Query(
        """
        SELECT COUNT(*) AS observationCount,
               MIN(timestamp_utc) AS firstObservationUtc,
               MAX(timestamp_utc) AS lastObservationUtc,
               SUM(CASE WHEN sample_kind = 'GROUND_TRUTH' THEN 1 ELSE 0 END) AS groundTruthCount
        FROM raw_observation
        WHERE timestamp_epoch_ms >= :fromEpochMs AND timestamp_epoch_ms <= :toEpochMs
        """,
    )
    suspend fun summariseRange(fromEpochMs: Long, toEpochMs: Long): ExportRangeSummary

    @Query(
        """
        SELECT COUNT(*) AS observationCount,
               MIN(timestamp_utc) AS firstObservationUtc,
               MAX(timestamp_utc) AS lastObservationUtc,
               SUM(CASE WHEN sample_kind = 'GROUND_TRUTH' THEN 1 ELSE 0 END) AS groundTruthCount
        FROM raw_observation
        WHERE session_id = :sessionId
        """,
    )
    suspend fun summariseSession(sessionId: String): ExportRangeSummary

    @Query("SELECT DISTINCT session_id FROM raw_observation WHERE session_id IS NOT NULL ORDER BY session_id")
    suspend fun allSessionIds(): List<String>

    @Query(
        """
        SELECT DISTINCT session_id FROM raw_observation
        WHERE session_id IS NOT NULL
          AND timestamp_epoch_ms >= :fromEpochMs AND timestamp_epoch_ms <= :toEpochMs
        ORDER BY session_id
        """,
    )
    suspend fun sessionIdsInRange(fromEpochMs: Long, toEpochMs: Long): List<String>

    /**
     * Which of [ids] the database already holds. Chunked by the caller: SQLite's default limit is
     * 999 bound parameters, and an import package routinely carries tens of thousands of ids.
     */
    @Query("SELECT observation_id FROM raw_observation WHERE observation_id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query(
        """
        SELECT * FROM raw_observation
        ORDER BY timestamp_epoch_ms DESC, observation_id DESC
        """,
    )
    fun pageAllNewestFirst(): PagingSource<Int, RawObservationEntity>

    @Query(
        """
        SELECT * FROM raw_observation
        WHERE (:observerId IS NULL OR observer_id = :observerId)
          AND (:sensorType IS NULL OR sensor_type = :sensorType)
          AND (:identifier IS NULL OR radio_identifier = :identifier)
          AND (:buildingId IS NULL OR building_id = :buildingId)
          AND timestamp_epoch_ms >= :fromEpochMs
          AND timestamp_epoch_ms <= :toEpochMs
        ORDER BY timestamp_epoch_ms DESC, observation_id DESC
        """,
    )
    fun pageFiltered(
        observerId: String?,
        sensorType: String?,
        identifier: String?,
        buildingId: String?,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): PagingSource<Int, RawObservationEntity>

    @Query(
        """
        SELECT radio_identifier AS radioIdentifier,
               identifier_type  AS identifierType,
               COUNT(*)         AS sampleCount,
               AVG(rssi)        AS meanRssi,
               MAX(rssi)        AS strongestRssi,
               MAX(timestamp_utc) AS lastSeenUtc
        FROM raw_observation
        WHERE timestamp_epoch_ms >= :fromEpochMs AND timestamp_epoch_ms <= :toEpochMs
        GROUP BY radio_identifier, identifier_type
        ORDER BY sampleCount DESC
        LIMIT :limit
        """,
    )
    suspend fun topIdentifiers(fromEpochMs: Long, toEpochMs: Long, limit: Int): List<IdentifierSummary>

    /** Observations captured during one survey session, for fingerprint construction. */
    @Query(
        """
        SELECT * FROM raw_observation
        WHERE sample_kind = 'GROUND_TRUTH' AND survey_point_id = :surveyPointId
        ORDER BY timestamp_epoch_ms ASC, observation_id ASC
        """,
    )
    suspend fun groundTruthForPoint(surveyPointId: String): List<RawObservationEntity>

    @Query("SELECT MIN(timestamp_epoch_ms) FROM raw_observation")
    suspend fun earliestEpochMs(): Long?

    @Query("SELECT MAX(timestamp_epoch_ms) FROM raw_observation")
    suspend fun latestEpochMs(): Long?
}

data class SensorTypeCount(val sensorType: String, val count: Long)

data class SessionCounterRow(
    val total: Long,
    val wifi: Long,
    val ble: Long,
    val rtt: Long,
    val gps: Long,
    val groundTruth: Long,
    val distinctIdentifiers: Long,
    val lastObservationEpochMs: Long?,
)

data class ExportRangeSummary(
    val observationCount: Long,
    val firstObservationUtc: String?,
    val lastObservationUtc: String?,
    val groundTruthCount: Long,
)

data class IdentifierSummary(
    val radioIdentifier: String,
    val identifierType: String,
    val sampleCount: Long,
    val meanRssi: Double?,
    val strongestRssi: Int?,
    val lastSeenUtc: String?,
)
