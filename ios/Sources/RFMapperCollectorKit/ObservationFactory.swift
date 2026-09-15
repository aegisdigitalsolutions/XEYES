import Foundation
import RFMapperCore

/// Turns a platform reading into a canonical ``Observation``.
///
/// Kept separate from the providers, and free of any Apple framework, for two reasons. It is the
/// place where the iOS-specific identity decisions live -- the app-install scope tag, the `OTHER`
/// identifier type, the deliberate absence of a Wi-Fi RSSI -- and those decisions are the ones most
/// worth being able to test without a device. And a provider that also built rows would be two
/// responsibilities in one class, with the schema rules duplicated across three of them.
public struct ObservationFactory {

    public let observer: ObserverIdentity
    public let appVersion: String
    public let osVersion: String
    public let deviceModel: String

    /// Injected so tests are deterministic, and so a single capture batch can share one instant.
    public var now: () -> Int64
    public var newIdentifier: () -> String

    public init(
        observer: ObserverIdentity,
        appVersion: String,
        osVersion: String,
        deviceModel: String,
        now: @escaping () -> Int64,
        newIdentifier: @escaping () -> String
    ) {
        self.observer = observer
        self.appVersion = appVersion
        self.osVersion = osVersion
        self.deviceModel = deviceModel
        self.now = now
        self.newIdentifier = newIdentifier
    }

    // MARK: - BLE

    /// A BLE advertisement seen by CoreBluetooth.
    ///
    /// `peripheralIdentifier` is a `CBPeripheral.identifier`, which is **not** a hardware address:
    /// it is a UUID scoped to this peripheral, this app install and this device. Three consequences
    /// are encoded here rather than left to a consumer to infer:
    ///
    /// - `identifier_type` is ``IdentifierType/other``. Claiming `BLE_MAC_PUBLIC` or
    ///   `BLE_MAC_RANDOM` would invite a reader to treat it as an address.
    /// - `metadata.identifier_scope` is `APP_INSTALL`, which is the field that stops the Lab
    ///   joining it to another observer's sighting of something else.
    /// - The service UUID is carried separately, because it is the only durable key by which this
    ///   sighting can be joined to an Android sighting of the same hardware
    ///   (`docs/06-ios-capability-matrix.md` §3).
    public func ble(
        peripheralIdentifier: String,
        rssi: Int,
        serviceUuids: [String] = [],
        manufacturerData: [UInt8]? = nil,
        serviceData: [String: [UInt8]] = [:],
        txPower: Int? = nil,
        localName: String? = nil,
        isConnectable: Bool? = nil,
        targetDeviceId: String? = nil,
        sessionId: String,
        zoneId: String? = nil,
        confidence: Double
    ) throws -> Observation {
        guard let normalized = RadioIdentifierNormalizer.iosPeripheral(peripheralIdentifier) else {
            throw CaptureError.unusableIdentifier(peripheralIdentifier)
        }

        var metadata = baseMetadata(sessionId: sessionId)
        metadata[MetadataKeys.identifierScope] = IdentifierScope.appInstall.rawValue
        metadata[MetadataKeys.iosPeripheralIdentifier] = normalized
        if let localName { metadata[MetadataKeys.bleDeviceName] = localName }
        if let isConnectable {
            metadata[MetadataKeys.bleIsConnectable] = isConnectable ? "true" : "false"
        }
        if !serviceUuids.isEmpty {
            // All of them, sorted, so a device advertising several is not reduced to whichever one
            // happened to be first in the advertisement.
            metadata[MetadataKeys.bleServiceUuids] = serviceUuids
                .compactMap(RadioIdentifierNormalizer.uuid)
                .sorted()
                .joined(separator: ",")
        }
        if !serviceData.isEmpty {
            metadata[MetadataKeys.bleServiceData] = serviceData.keys.sorted()
                .compactMap { key in
                    RadioIdentifierNormalizer.manufacturerData(serviceData[key]!).map { "\(key)=\($0)" }
                }
                .joined(separator: ",")
        }

        return try Observation.validated(Observation(
            observationId: newIdentifier(),
            timestampUtc: Iso8601.format(now()),
            observerId: observer.observerId,
            observerDeviceType: observer.observerDeviceType,
            sensorType: .ble,
            targetDeviceId: targetDeviceId,
            radioIdentifier: normalized,
            identifierType: .other,
            bleServiceUuid: serviceUuids.first.flatMap(RadioIdentifierNormalizer.uuid),
            manufacturerData: manufacturerData.flatMap(RadioIdentifierNormalizer.manufacturerData),
            rssi: rssi,
            txPower: txPower,
            buildingId: observer.buildingId,
            zoneId: zoneId ?? observer.defaultZoneId,
            confidence: confidence,
            metadata: metadata
        ))
    }

