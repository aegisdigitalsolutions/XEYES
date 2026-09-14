package com.rfmapper.data.room

import androidx.paging.PagingSource
import com.rfmapper.core.export.ObservationSource
import com.rfmapper.core.export.ObservationSummary
import com.rfmapper.core.importing.ExistingIdLookup
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.Observation
import com.rfmapper.core.radio.ObservationWriter
import com.rfmapper.data.room.raw.ObservationDao
import com.rfmapper.data.room.raw.RawObservationEntity
import com.rfmapper.data.room.raw.SessionDao
import com.rfmapper.data.room.raw.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The RAW layer as the rest of the app sees it: an [ObservationWriter] for collection, an
 * [ObservationSource] for export, and an [ExistingIdLookup] for import. Each is the narrow
 * interface a `core-*` module declared, so nothing outside this module depends on Room.
 */
class ObservationRepository(
    private val observations: ObservationDao,
    private val sessions: SessionDao,
) : ObservationWriter {

    override suspend fun write(batch: List<Observation>) {
        if (batch.isEmpty()) return
        withContext(Dispatchers.IO) {
            observations.insertAll(batch.map { RawObservationEntity.from(it) })
        }
    }

    /**
     * Inserts imported rows and reports how many were genuinely new.
     *
     * The count comes from the insert's own return values rather than from a preceding existence
     * check: `IGNORE` against the primary key returns `-1` for a row that was already there, so the
     * duplicate count is a byproduct of the atomic write instead of the result of a query that
     * another writer could invalidate in between.
     */
    suspend fun insertImported(
        batch: List<Observation>,
        importBatchId: String,
    ): InsertOutcome = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext InsertOutcome(0, 0)
        val rowIds = observations.insertAll(batch.map { RawObservationEntity.from(it, importBatchId) })
        val inserted = rowIds.count { it != -1L }
        InsertOutcome(inserted = inserted, duplicates = rowIds.size - inserted)
    }

    data class InsertOutcome(val inserted: Int, val duplicates: Int)

    suspend fun count(): Long = withContext(Dispatchers.IO) { observations.countAll() }

    fun observeCount(): Flow<Long> = observations.observeCount()

    fun observeSessionCounters(sessionId: String) = observations.observeSessionCounters(sessionId)

    suspend fun upsertSession(session: SessionEntity) = withContext(Dispatchers.IO) {
        sessions.upsert(session)
    }

    suspend fun session(sessionId: String): SessionEntity? =
        withContext(Dispatchers.IO) { sessions.byId(sessionId) }

    fun observeRecentSessions(limit: Int = 20) = sessions.observeRecent(limit)

    /**
     * Which of [candidateIds] the raw layer already holds, chunked to stay inside SQLite's limit of
     * 999 bound parameters. A day's package routinely carries tens of thousands of ids.
     */
    suspend fun existingIds(candidateIds: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        candidateIds
            .chunked(SQLITE_PARAMETER_LIMIT)
            .flatMap { observations.existingIds(it) }
            .toSet()
    }

    /** Adapter for the import engine, which has no coroutine context of its own. */
    fun existingIdLookup() = ExistingIdLookup { candidates -> runBlocking { existingIds(candidates) } }

    // -- export ------------------------------------------------------------------------------------

    suspend fun summariseDay(dateStamp: String): ObservationSummary {
        val (from, to) = utcDayBounds(dateStamp)
        return summariseRange(from, to)
    }

    suspend fun summariseRange(fromEpochMs: Long, toEpochMs: Long): ObservationSummary =
        withContext(Dispatchers.IO) {
            val summary = observations.summariseRange(fromEpochMs, toEpochMs)
            ObservationSummary(
                observationCount = summary.observationCount,
                firstObservationUtc = summary.firstObservationUtc,
                lastObservationUtc = summary.lastObservationUtc,
                countsBySensorType = observations.countBySensorTypeInRange(fromEpochMs, toEpochMs)
                    .associate { it.sensorType to it.count },
                groundTruthCount = summary.groundTruthCount,
                sessionIds = observations.sessionIdsInRange(fromEpochMs, toEpochMs),
            )
        }

    suspend fun summariseSession(sessionId: String): ObservationSummary = withContext(Dispatchers.IO) {
        val summary = observations.summariseSession(sessionId)
        ObservationSummary(
            observationCount = summary.observationCount,
            firstObservationUtc = summary.firstObservationUtc,
            lastObservationUtc = summary.lastObservationUtc,
            countsBySensorType = emptyMap(),
            groundTruthCount = summary.groundTruthCount,
            sessionIds = listOf(sessionId),
        )
    }

    /**
     * A cursor-paged source for the export writer.
     *
     * Each page is one indexed seek keyed on the last row of the previous page, so exporting 500k
     * rows costs constant memory and is immune to a concurrent insert shifting offsets underneath
     * it. `LIMIT/OFFSET` would do neither.
     *
     * The blocking calls are deliberate: [ObservationSource] is a synchronous contract because the
     * zip writer it feeds is a synchronous stream, and wrapping each page in a suspension would buy
     * nothing while the writer is blocked on I/O regardless.
     */
    fun dayExportSource(dateStamp: String): ObservationSource {
        val (from, to) = utcDayBounds(dateStamp)
        return rangeExportSource(from, to)
    }

    fun rangeExportSource(fromEpochMs: Long, toEpochMs: Long): ObservationSource = ObservationSource {
        pagingIterator { afterTimestamp, afterId ->
            runBlocking {
                observations.pageForExport(fromEpochMs, toEpochMs, afterTimestamp, afterId, PAGE_SIZE)
            }
        }
    }

    fun sessionExportSource(sessionId: String): ObservationSource = ObservationSource {
        pagingIterator { afterTimestamp, afterId ->
            runBlocking {
                observations.pageForSessionExport(sessionId, afterTimestamp, afterId, PAGE_SIZE)
            }
        }
    }

    private fun pagingIterator(
        nextPage: (afterTimestamp: String, afterId: String) -> List<RawObservationEntity>,
    ): Iterator<Observation> = object : Iterator<Observation> {
        // The empty string sorts below every valid timestamp, so it is the natural starting cursor.
        private var afterTimestamp = ""
        private var afterId = ""
        private var page: List<RawObservationEntity> = emptyList()
        private var index = 0
        private var exhausted = false

        override fun hasNext(): Boolean {
            if (index < page.size) return true
            if (exhausted) return false
            page = nextPage(afterTimestamp, afterId)
            index = 0
            if (page.isEmpty()) {
                exhausted = true
                return false
            }
            if (page.size < PAGE_SIZE) exhausted = true
            page.last().let {
                afterTimestamp = it.timestampUtc
                afterId = it.observationId
            }
            return true
        }

        override fun next(): Observation {
            if (!hasNext()) throw NoSuchElementException()
            return page[index++].toObservation()
        }
    }

    fun observeIdentifierActivity(fromEpochMs: Long, toEpochMs: Long, limit: Int = 50) =
        observeCount().map { observations.topIdentifiers(fromEpochMs, toEpochMs, limit) }

    /**
     * The Master's observation browser.
     *
     * Paged by the framework rather than loaded wholesale: the raw layer is the one table expected
     * to reach hundreds of thousands of rows, and a browser that materialised it would be unusable
     * on exactly the datasets worth browsing.
     */
    fun browse(
        observerId: String? = null,
        sensorType: String? = null,
        identifier: String? = null,
        buildingId: String? = null,
        fromEpochMs: Long = 0L,
        toEpochMs: Long = Long.MAX_VALUE,
    ): PagingSource<Int, RawObservationEntity> = observations.pageFiltered(
        observerId = observerId,
        sensorType = sensorType,
        identifier = identifier,
        buildingId = buildingId,
        fromEpochMs = fromEpochMs,
        toEpochMs = toEpochMs,
    )

    suspend fun topIdentifiers(limit: Int = 50) = withContext(Dispatchers.IO) {
        observations.topIdentifiers(0L, Long.MAX_VALUE, limit)
    }

    suspend fun observedRange(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val earliest = observations.earliestEpochMs() ?: return@withContext null
        earliest to (observations.latestEpochMs() ?: earliest)
    }

    companion object {
        /**
         * Page size for export. Large enough that per-query overhead is negligible, small enough
         * that one page of decoded observations is a few megabytes rather than a few hundred.
         */
        const val PAGE_SIZE = 1_000

        /** SQLite's default `SQLITE_MAX_VARIABLE_NUMBER`, minus room for the query's own bindings. */
        const val SQLITE_PARAMETER_LIMIT = 900

        /** `2026-09-14` to the half-open-in-practice inclusive millisecond bounds of that UTC day. */
        fun utcDayBounds(dateStamp: String): Pair<Long, Long> {
            val start = requireNotNull(Iso8601.parseToEpochMillis("${dateStamp}T00:00:00.000Z")) {
                "'$dateStamp' is not a yyyy-MM-dd date stamp"
            }
            return start to (start + 24 * 60 * 60 * 1000L - 1)
        }
    }
}
