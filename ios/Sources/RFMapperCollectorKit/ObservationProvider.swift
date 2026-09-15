import Foundation
import RFMapperCore

/// What a capture source hands to the store.
///
/// Providers are deliberately per-capability rather than one `RadioProvider` interface with optional
/// methods. `docs/06-ios-capability-matrix.md` §6.1 is explicit about why: iOS supplies BLE,
/// location and association, and **cannot** supply Wi-Fi scanning or RTT at all. With a single
/// interface, the iOS implementations of the missing capabilities would have to exist and return
/// nothing, which is indistinguishable at the call site from a working scanner that found nothing.
public protocol ObservationProvider: AnyObject {

    /// The capability this provider supplies. It appears in `observer.json`, where its presence or
    /// absence changes how the Lab reads silence from this observer.
    var capability: ObserverCapability { get }

    var isAvailable: Bool { get }

    func start()
    func stop()
}

/// Where captured rows go, and where a provider reports that it could not capture something.
///
/// `recordDegradation` is not a logging convenience. A Collector that quietly produces fewer rows
/// because a permission was declined or the system throttled a scan is worse than one that fails:
/// the gap looks like an absence of radios rather than an absence of data, and the Lab has no way to
/// tell the difference. Recording the loss is what keeps an unexplained coverage hole from becoming
/// a silent lie about the site.
public protocol ObservationSink: AnyObject {
    func record(_ observation: Observation)
    func recordDegradation(_ reason: String, detail: String?)
    func recordDropped(count: Int, reason: String)
}

/// The scan cadence, which on iOS is a battery and thermal decision as much as a data one.
public enum ScanProfile: String, CaseIterable, Sendable {
    /// Continuous BLE with duplicates allowed. Screen on, mains power preferred.
    case foregroundSurvey = "IOS_FOREGROUND_SURVEY"

    /// Duty-cycled, for a longer supervised session on battery.
    case foregroundEconomy = "IOS_FOREGROUND_ECONOMY"

    /// Service-filtered only, which is all the system permits once the app is not in front.
    case backgroundFiltered = "IOS_BACKGROUND_FILTERED"

    public var advertisesDuplicates: Bool {
        // In the background the option is ignored by the system regardless of what we ask for.
        self != .backgroundFiltered
    }

    public var requiresServiceFilter: Bool {
        self == .backgroundFiltered
    }

    public var humanDescription: String {
        switch self {
        case .foregroundSurvey:
            return "Continuous — screen must stay on"
        case .foregroundEconomy:
            return "Duty-cycled — longer battery life"
        case .backgroundFiltered:
            return "Background — enrolled tags only"
        }
    }
}

/// Reasons a capture was degraded, recorded into `metadata.missing_permissions` and the session's
/// `degradations` list so a gap in the data has a stated cause.
public enum Degradation {
    public static let bluetoothUnauthorized = "BLUETOOTH_UNAUTHORIZED"
    public static let bluetoothPoweredOff = "BLUETOOTH_POWERED_OFF"
    public static let locationDenied = "LOCATION_DENIED"
    public static let locationReducedAccuracy = "LOCATION_REDUCED_ACCURACY"

    /// The `Access WiFi Information` entitlement is absent, or the app is not in a state where iOS
    /// will disclose the current network.
    public static let wifiInfoUnavailable = "WIFI_INFO_UNAVAILABLE"

    /// Structural, not a fault: iOS has no Wi-Fi scanning API for third-party apps.
    public static let wifiScanUnsupported = "WIFI_SCAN_UNSUPPORTED"

    /// Structural: no public 802.11mc API on any iOS version.
    public static let rttUnsupported = "RTT_UNSUPPORTED"

    /// Unfiltered BLE scanning stops when the app leaves the foreground.
    public static let backgroundRequiresServiceFilter = "BACKGROUND_BLE_REQUIRES_SERVICE_FILTER"

    /// iOS does not expose the associated network's signal strength to a third-party app, so an
    /// association row carries a BSSID and no RSSI.
    public static let wifiRssiUnavailable = "WIFI_RSSI_UNAVAILABLE_ON_IOS"
}
