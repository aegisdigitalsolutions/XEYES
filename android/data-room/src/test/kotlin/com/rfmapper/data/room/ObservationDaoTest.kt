package com.rfmapper.data.room

import androidx.test.core.app.ApplicationProvider
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType
import com.rfmapper.data.room.raw.RawObservationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage-level behaviour that the rest of the system takes for granted: that an observation
 * survives a round trip through SQLite unchanged, and that inserting it twice cannot duplicate it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservationDaoTest {

    private lateinit var db: RfMapperDatabase
    private lateinit var repository: ObservationRepository

    @Before
    fun open() {
        db = RfMapperDatabase.openInMemory(ApplicationProvider.getApplicationContext())
        repository = ObservationRepository(db.observationDao(), db.sessionDao())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `an observation survives the round trip through storage unchanged`() = runTest {
        val original = RoomFixtures.observation(index = 1)
        db.observationDao().insertAll(listOf(RawObservationEntity.from(original)))

        val page = db.observationDao().pageForExport(0, Long.MAX_VALUE, "", "", 10)
        assertEquals(1, page.size)
        // Every field, not a spot check: the export's byte-for-byte reproducibility depends on it.
        assertEquals(original, page.single().toObservation())
    }

    @Test
    fun `metadata round trips including keys the app does not know`() = runTest {
        val original = RoomFixtures.observation(index = 1).copy(
            metadata = mapOf(
                "session_id" to "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40",
                "some_future_key" to "value from a newer schema minor",
                "quoted" to "a value with, a comma and \"quotes\"",
            ),
        )
        db.observationDao().insertAll(listOf(RawObservationEntity.from(original)))

        val stored = db.observationDao().pageForExport(0, Long.MAX_VALUE, "", "", 10).single()
        assertEquals(original.metadata, stored.toObservation().metadata)
    }

    @Test
    fun `inserting the same observation twice stores it once`() = runTest {
        val row = RawObservationEntity.from(RoomFixtures.observation(index = 1))

        val first = db.observationDao().insertAll(listOf(row))
        val second = db.observationDao().insertAll(listOf(row))

        assertEquals(1, db.observationDao().countAll())
        assertTrue(first.single() != -1L, "the first insert takes effect")
        assertEquals(-1L, second.single(), "the second is ignored, which is the dedup mechanism")
    }

    @Test
    fun `the duplicate count comes from the insert itself`() = runTest {
        val existing = (1..5).map { RoomFixtures.observation(it) }
        repository.insertImported(existing, importBatchId = "batch-1")

        val overlapping = (1..12).map { RoomFixtures.observation(it) }
        val outcome = repository.insertImported(overlapping, importBatchId = "batch-2")

        assertEquals(7, outcome.inserted)
        assertEquals(5, outcome.duplicates)
        assertEquals(12, db.observationDao().countAll())
    }

    @Test
    fun `cursor paging walks every row exactly once in export order`() = runTest {
        val rows = (1..2_500).map { RoomFixtures.observation(it) }
        db.observationDao().insertAll(rows.map { RawObservationEntity.from(it) })

        val streamed = repository.rangeExportSource(0, Long.MAX_VALUE).open().asSequence().toList()

        assertEquals(2_500, streamed.size)
        assertEquals(rows.map { it.observationId }.sorted(), streamed.map { it.observationId }.sorted())
        // The export contract is (timestamp_utc, observation_id) ascending.
        val ordered = streamed.sortedWith(compareBy({ it.timestampUtc }, { it.observationId }))
        assertEquals(ordered.map { it.observationId }, streamed.map { it.observationId })
    }

    @Test
    fun `paging is not confused by rows sharing a timestamp`() = runTest {
        // A Wi-Fi scan delivers every access point with the same timestamp, so equal keys are the
        // normal case rather than an edge case. A cursor on timestamp alone would skip or repeat.
        val sameInstant = (1..40).map { RoomFixtures.observation(it, atMillis = RoomFixtures.DAY_START) }
        db.observationDao().insertAll(sameInstant.map { RawObservationEntity.from(it) })

        val streamed = repository.rangeExportSource(0, Long.MAX_VALUE).open().asSequence().toList()

        assertEquals(40, streamed.size)
        assertEquals(40, streamed.map { it.observationId }.distinct().size)
    }

    @Test
    fun `the source yields the same sequence on every pass`() = runTest {
        // The export writer streams the CSV and the JSON in separate passes; if the two disagreed
        // the package would be internally inconsistent and the importer would reject it.
        val rows = (1..300).map { RoomFixtures.observation(it) }
        db.observationDao().insertAll(rows.map { RawObservationEntity.from(it) })
        val source = repository.rangeExportSource(0, Long.MAX_VALUE)

        val first = source.open().asSequence().map { it.observationId }.toList()
        val second = source.open().asSequence().map { it.observationId }.toList()

        assertEquals(first, second)
    }

    @Test
    fun `a range query excludes rows outside it`() = runTest {
        val inRange = (1..10).map { RoomFixtures.observation(it) }
        val nextDay = RoomFixtures.observation(99, atMillis = RoomFixtures.DAY_START + 86_400_000L)
        db.observationDao().insertAll((inRange + nextDay).map { RawObservationEntity.from(it) })

        val (from, to) = ObservationRepository.utcDayBounds("2026-09-14")
        val summary = repository.summariseRange(from, to)

        assertEquals(10, summary.observationCount)
        assertEquals(
            10,
            repository.rangeExportSource(from, to).open().asSequence().count(),
        )
    }

    @Test
    fun `the range summary agrees with the rows it describes`() = runTest {
        val rows = (1..30).map { RoomFixtures.observation(it) }
        db.observationDao().insertAll(rows.map { RawObservationEntity.from(it) })

        val summary = repository.summariseRange(0, Long.MAX_VALUE)

        // The manifest is written from this summary before a single row is streamed, so a
        // disagreement here would become a package that lies about its own contents.
        assertEquals(30, summary.observationCount)
        assertEquals(rows.first().timestampUtc, summary.firstObservationUtc)
        assertEquals(rows.last().timestampUtc, summary.lastObservationUtc)
        assertEquals(30, summary.countsBySensorType.values.sum())
        assertEquals(
            rows.groupingBy { it.sensorType.name }.eachCount().mapValues { it.value.toLong() },
            summary.countsBySensorType,
        )
    }

    @Test
    fun `session counters aggregate what the dashboard shows`() = runTest {
        val sessionId = "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40"
        val rows = (1..24).map { RoomFixtures.observation(it) }
        db.observationDao().insertAll(rows.map { RawObservationEntity.from(it) })

        val counters = db.observationDao().observeSessionCounters(sessionId).first()

        assertEquals(24, counters?.total)
        assertEquals(rows.count { it.sensorType == SensorType.WIFI_SCAN }.toLong(), counters?.wifi)
        assertEquals(rows.count { it.sensorType == SensorType.BLE }.toLong(), counters?.ble)
    }

    @Test
    fun `ground truth is queryable by survey point without scanning metadata json`() = runTest {
        val ordinary = (1..5).map { RoomFixtures.observation(it) }
        val survey = (10..15).map { RoomFixtures.surveySample(it, surveyPointId = "B7_CENTER") }
        val elsewhere = listOf(RoomFixtures.surveySample(20, surveyPointId = "B9_CENTER"))
        db.observationDao().insertAll((ordinary + survey + elsewhere).map { RawObservationEntity.from(it) })

        val atCentre = db.observationDao().groundTruthForPoint("B7_CENTER")

        assertEquals(6, atCentre.size)
        assertTrue(atCentre.all { it.sampleKind == SampleKind.GROUND_TRUTH.name })
    }

    @Test
    fun `existing id lookup handles more ids than sqlite can bind at once`() = runTest {
        val rows = (1..2_000).map { RoomFixtures.observation(it) }
        db.observationDao().insertAll(rows.map { RawObservationEntity.from(it) })

        val candidates = (1..3_000).map { RoomFixtures.observationId(it) }.toSet()
        val existing = repository.existingIds(candidates)

        assertEquals(2_000, existing.size)
    }

    @Test
    fun `retention is the only path that removes raw rows`() = runTest {
        val old = (1..5).map { RoomFixtures.observation(it, atMillis = RoomFixtures.DAY_START) }
        val recent = (10..14).map {
            RoomFixtures.observation(it, atMillis = RoomFixtures.DAY_START + 40L * 86_400_000L)
        }
        db.observationDao().insertAll((old + recent).map { RawObservationEntity.from(it) })

        val cutoff = RoomFixtures.DAY_START + 86_400_000L
        assertEquals(5, db.retentionDao().countRawBefore(cutoff))
        assertEquals(5, db.retentionDao().deleteRawBefore(cutoff))
        assertEquals(5, db.observationDao().countAll())
    }

    @Test
    fun `an empty database summarises honestly rather than guessing`() = runTest {
        val summary = repository.summariseRange(0, Long.MAX_VALUE)

        assertEquals(0, summary.observationCount)
        assertNull(summary.firstObservationUtc)
        assertNull(summary.lastObservationUtc)
        assertEquals(0, repository.rangeExportSource(0, Long.MAX_VALUE).open().asSequence().count())
    }

    @Test
    fun `the day bounds cover exactly one utc day`() {
        val (from, to) = ObservationRepository.utcDayBounds("2026-09-14")

        assertEquals("2026-09-14T00:00:00.000Z", Iso8601.format(from))
        assertEquals("2026-09-14T23:59:59.999Z", Iso8601.format(to))
    }
}
