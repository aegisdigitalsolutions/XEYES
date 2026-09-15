import Foundation
import RFMapperCore

/// A fixed, iOS-shaped day of collection, used to generate the cross-language contract fixture.
///
/// It sits in the same site as the Kotlin fixtures -- building `B1`, zones `B1-NORTH` and
/// `B1-SOUTH`, access point `AP-NORTH`, managed device `DEVICE-TAG-1` -- so the Python Lab can run
/// the same reference model over an iOS package as over an Android one.
///
/// Every value is hard-coded. Nothing here reads a clock, a random source or the environment,
/// because a fixture that changed between runs could not detect a change in the code.
///
/// The scenario is chosen to cover what an iOS Collector can *actually* produce, including the parts
/// that differ awkwardly from Android:
///
/// 1. **BLE sightings keyed by an app-install-scoped UUID.** CoreBluetooth never discloses a
///    hardware address, so `radio_identifier` is a `CBPeripheral.identifier` and
///    `identifier_type` is `OTHER`. Joining these to an Android sighting of the same tag is
///    impossible on the address and must happen through the service UUID.
/// 2. **A BLE sighting with no service UUID**, which therefore cannot be joined to anything at all.
///    Recorded honestly rather than guessed at.
/// 3. **A Wi-Fi association with no RSSI.** iOS exposes the BSSID of the network it is joined to but
///    not that network's signal strength, so this is the one Wi-Fi record iOS can emit, and it
///    carries no signal level. See ``IosContractScenario/associationRows``.
/// 4. **A GNSS fix**, where iOS is at parity with Android.
/// 5. **An SSID containing a comma and a quote**, to keep the CSV dialect honest.
public enum IosContractScenario {

    public static let observerId = "OBS-I1"
    public static let buildingId = "B1"
    public static let northZone = "B1-NORTH"
    public static let southZone = "B1-SOUTH"

    /// `AP-NORTH` in the shared site model.
    public static let northApBssid = "aa:bb:cc:00:00:01"

    /// `DEVICE-TAG-1`'s durable identifier in the shared device registry.
    public static let tagServiceUuid = "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162"

    /// The `CBPeripheral.identifier` this install happens to see the tag under. Stable for this app
    /// install and meaningless to any other observer, which is the entire point.
    public static let tagPeripheralUuid = "9f8e7d6c-5b4a-4392-8281-706f5e4d3c2b"
    public static let strangerPeripheralUuid = "1a2b3c4d-5e6f-4071-8283-94a5b6c7d8e9"

    public static let sessionId = "00000000-0000-4000-9000-000000000101"
    public static let exportId = "00000000-0000-4000-a000-000000000071"
    public static let installationId = "a1b2c3d4-2222-4333-8444-5555666677a1"

    public static let dayStart = Iso8601.parseToEpochMillis("2026-05-04T00:00:00.000Z")!
    public static let firstSample = Iso8601.parseToEpochMillis("2026-05-04T11:00:00.000Z")!

    /// Evening of the day being exported. The Kotlin `PackageValidator` rejects a package whose
    /// rows postdate its own `created_at`, so this must sit after the last sample.
    public static let createdAt = Iso8601.parseToEpochMillis("2026-05-04T20:00:00.000Z")!

    public static let observer = ObserverIdentity(
        observerId: observerId,
        friendlyName: "Survey iPhone",
        observerDeviceType: .iosPhone,
        buildingId: buildingId,
        defaultZoneId: northZone,
        deviceModel: "iPhone15,2",
        manufacturer: "Apple",
        platform: .ios,
        osVersion: "18.4",
        appVersion: "1.0.0",
        installationId: installationId,
        // The load-bearing declaration. An iOS observer reporting no Wi-Fi scan results means "this
        // observer cannot scan Wi-Fi", not "no access points were present". Without `unsupported`
        // the Lab would read the silence as evidence of absence and drive every fingerprint's Wi-Fi
        // visibility probability toward zero.
        capabilities: [.ble, .gps, .wifiAssociation],
        unsupported: [.wifiScan, .rtt],
        // A handheld phone is not a fixed observer, whatever its coordinates happen to be.
        fixedObserver: false,
        notes: "Supervised foreground survey; iOS cannot collect unattended."
    )

    public static func observations() throws -> [Observation] {
        var rows: [Observation] = []
        rows.append(contentsOf: try bleTagRows())
        rows.append(contentsOf: try strangerRows())
        rows.append(contentsOf: try associationRows())
        rows.append(try gnssRow())
        rows.append(try awkwardSsidRow())

        // The export engine requires (timestamp_utc, observation_id) order and will refuse the
        // package otherwise. Sorting here rather than relying on construction order keeps the
        // scenario readable.
        return rows.sorted {
            ($0.timestampUtc, $0.observationId) < ($1.timestampUtc, $1.observationId)
        }
    }

    // MARK: - Rows

