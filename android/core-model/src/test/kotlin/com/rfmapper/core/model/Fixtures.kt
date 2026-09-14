package com.rfmapper.core.model

/** Shared, deliberately realistic test data mirroring the worked example in the CSV specification. */
object Fixtures {

    const val OBSERVER = "OBS-04"
    const val AP_BSSID = "aa:bb:cc:11:22:33"

    fun wifiScan(
        observationId: String = "4f1c9a2e-6b3d-4c58-9e77-0a1b2c3d4e5f",
        timestampUtc: String = "2026-09-14T08:19:04.312Z",
        observerId: String = OBSERVER,
        metadata: Map<String, String> = mapOf(MetadataKeys.RESULT_FRESHNESS to ResultFreshness.FRESH.name),
    ) = Observation(
        observationId = observationId,
        timestampUtc = timestampUtc,
        observerId = observerId,
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        sensorType = SensorType.WIFI_SCAN,
        radioIdentifier = AP_BSSID,
        identifierType = IdentifierType.WIFI_BSSID,
        ssid = "SITE-INFRA-7",
        bssid = AP_BSSID,
        rssi = -61,
        frequency = 5180,
        channel = 36,
        buildingId = "B7",
        zoneId = "B7-CENTER",
        xCoordinate = 24.5,
        yCoordinate = 11.0,
        confidence = 0.9,
        metadata = metadata,
    )

    fun ble(
        observationId: String = "7a2b0c11-2d3e-4f50-8192-a3b4c5d6e7f8",
        targetDeviceId: String? = "DEVICE-03",
    ) = Observation(
        observationId = observationId,
        timestampUtc = "2026-09-14T08:19:05.004Z",
        observerId = OBSERVER,
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        sensorType = SensorType.BLE,
        targetDeviceId = targetDeviceId,
        radioIdentifier = "d1:e2:f3:04:15:26",
        identifierType = IdentifierType.BLE_MAC_PUBLIC,
        bleServiceUuid = "0000180f-0000-1000-8000-00805f9b34fb",
        manufacturerData = "004C0215A1B2",
        rssi = -73,
        txPower = 4,
        buildingId = "B7",
        zoneId = "B7-CENTER",
        xCoordinate = 24.5,
        yCoordinate = 11.0,
        confidence = 0.85,
        metadata = mapOf(MetadataKeys.BLE_DEVICE_NAME to "TAG-03"),
    )

    fun rtt(observationId: String = "b3c4d5e6-7f80-4912-a3b4-c5d6e7f80912") = Observation(
        observationId = observationId,
        timestampUtc = "2026-09-14T08:19:06.550Z",
        observerId = OBSERVER,
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        sensorType = SensorType.RTT,
        radioIdentifier = AP_BSSID,
        identifierType = IdentifierType.WIFI_BSSID,
        bssid = AP_BSSID,
        rssi = -58,
        frequency = 5180,
        channel = 36,
        rttDistanceMm = 11_840,
        rttStddevMm = 1_320,
        buildingId = "B7",
        zoneId = "B7-CENTER",
        xCoordinate = 24.5,
        yCoordinate = 11.0,
        confidence = 0.95,
        metadata = mapOf(MetadataKeys.RTT_NUM_SUCCESSFUL to "7"),
    )

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
        capabilities = setOf(
            ObserverCapability.WIFI_SCAN,
            ObserverCapability.WIFI_ASSOCIATION,
            ObserverCapability.BLE,
            ObserverCapability.GPS,
        ),
        unsupported = setOf(ObserverCapability.RTT),
    )

    /** A deterministic sequence of observations, useful for export/import round trips. */
    fun sequence(count: Int, observerId: String = OBSERVER): List<Observation> =
        (0 until count).map { index ->
            wifiScan(
                observationId = uuid(index),
                timestampUtc = Iso8601.format(1_757_836_744_312L + index * 1_000L),
                observerId = observerId,
            )
        }

    fun uuid(index: Int): String {
        val hex = index.toString(16).padStart(12, '0')
        return "00000000-0000-4000-8000-$hex"
    }
}
