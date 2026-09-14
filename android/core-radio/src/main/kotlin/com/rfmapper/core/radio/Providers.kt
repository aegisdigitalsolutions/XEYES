package com.rfmapper.core.radio

import kotlinx.coroutines.flow.Flow

/**
 * The provider contracts. Both the Android implementations and the fakes used by tests implement
 * these, so the collection pipeline can be exercised end to end without a radio.
 *
 * Every provider reports its [capability] rather than throwing. Missing hardware is a normal
 * condition on a heterogeneous fleet: a phone without Wi-Fi RTT must collect everything else
 * normally and declare RTT unsupported, and that declaration then travels in `observer.json` so the
 * Lab can tell "saw nothing" from "cannot see".
 */
interface RadioProvider {
    suspend fun capability(): Capability
}

interface WifiObservationProvider : RadioProvider {
    /** Hot stream of scan results. Collection starts the scan loop; cancellation stops it. */
    fun samples(): Flow<RadioSample>

    /** Wi-Fi association details for the network this observer is joined to, if any. */
    suspend fun associationSample(): RadioSample?
}

interface BleObservationProvider : RadioProvider {
    fun samples(): Flow<RadioSample>
}

/**
 * Wi-Fi RTT ranging. Request/response rather than a stream: ranging is expensive, rate-limited by
 * the platform, and only worth attempting against known responder infrastructure.
 */
interface RttObservationProvider : RadioProvider {
    suspend fun range(targets: List<RttTarget>): List<RadioSample>

    /** Responders the platform saw in the last scan, so the caller can intersect with its registry. */
    suspend fun availableResponders(): List<RttTarget>
}

data class RttTarget(
    val bssid: String,
    val frequencyMhz: Int? = null,
    val supports80211mc: Boolean = true,
)

interface LocationProvider : RadioProvider {
    fun samples(): Flow<RadioSample>
}

/**
 * Non-radio context: battery level, screen state, motion. Recorded as metadata because it explains
 * sample-rate changes that would otherwise look like data loss.
 */
interface DeviceContextProvider {
    suspend fun snapshot(): Map<String, String>
}

/** The clock, injected so that tests are not at the mercy of wall time. */
interface Clock {
    fun wallClockMillis(): Long
    fun monotonicElapsedMillis(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun wallClockMillis(): Long = System.currentTimeMillis()
            override fun monotonicElapsedMillis(): Long = System.nanoTime() / 1_000_000
        }
    }
}

/** Identifier source for observation ids, injected so exports can be made reproducible in tests. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val RANDOM = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}