    /// The enrolled tag, seen under an app-install-scoped peripheral UUID but carrying the service
    /// UUID that makes it joinable to an Android sighting of the same hardware.
    private static func bleTagRows() throws -> [Observation] {
        let levels = [-58, -55, -53, -51, -54, -57]
        return try levels.enumerated().map { index, rssi in
            try row(
                sequence: 1_000 + index,
                offsetSeconds: index * 5,
                sensorType: .ble,
                radioIdentifier: tagPeripheralUuid,
                identifierType: .other,
                zoneId: index < 3 ? northZone : southZone,
                rssi: rssi,
                txPower: -8,
                bleServiceUuid: tagServiceUuid,
                manufacturerData: "004C0215A1B2",
                targetDeviceId: "DEVICE-TAG-1",
                confidence: 0.9,
                extraMetadata: [
                    MetadataKeys.bleDeviceName: "TOOLBOX-1",
                    MetadataKeys.bleIsConnectable: "false",
                    MetadataKeys.attributionSource: "SERVICE_UUID_MATCH",
                ]
            )
        }
    }

    /// An unenrolled peripheral with no service UUID. There is no honest way to join this to an
    /// observation from any other device, and the record says so by scope alone.
    private static func strangerRows() throws -> [Observation] {
        try (0..<3).map { index in
            try row(
                sequence: 1_100 + index,
                offsetSeconds: 2 + index * 5,
                sensorType: .ble,
                radioIdentifier: strangerPeripheralUuid,
                identifierType: .other,
                zoneId: northZone,
                rssi: -77 + index,
                confidence: 0.6
            )
        }
    }

    /// The only Wi-Fi record an iOS Collector can produce, and the reason it is interesting:
    /// **it has no `rssi`.**
    ///
    /// `NEHotspotNetwork.fetchCurrent` yields the BSSID of the network the device is joined to, but
    /// iOS does not expose that network's signal strength to a third-party app.
    /// `NEHotspotNetwork.signalStrength` is a coarse bar-level value documented as unspecified, and
    /// putting it in `rssi` would be inventing a measurement -- precisely the failure the schema's
    /// separation of raw and derived exists to prevent.
    ///
    /// So the record is categorical: *this device was attached to this access point at this
    /// instant*. For an association-based deployment that is the whole signal, and it is exactly the
    /// shape the Lab has to be able to consume. See `docs/19-association-only-deployment.md`.
    private static func associationRows() throws -> [Observation] {
        try (0..<4).map { index in
            try row(
                sequence: 1_200 + index,
                offsetSeconds: 1 + index * 20,
                sensorType: .wifiAssociation,
                radioIdentifier: northApBssid,
                identifierType: .wifiBssid,
                zoneId: northZone,
                // No rssi, and no substitute for one.
                rssi: nil,
                ssid: "RFMAPPER-SITE",
                bssid: northApBssid,
                confidence: 0.75,
                extraMetadata: [
                    MetadataKeys.isConnected: "true",
                    MetadataKeys.permissionDegraded: "true",
                    MetadataKeys.missingPermissions: "WIFI_RSSI_UNAVAILABLE_ON_IOS",
                ]
            )
        }
    }

    private static func gnssRow() throws -> Observation {
        try row(
            sequence: 1_300,
            offsetSeconds: 30,
            sensorType: .gps,
            radioIdentifier: "OBSERVER_SELF",
            identifierType: .gnssFix,
            zoneId: northZone,
            latitude: 51.50742,
            longitude: -0.12781,
            horizontalAccuracy: 14.5,
            confidence: 0.8
        )
    }

    /// An SSID with a comma and a quote in it. Legal, and the single most likely thing to break a
    /// naive CSV writer.
    private static func awkwardSsidRow() throws -> Observation {
        try row(
            sequence: 1_400,
            offsetSeconds: 95,
            sensorType: .wifiAssociation,
            radioIdentifier: "bb:cc:dd:00:00:09",
            identifierType: .wifiBssid,
            zoneId: southZone,
            ssid: "Guest, \"Public\"",
            bssid: "bb:cc:dd:00:00:09",
            confidence: 0.5,
            extraMetadata: [MetadataKeys.isConnected: "true"]
        )
    }

    // MARK: - Construction

