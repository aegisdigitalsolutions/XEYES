package com.rfmapper.data.room

import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MovementEstimate
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.core.model.QualityFlag
import com.rfmapper.core.model.ZoneTransition
import com.rfmapper.data.room.derived.DerivedDao
import com.rfmapper.data.room.derived.DerivedGenerationEntity
import com.rfmapper.data.room.derived.ZoneOccupancyRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The Master's read path over the DERIVED layer.
 *
 * Every query is scoped by `algorithmVersion`. Generations coexist deliberately — reprocessing
 * never overwrites — so "which version am I looking at" is not a filter the caller may forget but
 * a parameter it cannot omit.
 */
class DerivedRepository(private val derived: DerivedDao) {

    fun observeGenerations(): Flow<List<DerivedGenerationEntity>> = derived.observeGenerations()

    suspend fun activeGeneration(): DerivedGenerationEntity? =
        withContext(Dispatchers.IO) { derived.activeGeneration() ?: derived.generations().firstOrNull() }

    suspend fun setActive(algorithmVersion: String) =
        withContext(Dispatchers.IO) { derived.setActiveGeneration(algorithmVersion) }

    /** Discards a superseded generation. Raw evidence is untouched; the estimates are regenerable. */
    suspend fun discard(algorithmVersion: String): Int =
        withContext(Dispatchers.IO) { derived.deleteByAlgorithmVersion(algorithmVersion) }

    fun observeLatestPerDevice(algorithmVersion: String): Flow<List<PositionEstimate>> =
        derived.observeLatestPerDevice(algorithmVersion).map { rows -> rows.map { it.toModel() } }

    fun observeZoneOccupancy(algorithmVersion: String): Flow<List<ZoneOccupancyRow>> =
        derived.observeZoneOccupancy(algorithmVersion)

    fun observeOpenFlags(): Flow<List<QualityFlag>> =
        derived.observeOpenFlags().map { rows -> rows.map { it.toModel() } }

    suspend fun acknowledgeFlag(flagId: String, by: String, nowMillis: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { derived.acknowledgeFlag(flagId, by, Iso8601.format(nowMillis)) }

    suspend fun track(
        deviceId: String,
        algorithmVersion: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<PositionEstimate> = withContext(Dispatchers.IO) {
        derived.estimatesForDevice(deviceId, algorithmVersion, fromEpochMs, toEpochMs).map { it.toModel() }
    }

    suspend fun transitions(deviceId: String, algorithmVersion: String, limit: Int = 50): List<ZoneTransition> =
        withContext(Dispatchers.IO) {
            derived.transitionsForDevice(deviceId, algorithmVersion, limit).map { it.toModel() }
        }

    suspend fun movements(deviceId: String, algorithmVersion: String, limit: Int = 50): List<MovementEstimate> =
        withContext(Dispatchers.IO) {
            derived.movementsForDevice(deviceId, algorithmVersion, limit).map { it.toModel() }
        }

    data class GenerationSummary(
        val generation: DerivedGenerationEntity,
        val devices: Int,
        val byTier: Map<PrecisionTier, Int>,
    ) {
        /**
         * The share of estimates that assert a coordinate.
         *
         * Worth showing next to any map: a generation where most results are coarse zone
         * statements is the honest outcome of sparse evidence, and a sudden jump toward
         * coordinates is a signal to go and check what changed in the pipeline rather than to
         * celebrate.
         */
        val coordinateShare: Double
            get() {
                val total = byTier.values.sum()
                if (total == 0) return 0.0
                return byTier.filterKeys { it.hasCoordinates }.values.sum().toDouble() / total
            }
    }

    suspend fun summarise(algorithmVersion: String): GenerationSummary? = withContext(Dispatchers.IO) {
        val generation = derived.generation(algorithmVersion) ?: return@withContext null
        GenerationSummary(
            generation = generation,
            devices = derived.deviceCount(algorithmVersion),
            byTier = derived.tierBreakdown(algorithmVersion).mapNotNull { row ->
                PrecisionTier.entries.firstOrNull { it.name == row.precisionTier }?.let { it to row.count }
            }.toMap(),
        )
    }
}