    // MARK: - Wi-Fi association

    /// The network this device is currently joined to.
    ///
    /// **This row carries no `rssi`, and that is correct rather than a gap to be filled.** iOS does
    /// not expose the associated network's signal strength to a third-party app.
    /// `NEHotspotNetwork.signalStrength` exists but is a coarse bar-level value documented as
    /// unspecified; mapping it onto a dBm scale would be inventing a measurement, which is the exact
    /// failure the schema's separation of raw evidence from derived inference exists to prevent.
    ///
    /// What the row does say is categorical and genuinely useful: *this device was attached to this
    /// access point at this instant*. A station associates with exactly one access point and only
    /// within its range, so on an association-based site that fact alone places the device in the
    /// access point's zone (`docs/19-association-only-deployment.md`).
    public func association(
        bssid: String,
        ssid: String?,
        sessionId: String,
        zoneId: String? = nil,
        confidence: Double
    ) throws -> Observation {
        guard let normalized = RadioIdentifierNormalizer.mac(bssid) else {
            throw CaptureError.unusableIdentifier(bssid)
        }

        var metadata = baseMetadata(sessionId: sessionId)
        metadata[MetadataKeys.identifierScope] = IdentifierScope.global.rawValue
        metadata[MetadataKeys.isConnected] = "true"
        // Says why the signal level is missing, so a consumer does not read the absence as a
        // collection fault or, worse, quietly substitute something.
        metadata[MetadataKeys.permissionDegraded] = "true"
        metadata[MetadataKeys.missingPermissions] = Degradation.wifiRssiUnavailable

        return try Observation.validated(Observation(
            observationId: newIdentifier(),
            timestampUtc: Iso8601.format(now()),
            observerId: observer.observerId,
            observerDeviceType: observer.observerDeviceType,
            sensorType: .wifiAssociation,
            radioIdentifier: normalized,
            identifierType: .wifiBssid,
            ssid: ssid.flatMap(RadioIdentifierNormalizer.ssid),
            bssid: normalized,
            rssi: nil,
            buildingId: observer.buildingId,
            zoneId: zoneId ?? observer.defaultZoneId,
            confidence: confidence,
            metadata: metadata
        ))
    }

    // MARK: - Location

    /// A GNSS fix, which describes where *this observer* was.
    ///
    /// Not where any observed device is. The schema keeps the two apart deliberately, and this is
    /// the row most likely to be misread as the latter.
    public func location(
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Double,
        sessionId: String,
        reducedAccuracy: Bool,
        confidence: Double
    ) throws -> Observation {
        var metadata = baseMetadata(sessionId: sessionId)
        if reducedAccuracy {
            // iOS 14's approximate-location mode returns a fix good to kilometres. Usable for "which
            // site", useless for anything finer, and the record has to say so.
            metadata[MetadataKeys.permissionDegraded] = "true"
            metadata[MetadataKeys.missingPermissions] = Degradation.locationReducedAccuracy
        }

        return try Observation.validated(Observation(
            observationId: newIdentifier(),
            timestampUtc: Iso8601.format(now()),
            observerId: observer.observerId,
            observerDeviceType: observer.observerDeviceType,
            sensorType: .gps,
            radioIdentifier: "OBSERVER_SELF",
            identifierType: .gnssFix,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            buildingId: observer.buildingId,
            zoneId: observer.defaultZoneId,
            confidence: confidence,
            metadata: metadata
        ))
    }

    private func baseMetadata(sessionId: String) -> [String: String] {
        [
            MetadataKeys.sessionId: sessionId,
            MetadataKeys.platform: "ios",
            MetadataKeys.osVersion: osVersion,
            MetadataKeys.deviceModel: deviceModel,
            MetadataKeys.appVersion: appVersion,
            MetadataKeys.installationId: observer.installationId ?? "",
            MetadataKeys.resultFreshness: ResultFreshness.fresh.rawValue,
            MetadataKeys.sampleKind: SampleKind.ordinary.rawValue,
        ].filter { !$0.value.isEmpty }
    }
}

public enum CaptureError: Error, CustomStringConvertible {
    case unusableIdentifier(String)

    public var description: String {
        switch self {
        case .unusableIdentifier(let raw):
            return "could not normalize the radio identifier '\(raw)'"
        }
    }
}