    /// Builds one row with every invariant-critical field passed explicitly.
    ///
    /// Taking these as named parameters rather than mutating a template is deliberate: the schema's
    /// cross-field invariants (a GPS fix needs both coordinates, x/y need a building, RTT fields
    /// need an RTT sensor) are easy to violate by forgetting to set a field, and the compiler cannot
    /// see that.
    private static func row(
        sequence: Int,
        offsetSeconds: Int,
        sensorType: SensorType,
        radioIdentifier: String,
        identifierType: IdentifierType,
        zoneId: String,
        rssi: Int? = nil,
        txPower: Int? = nil,
        ssid: String? = nil,
        bssid: String? = nil,
        bleServiceUuid: String? = nil,
        manufacturerData: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        horizontalAccuracy: Double? = nil,
        targetDeviceId: String? = nil,
        confidence: Double,
        extraMetadata: [String: String] = [:]
    ) throws -> Observation {
        var metadata: [String: String] = [
            MetadataKeys.sessionId: sessionId,
            MetadataKeys.platform: "ios",
            MetadataKeys.osVersion: "18.4",
            MetadataKeys.deviceModel: "iPhone15,2",
            MetadataKeys.appVersion: "1.0.0",
            MetadataKeys.installationId: installationId,
            MetadataKeys.resultFreshness: ResultFreshness.fresh.rawValue,
            MetadataKeys.sampleKind: SampleKind.ordinary.rawValue,
        ]
        // An app-install-scoped identifier must be labelled as such, or a consumer may treat it as
        // an address and join it to another observer's sighting of a different peripheral.
        if identifierType == .other {
            metadata[MetadataKeys.identifierScope] = IdentifierScope.appInstall.rawValue
            metadata[MetadataKeys.iosPeripheralIdentifier] = radioIdentifier
        } else if sensorType == .ble {
            metadata[MetadataKeys.identifierScope] = IdentifierScope.global.rawValue
        }
        for (key, value) in extraMetadata {
            metadata[key] = value
        }

        return try Observation.validated(Observation(
            observationId: deterministicId(sequence),
            timestampUtc: Iso8601.format(firstSample + Int64(offsetSeconds) * 1_000),
            observerId: observerId,
            observerDeviceType: .iosPhone,
            sensorType: sensorType,
            targetDeviceId: targetDeviceId,
            radioIdentifier: radioIdentifier,
            identifierType: identifierType,
            ssid: ssid,
            bssid: bssid,
            bleServiceUuid: bleServiceUuid,
            manufacturerData: manufacturerData,
            rssi: rssi,
            txPower: txPower,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            buildingId: buildingId,
            zoneId: zoneId,
            confidence: confidence,
            metadata: metadata
        ))
    }

    /// A UUID-shaped identifier derived from a counter, so the fixture's ids are stable.
    ///
    /// Version and variant nibbles are set so the value passes the schema's UUID check; it is not a
    /// random UUID and is not pretending to be one.
    static func deterministicId(_ sequence: Int) -> String {
        let hex = String(sequence, radix: 16)
        let tail = String(repeating: "0", count: 12 - hex.count) + hex
        return "00000000-0000-4000-8000-\(tail)"
    }

    public static func summary(for observations: [Observation]) -> ObservationSummary {
        var counts: [String: Int64] = [:]
        var groundTruth: Int64 = 0
        for observation in observations {
            counts[observation.sensorType.rawValue, default: 0] += 1
            if observation.sampleKind == .groundTruth { groundTruth += 1 }
        }
        return ObservationSummary(
            observationCount: Int64(observations.count),
            firstObservationUtc: observations.first?.timestampUtc,
            lastObservationUtc: observations.last?.timestampUtc,
            countsBySensorType: counts,
            groundTruthCount: groundTruth,
            sessionIds: [sessionId]
        )
    }

    public static func session(for observations: [Observation]) -> SessionSummary {
        func count(_ sensor: SensorType) -> Int64 {
            Int64(observations.filter { $0.sensorType == sensor }.count)
        }
        return SessionSummary(
            sessionId: sessionId,
            startedAt: Iso8601.format(firstSample - 60_000),
            endedAt: Iso8601.format(firstSample + 120_000),
            scanProfile: "IOS_FOREGROUND_SURVEY",
            buildingId: buildingId,
            zoneId: northZone,
            observationCount: Int64(observations.count),
            // Association records are Wi-Fi records; iOS simply cannot produce the scanning kind.
            wifiCount: count(.wifiAssociation) + count(.wifiScan),
            bleCount: count(.ble),
            rttCount: 0,
            gpsCount: count(.gps),
            droppedSamples: 0,
            throttledScanRequests: 0,
            backgroundDenied: true,
            suspectedServiceKill: false,
            batteryStartPct: 88,
            batteryEndPct: 84,
            // Recorded as degradations rather than left as an unexplained gap in the data.
            degradations: [
                "WIFI_SCAN_UNSUPPORTED",
                "RTT_UNSUPPORTED",
                "BACKGROUND_BLE_REQUIRES_SERVICE_FILTER",
            ]
        )
    }

    public static func request(for observations: [Observation]) -> ExportRequest {
        ExportRequest(
            exportId: exportId,
            observer: observer,
            exportKind: .day,
            createdAtEpochMillis: createdAt,
            summary: summary(for: observations),
            dateRange: DateRange(
                from: Iso8601.format(dayStart),
                to: Iso8601.format(dayStart + Iso8601.millisPerDay - 1)
            ),
            sessions: [session(for: observations)],
            appVersion: "1.0.0",
            generator: Generator(name: "RFMapper iOS Collector", version: "1.0.0")
        )
    }
}
