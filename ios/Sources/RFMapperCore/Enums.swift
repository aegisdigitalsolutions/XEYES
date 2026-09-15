/// The wire enums, mirroring `core-model/Enums.kt`.
///
/// Every raw value is the Kotlin constant name verbatim. These strings appear in CSV cells, in JSON
/// and in the Python Lab's own enums, so a rename here is a breaking change to the file contract
/// rather than a local refactor.

public enum SensorType: String, Codable, CaseIterable, Sendable {
    case wifiScan = "WIFI_SCAN"
    case wifiAssociation = "WIFI_ASSOCIATION"
    case ble = "BLE"
    case rtt = "RTT"
    case gps = "GPS"

    /// An administrator-declared infrastructure sighting. A human assertion, not a measurement.
    case zoneAnchor = "ZONE_ANCHOR"

    /// Administrator entry, e.g. "device physically seen here".
    case manual = "MANUAL"

    /// Synthesised from an external dataset; requires `metadata.import_source`.
    case imported = "IMPORT"

    public var isRadioMeasurement: Bool {
        switch self {
        case .wifiScan, .wifiAssociation, .ble, .rtt: return true
        default: return false
        }
    }
}

/// What kind of identifier ``Observation/radioIdentifier`` holds.
public enum IdentifierType: String, Codable, CaseIterable, Sendable {
    case wifiBssid = "WIFI_BSSID"
    case wifiSsid = "WIFI_SSID"
    case bleMacPublic = "BLE_MAC_PUBLIC"

    /// A locally-administered (privacy-rotating) BLE address. Recorded, but never automatically
    /// attributed to a device: see `docs/17-identity-and-attribution-policy.md`.
    case bleMacRandom = "BLE_MAC_RANDOM"
    case bleServiceUuid = "BLE_SERVICE_UUID"
    case bleIBeacon = "BLE_IBEACON"
    case gnssFix = "GNSS_FIX"
    case observerSelf = "OBSERVER_SELF"
    case other = "OTHER"

    /// True when this identifier is not a durable identity and must not be treated as one.
    public var isEphemeral: Bool { self == .bleMacRandom }
}

public enum ObserverDeviceType: String, Codable, CaseIterable, Sendable {
    case androidPhone = "ANDROID_PHONE"
    case androidTablet = "ANDROID_TABLET"
    case iosPhone = "IOS_PHONE"
    case iosTablet = "IOS_TABLET"
    case fixedObserver = "FIXED_OBSERVER"
    case other = "OTHER"
}

public enum Platform: String, Codable, CaseIterable, Sendable {
    case android
    case ios
    case other
}

public enum DeviceStatus: String, Codable, CaseIterable, Sendable {
    case authorized = "AUTHORIZED"
    case infrastructure = "INFRASTRUCTURE"
    case blocked = "BLOCKED"
    case disabled = "DISABLED"
    case unknown = "UNKNOWN"
}

/// Distinguishes deliberate survey capture from ordinary collection.
public enum SampleKind: String, Codable, CaseIterable, Sendable {
    case ordinary = "ORDINARY"
    case groundTruth = "GROUND_TRUTH"

    public static func fromWireOrDefault(_ value: String?) -> SampleKind {
        guard let value, let parsed = SampleKind(rawValue: value) else { return .ordinary }
        return parsed
    }
}

/// Whether a scan result came from the scan that just completed, or from the platform cache.
public enum ResultFreshness: String, Codable, CaseIterable, Sendable {
    case fresh = "FRESH"
    case cached = "CACHED"
    case unknown = "UNKNOWN"
}

/// Sensor capabilities an observer installation can actually produce.
///
/// On iOS this set is smaller than on Android and the difference is load-bearing rather than
/// cosmetic: see `docs/06-ios-capability-matrix.md` §6.2.
public enum ObserverCapability: String, Codable, CaseIterable, Sendable {
    case wifiScan = "WIFI_SCAN"
    case wifiAssociation = "WIFI_ASSOCIATION"
    case ble = "BLE"
    case rtt = "RTT"
    case gps = "GPS"
}

public enum ExportKind: String, Codable, CaseIterable, Sendable {
    case day = "DAY"
    case session = "SESSION"
    case range = "RANGE"
}

public enum PackageType: String, Codable, CaseIterable, Sendable {
    case observations = "OBSERVATIONS"
    case derived = "DERIVED"
}
