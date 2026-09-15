/// Readers for the JSON forms the core writes.
///
/// The on-device store keeps each row as its canonical JSON rather than as a column per field, so
/// that a minor schema version adding an optional field does not force a database migration --
/// which is the forward-compatibility rule the contract promises. That design needs a decoder, and
/// this is it.
///
/// Unknown keys are ignored rather than rejected, matching `RfMapperJson`'s
/// `ignoreUnknownKeys = true`. Rejecting an unknown *major* version is a separate, explicit check:
/// see ``SchemaVersion/isReadable(_:)``.

extension Observation {

    /// Decodes a row written by ``Observation/json``.
    ///
    /// Returns nil rather than throwing on malformed input, because the one caller that matters --
    /// the store -- treats an undecodable row as a reportable fault and needs no detail beyond that.
    /// A row that decodes but violates an invariant is also nil: an `Observation` that could not
    /// have been captured must not be reconstructible from storage either.
    public init?(compactJSON text: String) {
        guard let value = MiniJSON.parse(text), let members = value.asObject else { return nil }
        self.init(members: members)
    }

    init?(members: [String: MiniJSON.Value]) {
        func text(_ key: String) -> String? { members[key]?.asString }
        func integer(_ key: String) -> Int? { members[key]?.asInt }
        func decimal(_ key: String) -> Double? { members[key]?.asDouble }

        guard let observationId = text("observation_id"),
              let timestampUtc = text("timestamp_utc"),
              let observerId = text("observer_id"),
              let radioIdentifier = text("radio_identifier"),
              let deviceType = text("observer_device_type").flatMap(ObserverDeviceType.init(rawValue:)),
              let sensorType = text("sensor_type").flatMap(SensorType.init(rawValue:)),
              let identifierType = text("identifier_type").flatMap(IdentifierType.init(rawValue:))
        else { return nil }

        var metadata: [String: String] = [:]
        if let raw = members["metadata"]?.asObject {
            for (key, member) in raw {
                guard let value = member.asString else { return nil }
                metadata[key] = value
            }
        }

        let candidate = Observation(
            observationId: observationId,
            schemaVersion: text("schema_version") ?? SchemaVersion.current,
            timestampUtc: timestampUtc,
            observerId: observerId,
            observerDeviceType: deviceType,
            sensorType: sensorType,
            targetDeviceId: text("target_device_id"),
            radioIdentifier: radioIdentifier,
            identifierType: identifierType,
            ssid: text("ssid"),
            bssid: text("bssid"),
            bleServiceUuid: text("ble_service_uuid"),
            manufacturerData: text("manufacturer_data"),
            rssi: integer("rssi"),
            txPower: integer("tx_power"),
            frequency: integer("frequency"),
            channel: integer("channel"),
            rttDistanceMm: integer("rtt_distance_mm"),
            rttStddevMm: integer("rtt_stddev_mm"),
            latitude: decimal("latitude"),
            longitude: decimal("longitude"),
            horizontalAccuracy: decimal("horizontal_accuracy"),
            buildingId: text("building_id"),
            zoneId: text("zone_id"),
            xCoordinate: decimal("x_coordinate"),
            yCoordinate: decimal("y_coordinate"),
            confidence: decimal("confidence"),
            metadata: metadata
        )

        guard ObservationInvariants.check(candidate).isEmpty else { return nil }
        self = candidate
    }
}

extension SessionSummary {

    public init?(json text: String) {
        guard let value = MiniJSON.parse(text), let members = value.asObject else { return nil }
        guard let sessionId = members["session_id"]?.asString,
              let startedAt = members["started_at"]?.asString,
              let scanProfile = members["scan_profile"]?.asString
        else { return nil }

        func counter(_ key: String) -> Int64 {
            Int64(members[key]?.asInt ?? 0)
        }
        func flag(_ key: String) -> Bool {
            if case .bool(let value) = members[key] { return value }
            return false
        }

        self.init(
            sessionId: sessionId,
            startedAt: startedAt,
            endedAt: members["ended_at"]?.asString,
            scanProfile: scanProfile,
            buildingId: members["building_id"]?.asString,
            zoneId: members["zone_id"]?.asString,
            observationCount: counter("observation_count"),
            wifiCount: counter("wifi_count"),
            bleCount: counter("ble_count"),
            rttCount: counter("rtt_count"),
            gpsCount: counter("gps_count"),
            droppedSamples: counter("dropped_samples"),
            throttledScanRequests: counter("throttled_scan_requests"),
            backgroundDenied: flag("background_denied"),
            suspectedServiceKill: flag("suspected_service_kill"),
            batteryStartPct: members["battery_start_pct"]?.asInt,
            batteryEndPct: members["battery_end_pct"]?.asInt,
            degradations: members["degradations"]?.asArray?.compactMap(\.asString) ?? []
        )
    }
}

extension ObserverIdentity {

    public init?(json text: String) {
        guard let value = MiniJSON.parse(text), let members = value.asObject else { return nil }
        guard let observerId = members["observer_id"]?.asString,
              let friendlyName = members["friendly_name"]?.asString,
              let appVersion = members["app_version"]?.asString,
              let deviceType = members["observer_device_type"]?.asString
                  .flatMap(ObserverDeviceType.init(rawValue:)),
              let platform = members["platform"]?.asString.flatMap(Platform.init(rawValue:))
        else { return nil }

        func capabilities(_ key: String) -> Set<ObserverCapability> {
            Set(
                (members[key]?.asArray ?? [])
                    .compactMap(\.asString)
                    .compactMap(ObserverCapability.init(rawValue:))
            )
        }
        func flag(_ key: String) -> Bool {
            if case .bool(let value) = members[key] { return value }
            return false
        }

        self.init(
            schemaVersion: members["schema_version"]?.asString ?? SchemaVersion.current,
            observerId: observerId,
            friendlyName: friendlyName,
            observerDeviceType: deviceType,
            buildingId: members["building_id"]?.asString,
            defaultZoneId: members["default_zone_id"]?.asString,
            deviceModel: members["device_model"]?.asString,
            manufacturer: members["manufacturer"]?.asString,
            platform: platform,
            osVersion: members["os_version"]?.asString,
            appVersion: appVersion,
            installationId: members["installation_id"]?.asString,
            capabilities: capabilities("capabilities"),
            unsupported: capabilities("unsupported"),
            xCoordinate: members["x_coordinate"]?.asDouble,
            yCoordinate: members["y_coordinate"]?.asDouble,
            fixedObserver: flag("fixed_observer"),
            notes: members["notes"]?.asString
        )
    }
}
