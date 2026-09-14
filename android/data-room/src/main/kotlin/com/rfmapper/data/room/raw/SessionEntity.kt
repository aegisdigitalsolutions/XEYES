package com.rfmapper.data.room.raw

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.rfmapper.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow

/**
 * A collection session. Updatable, unlike an observation: a session is an open record that gains an
 * end time and final counters when it closes, not a statement about a single instant.
 */
@Entity(
    tableName = "raw_session",
    indices = [Index("started_at_epoch_ms")],
)
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "started_at_utc") val startedAtUtc: String,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_utc") val endedAtUtc: String?,
    @ColumnInfo(name = "observer_id") val observerId: String,
    @ColumnInfo(name = "scan_profile") val scanProfile: String,
    @ColumnInfo(name = "building_id") val buildingId: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String?,
    @ColumnInfo(name = "observation_count") val observationCount: Long,
    @ColumnInfo(name = "wifi_count") val wifiCount: Long,
    @ColumnInfo(name = "ble_count") val bleCount: Long,
    @ColumnInfo(name = "rtt_count") val rttCount: Long,
    @ColumnInfo(name = "gps_count") val gpsCount: Long,
    @ColumnInfo(name = "dropped_samples") val droppedSamples: Long,
    @ColumnInfo(name = "throttled_scan_requests") val throttledScanRequests: Long,
    @ColumnInfo(name = "background_denied") val backgroundDenied: Boolean,
    @ColumnInfo(name = "suspected_service_kill") val suspectedServiceKill: Boolean,
    @ColumnInfo(name = "battery_start_pct") val batteryStartPct: Int?,
    @ColumnInfo(name = "battery_end_pct") val batteryEndPct: Int?,
    @ColumnInfo(name = "degradations") val degradations: List<String>,
) {
    fun toSummary() = SessionSummary(
        sessionId = sessionId,
        startedAt = startedAtUtc,
        endedAt = endedAtUtc,
        scanProfile = scanProfile,
        buildingId = buildingId,
        zoneId = zoneId,
        observationCount = observationCount,
        wifiCount = wifiCount,
        bleCount = bleCount,
        rttCount = rttCount,
        gpsCount = gpsCount,
        droppedSamples = droppedSamples,
        throttledScanRequests = throttledScanRequests,
        backgroundDenied = backgroundDenied,
        suspectedServiceKill = suspectedServiceKill,
        batteryStartPct = batteryStartPct,
        batteryEndPct = batteryEndPct,
        degradations = degradations,
    )

    companion object {
        fun from(
            summary: SessionSummary,
            observerId: String,
            startedAtEpochMs: Long,
        ) = SessionEntity(
            sessionId = summary.sessionId,
            startedAtUtc = summary.startedAt,
            startedAtEpochMs = startedAtEpochMs,
            endedAtUtc = summary.endedAt,
            observerId = observerId,
            scanProfile = summary.scanProfile,
            buildingId = summary.buildingId,
            zoneId = summary.zoneId,
            observationCount = summary.observationCount,
            wifiCount = summary.wifiCount,
            bleCount = summary.bleCount,
            rttCount = summary.rttCount,
            gpsCount = summary.gpsCount,
            droppedSamples = summary.droppedSamples,
            throttledScanRequests = summary.throttledScanRequests,
            backgroundDenied = summary.backgroundDenied,
            suspectedServiceKill = summary.suspectedServiceKill,
            batteryStartPct = summary.batteryStartPct,
            batteryEndPct = summary.batteryEndPct,
            degradations = summary.degradations,
        )
    }
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM raw_session WHERE session_id = :sessionId")
    suspend fun byId(sessionId: String): SessionEntity?

    @Query("SELECT * FROM raw_session ORDER BY started_at_epoch_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM raw_session WHERE ended_at_utc IS NULL ORDER BY started_at_epoch_ms DESC")
    suspend fun unfinished(): List<SessionEntity>

    @Query(
        """
        SELECT * FROM raw_session
        WHERE started_at_epoch_ms >= :fromEpochMs AND started_at_epoch_ms <= :toEpochMs
        ORDER BY started_at_epoch_ms ASC
        """,
    )
    suspend fun inRange(fromEpochMs: Long, toEpochMs: Long): List<SessionEntity>

    @Query("SELECT * FROM raw_session WHERE session_id IN (:sessionIds) ORDER BY started_at_epoch_ms ASC")
    suspend fun byIds(sessionIds: List<String>): List<SessionEntity>
}

/**
 * The only path that removes raw observations, separated so that deletion is never reachable from
 * ordinary collection or import code. Retention is an administrative act on data the administrator
 * owns, and the UI gates it behind an explicit confirmation that states the row count.
 */
@Dao
interface RetentionDao {

    @Query("SELECT COUNT(*) FROM raw_observation WHERE timestamp_epoch_ms < :beforeEpochMs")
    suspend fun countRawBefore(beforeEpochMs: Long): Long

    @Query("DELETE FROM raw_observation WHERE timestamp_epoch_ms < :beforeEpochMs")
    suspend fun deleteRawBefore(beforeEpochMs: Long): Int

    @Query("DELETE FROM raw_session WHERE ended_at_utc IS NOT NULL AND started_at_epoch_ms < :beforeEpochMs")
    suspend fun deleteSessionsBefore(beforeEpochMs: Long): Int
}
