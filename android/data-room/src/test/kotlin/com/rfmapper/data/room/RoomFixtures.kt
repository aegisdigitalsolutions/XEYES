package com.rfmapper.data.room

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.radio.RadioSample

object RoomFixtures {

    const val OBSERVER = "OBS-04"
    const val SESSION = "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40"
    val DAY_START: Long = requireNotNull(Iso8601.parseToEpochMillis("2026-09-14T06:11:02.310Z"))

    fun observationId(index: Int): String =
        "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"

    fun observer(observerId: String = OBSERVER) = ObserverIdentity(
        observerId = observerId,
        friendlyName = "Building 4 Android Collector",
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        buildingId = "B4",
        defaultZoneId = "B4-CENTER",
        deviceModel = "Pixel 7a",
        manufacturer = "Google",
        platform = Platform.ANDROID,
        osVersion = "34",
        appVersion = "1.0.0",
        installationId = "b1c2d3e4-1111-4222-8333-444455556666",
        capabilities = setOf(ObserverCapability.WIFI_SCAN, ObserverCapability.BLE),
        unsupported = setOf(ObserverCapability.RTT),
    )

    fun observation(
        index: Int,
        observerId: String = OBSERVER,
        atMillis: Long = DAY_START + index * 1_000L,
    ): Observation {
        val isBle = index % 3 == 0
        return Observation(
            observationId = observationId(index),
            timestampUtc = Iso8601.format(atMillis),
            observerId = observerId,
            observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
            sensorType = if (isBle) SensorType.BLE else SensorType.WIFI_SCAN,
            radioIdentifier = if (isBle) "d1:e2:f3:04:15:26" else "aa:bb:cc:11:22:33",
            identifierType = if (isBle) IdentifierType.BLE_MAC_PUBLIC else IdentifierType.WIFI_BSSID,
            ssid = if (isBle) null else "SITE-INFRA-7",
            bssid = if (isBle) null else "aa:bb:cc:11:22:33",
            rssi = -61 - (index % 7),
            txPower = if (isBle) -4 else null,
            frequency = if (isBle) null else 5180,
            channel = if (isBle) null else 36,
            buildingId = "B7",
            zoneId = "B7-CENTER",
            confidence = 0.9,
            metadata = mapOf(
                MetadataKeys.SESSION_ID to SESSION,
                MetadataKeys.RESULT_FRESHNESS to "FRESH",
            ),
        )
    }

    fun surveySample(
        index: Int,
        surveyPointId: String,
        surveySessionId: String = "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
    ): Observation = observation(index).copy(
        metadata = mapOf(
            MetadataKeys.SESSION_ID to SESSION,
            MetadataKeys.SAMPLE_KIND to SampleKind.GROUND_TRUTH.name,
            MetadataKeys.SURVEY_POINT_ID to surveyPointId,
            MetadataKeys.SURVEY_SESSION_ID to surveySessionId,
        ),
    )
}

/** Radio samples as a provider would deliver them, for driving the collection engine in tests. */
object FakeSamples {

    fun wifi(
        bssid: String,
        rssi: Int,
        wallClockMillis: Long,
        monotonicMillis: Long,
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
        freshness = ResultFreshness.FRESH,
        resultAgeMillis = 1_200,
    )

    fun ble(
        mac: String,
        rssi: Int,
        wallClockMillis: Long,
        monotonicMillis: Long,
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

    fun randomBle(
        mac: String,
        wallClockMillis: Long,
        monotonicMillis: Long,
    ) = ble(mac, rssi = -82, wallClockMillis = wallClockMillis, monotonicMillis = monotonicMillis)
        .copy(identifierType = IdentifierType.BLE_MAC_RANDOM)
}
