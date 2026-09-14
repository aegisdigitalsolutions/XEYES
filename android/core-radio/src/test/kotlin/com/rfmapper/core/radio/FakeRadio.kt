package com.rfmapper.core.radio

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SensorType

/**
 * A controllable radio. The whole point of `core-radio` being pure JVM is that the collection
 * pipeline can be driven from here instead of from a phone in someone's pocket.
 */
class TestClock(
    private var wall: Long = WALL_START,
    private var monotonic: Long = MONOTONIC_START,
) : Clock {
    override fun wallClockMillis(): Long = wall
    override fun monotonicElapsedMillis(): Long = monotonic

    fun advance(millis: Long) {
        wall += millis
        monotonic += millis
    }

    /** Moves the wall clock only, as an NTP correction or a manual time change would. */
    fun skewWallClock(millis: Long) {
        wall += millis
    }

    companion object {
        const val WALL_START = 1_789_400_000_000L
        const val MONOTONIC_START = 86_400_000L
    }
}

/** Sequential ids, so an expected observation id can be written down in a test. */
class SequentialIds(private val prefix: String = "00000000-0000-4000-8000-") : IdGenerator {
    private var next = 1
    override fun newId(): String = prefix + (next++).toString(16).padStart(12, '0')
}

object TestRadio {

    fun observer(
        observerId: String = "OBS-04",
        buildingId: String? = "B7",
        zoneId: String? = "B7-CENTER",
    ) = ObserverIdentity(
        observerId = observerId,
        friendlyName = "Test Collector",
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        buildingId = buildingId,
        defaultZoneId = zoneId,
        deviceModel = "Pixel 7a",
        manufacturer = "Google",
        platform = Platform.ANDROID,
        osVersion = "34",
        appVersion = "1.0.0",
        installationId = "b1c2d3e4-1111-4222-8333-444455556666",
        capabilities = setOf(ObserverCapability.WIFI_SCAN, ObserverCapability.BLE),
        unsupported = setOf(ObserverCapability.RTT),
    )

    fun session(
        sessionId: String = "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40",
        startedAtMillis: Long = TestClock.WALL_START,
        profile: ScanProfile = ScanProfile.BALANCED,
        place: ObserverPlace = ObserverPlace(buildingId = "B7", zoneId = "B7-CENTER"),
    ) = SessionContext(sessionId, startedAtMillis, profile, place)

    fun wifiSample(
        bssid: String = "AA:BB:CC:11:22:33",
        rssi: Int = -61,
        wallClockMillis: Long = TestClock.WALL_START,
        monotonicMillis: Long = TestClock.MONOTONIC_START,
        freshness: ResultFreshness = ResultFreshness.FRESH,
        ageMillis: Long? = 1_200,
    ) = RadioSample(
        sensorType = SensorType.WIFI_SCAN,
        identifier = bssid,
        identifierType = IdentifierType.WIFI_BSSID,
        wallClockMillis = wallClockMillis,
        monotonicElapsedMillis = monotonicMillis,
        rssi = rssi,
        frequencyMhz = 5180,
        ssid = "SITE-INFRA-7",
        bssid = bssid,
        freshness = freshness,
        resultAgeMillis = ageMillis,
    )

    fun bleSample(
        mac: String = "D1:E2:F3:04:15:26",
        rssi: Int = -77,
        wallClockMillis: Long = TestClock.WALL_START,
        monotonicMillis: Long = TestClock.MONOTONIC_START,
    ) = RadioSample(
        sensorType = SensorType.BLE,
        identifier = mac,
        identifierType = IdentifierType.BLE_MAC_PUBLIC,
        wallClockMillis = wallClockMillis,
        monotonicElapsedMillis = monotonicMillis,
        rssi = rssi,
        txPower = -4,
        freshness = ResultFreshness.FRESH,
    )
}
