package com.rfmapper.core.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Throttling is the single biggest determinant of the Wi-Fi sample rate, so it is tested against a
 * fake clock rather than discovered in the field two minutes at a time.
 */
class WifiScanSchedulerTest {

    private val clock = TestClock()

    @Test
    fun `the platform quota is never exceeded`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.FOREGROUND)

        repeat(4) { assertTrue(scheduler.tryAcquire(), "request ${it + 1} should be allowed") }
        assertFalse(scheduler.tryAcquire(), "a fifth request inside the window must be refused")
    }

    @Test
    fun `a token becomes available once the window has moved past the oldest request`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.FOREGROUND)
        repeat(4) { scheduler.tryAcquire() }

        clock.advance(119_000)
        assertFalse(scheduler.tryAcquire(), "still inside the two-minute window")

        clock.advance(2_000)
        assertTrue(scheduler.tryAcquire(), "the oldest request has aged out")
    }

    @Test
    fun `the profile's cadence is honoured even when tokens remain`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.FOREGROUND)
        val cadence = ScanProfile.ENDURANCE.wifiScanIntervalMillis

        assertTrue(scheduler.tryAcquire(cadence))
        assertFalse(scheduler.tryAcquire(cadence), "ENDURANCE must not spend its quota immediately")

        clock.advance(cadence)
        assertTrue(scheduler.tryAcquire(cadence))
    }

    @Test
    fun `the wait time tells the caller when to come back`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.FOREGROUND)
        repeat(4) { scheduler.tryAcquire() }

        assertEquals(120_000, scheduler.millisUntilNextRequest())
        clock.advance(30_000)
        assertEquals(90_000, scheduler.millisUntilNextRequest())
    }

    @Test
    fun `the background quota is one request per half hour`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.BACKGROUND)

        assertTrue(scheduler.tryAcquire())
        assertFalse(scheduler.tryAcquire())

        clock.advance(1_800_001)
        assertTrue(scheduler.tryAcquire())
    }

    @Test
    fun `a refused startScan is counted rather than swallowed`() {
        val scheduler = WifiScanScheduler(clock, WifiScanScheduler.Quota.FOREGROUND)
        scheduler.tryAcquire()
        scheduler.recordRejection()

        val state = scheduler.state()
        assertEquals(1, state.rejectedRequests.toInt())
        assertTrue(state.isThrottled, "a rejection is a data-quality fact worth reporting")
    }

    @Test
    fun `a result older than the freshness window is classified as stale`() {
        val scheduler = WifiScanScheduler(clock)
        val now = clock.monotonicElapsedMillis()

        val fresh = scheduler.classify(now - 1_000, now)
        assertTrue(fresh.isFresh)
        assertEquals(1_000, fresh.ageMillis)

        // Android 29+ can serve the same cached scan for half an hour. Sixty deliveries of one
        // scan are not sixty samples, and this is where that becomes visible.
        val cached = scheduler.classify(now - 1_500_000, now)
        assertFalse(cached.isFresh)
        assertEquals(1_500_000, cached.ageMillis)
    }

    @Test
    fun `a result timestamp from the future is clamped rather than negative`() {
        val scheduler = WifiScanScheduler(clock)
        val now = clock.monotonicElapsedMillis()
        val skewed = scheduler.classify(now + 5_000, now)

        assertEquals(0, skewed.ageMillis)
        assertTrue(skewed.isFresh)
    }
}
