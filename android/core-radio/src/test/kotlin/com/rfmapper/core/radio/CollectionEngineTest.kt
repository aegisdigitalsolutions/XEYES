package com.rfmapper.core.radio

import com.rfmapper.core.model.Observation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records what it is given, and can be told to fail, so the retry path is exercisable. */
private class RecordingWriter(var failuresRemaining: Int = 0) : ObservationWriter {
    val batches = mutableListOf<List<Observation>>()
    val written: List<Observation> get() = batches.flatten()

    override suspend fun write(batch: List<Observation>) {
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("disk full")
        }
        batches += batch
    }
}

class CollectionEngineTest {

    private val clock = TestClock()
    private val observer = TestRadio.observer()
    private val factory = ObservationFactory(observer, clock, SequentialIds())

    private fun engine(
        writer: ObservationWriter,
        batchSize: Int = 5,
        bufferCapacity: Int = 100,
    ) = CollectionEngine(
        factory = factory,
        writer = writer,
        clock = clock,
        batchSize = batchSize,
        batchTimeoutMillis = 5_000,
        bufferCapacity = bufferCapacity,
    )

    @Test
    fun `samples are written in batches rather than one transaction each`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 5)
        engine.start(TestRadio.session())

        repeat(12) { engine.submit(TestRadio.wifiSample(rssi = -60 - it)) }

        assertEquals(2, writer.batches.size, "12 samples at a batch size of 5 is two full batches")
        assertEquals(10, writer.written.size)

        engine.stop()
        assertEquals(12, writer.written.size, "stopping flushes the remainder")
    }

    @Test
    fun `a quiet environment still persists on the flush deadline`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 200)
        engine.start(TestRadio.session())

        engine.submit(TestRadio.bleSample())
        assertEquals(0, writer.written.size, "one sample is far short of the batch size")

        clock.advance(6_000)
        engine.submit(TestRadio.bleSample())

        assertEquals(2, writer.written.size, "the deadline flushed what was buffered")
    }

    @Test
    fun `nothing is recorded before the session starts`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer)

        assertFalse(engine.submit(TestRadio.wifiSample()))
        assertEquals(0, writer.written.size)
    }

    @Test
    fun `back-pressure drops samples and says how many`() = runTest {
        val writer = RecordingWriter()
        // A buffer of 3 with a batch size it never reaches: the buffer fills and stays full.
        val engine = engine(writer, batchSize = 1_000, bufferCapacity = 3)
        engine.start(TestRadio.session())

        repeat(10) { engine.submit(TestRadio.bleSample(rssi = -70 - it)) }

        val summary = engine.stop()
        assertEquals(7, summary.droppedSamples, "seven samples had nowhere to go")
        assertEquals(3, summary.observationCount)
        assertTrue(
            summary.degradations.any { it.startsWith("dropped_samples") },
            "a coverage gap must be explainable: ${summary.degradations}",
        )
    }

    @Test
    fun `a transient write failure costs a retry rather than the data`() = runTest {
        val writer = RecordingWriter(failuresRemaining = 1)
        val engine = engine(writer, batchSize = 3)
        engine.start(TestRadio.session())

        engine.submit(TestRadio.wifiSample())
        engine.submit(TestRadio.wifiSample())
        val failure = runCatching { engine.submit(TestRadio.wifiSample()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "the failure is surfaced, not hidden: got $failure")
        assertEquals(0, writer.written.size)

        engine.flush()
        assertEquals(3, writer.written.size, "the batch was retried, not discarded")
    }

    @Test
    fun `the summary counts each sensor separately`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 100)
        engine.start(TestRadio.session())

        repeat(7) { engine.submit(TestRadio.wifiSample(rssi = -60 - it)) }
        repeat(4) { engine.submit(TestRadio.bleSample(rssi = -80 - it)) }

        val summary = engine.stop()
        assertEquals(11, summary.observationCount)
        assertEquals(7, summary.wifiCount)
        assertEquals(4, summary.bleCount)
        assertEquals(0, summary.rttCount)
    }

    @Test
    fun `survey mode produces exactly the samples captured at the point`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 100)
        engine.start(TestRadio.session())

        engine.submit(TestRadio.wifiSample())

        engine.beginSurvey(
            SurveyContext(
                surveySessionId = "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
                surveyPointId = "B7_CENTER",
                place = ObserverPlace(buildingId = "B7", zoneId = "B7-CENTER"),
            ),
        )
        repeat(6) { engine.submit(TestRadio.wifiSample(rssi = -62 - it)) }
        engine.endSurvey()

        engine.submit(TestRadio.wifiSample())
        engine.stop()

        val groundTruth = writer.written.filter { it.sampleKind == com.rfmapper.core.model.SampleKind.GROUND_TRUTH }
        assertEquals(6, groundTruth.size)
        assertTrue(groundTruth.all { it.metadata["survey_point_id"] == "B7_CENTER" })
        assertEquals(8, writer.written.size, "collection continues around the survey")
    }

    @Test
    fun `the status stream drives the dashboard`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 100)

        assertFalse(engine.status.value.isRunning)

        engine.start(TestRadio.session())
        engine.submit(TestRadio.wifiSample())
        engine.submit(TestRadio.bleSample())

        val status = engine.status.value
        assertTrue(status.isRunning)
        assertEquals(2, status.counts.total)
        assertEquals(1, status.counts.wifi)
        assertEquals(1, status.counts.ble)
        assertEquals(2, status.counts.distinctIdentifiers)
        assertEquals(2, status.pendingWrites)
        assertFalse(status.suspectedGap)
    }

    @Test
    fun `a long silence is flagged as a suspected service kill`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 1)
        engine.start(TestRadio.session())
        engine.submit(TestRadio.wifiSample())

        assertFalse(engine.status.value.suspectedGap)

        // An OEM battery manager freezing the service looks exactly like this.
        clock.advance(11 * 60 * 1000)
        engine.setDegradation(DegradationContext(radiosOff = setOf("WIFI")))

        assertTrue(engine.status.value.suspectedGap)
        val summary = engine.stop()
        assertTrue(summary.suspectedServiceKill, "an unexplained hole is worse than a labelled one")
    }

    @Test
    fun `throttled scan requests reach the session summary`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 100)
        engine.start(TestRadio.session())
        engine.submit(TestRadio.wifiSample())

        engine.setThrottleState(
            ThrottleState(
                requestsInWindow = 4,
                maxRequestsInWindow = 4,
                rejectedRequests = 3,
                millisUntilNextRequest = 45_000,
            ),
        )

        val summary = engine.stop()
        assertEquals(3, summary.throttledScanRequests)
        assertTrue(summary.degradations.contains("wifi_scan_throttled"))
    }

    @Test
    fun `an unusable sample is counted, not crashed on and not invented`() = runTest {
        val writer = RecordingWriter()
        val engine = engine(writer, batchSize = 100)
        engine.start(TestRadio.session())

        assertFalse(engine.submit(TestRadio.wifiSample(bssid = "garbage")))
        engine.submit(TestRadio.wifiSample())

        val summary = engine.stop()
        assertEquals(1, summary.observationCount)
        assertTrue(
            summary.degradations.any { it.startsWith("rejected_sample") },
            "got ${summary.degradations}",
        )
    }
}
