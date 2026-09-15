/// Identity and capability declaration for one Collector installation. Serialized as `observer.json`
/// in every export package. Mirrors `core-model/ObserverIdentity.kt`.
///
/// `capabilities` and `unsupported` are load-bearing rather than informational, and nowhere more so
/// than on iOS. An observer that cannot scan Wi-Fi and reports no Wi-Fi observations means *"this
/// observer cannot see Wi-Fi"*, not *"no access points were present"*. Without the distinction an
/// iOS observer would silently drive every fingerprint's Wi-Fi visibility probability toward zero --
/// absence of evidence misread as evidence of absence. See `docs/06-ios-capability-matrix.md` §6.2.
public struct ObserverIdentity: Equatable, Sendable {

    public var schemaVersion: String
    public var observerId: String
    public var friendlyName: String
    public var observerDeviceType: ObserverDeviceType
    public var buildingId: String?
    public var defaultZoneId: String?
    public var deviceModel: String?
    public var manufacturer: String?
    public var platform: Platform
    public var osVersion: String?
    public var appVersion: String
    public var installationId: String?
    public var capabilities: Set<ObserverCapability>
    public var unsupported: Set<ObserverCapability>
    public var xCoordinate: Double?
    public var yCoordinate: Double?

    /// True when the coordinates are a trusted measured location. A fixed observer's samples become
    /// reference-quality evidence in multi-observer fusion, so this must not be set for a phone that
    /// merely happens to be sitting somewhere.
    public var fixedObserver: Bool
    public var notes: String?

    public init(
        schemaVersion: String = SchemaVersion.current,
        observerId: String,
        friendlyName: String,
        observerDeviceType: ObserverDeviceType,
        buildingId: String? = nil,
        defaultZoneId: String? = nil,
        deviceModel: String? = nil,
        manufacturer: String? = nil,
        platform: Platform,
        osVersion: String? = nil,
        appVersion: String,
        installationId: String? = nil,
        capabilities: Set<ObserverCapability> = [],
        unsupported: Set<ObserverCapability> = [],
        xCoordinate: Double? = nil,
        yCoordinate: Double? = nil,
        fixedObserver: Bool = false,
        notes: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.observerId = observerId
        self.friendlyName = friendlyName
        self.observerDeviceType = observerDeviceType
        self.buildingId = buildingId
        self.defaultZoneId = defaultZoneId
        self.deviceModel = deviceModel
        self.manufacturer = manufacturer
        self.platform = platform
        self.osVersion = osVersion
        self.appVersion = appVersion
        self.installationId = installationId
        self.capabilities = capabilities
        self.unsupported = unsupported
        self.xCoordinate = xCoordinate
        self.yCoordinate = yCoordinate
        self.fixedObserver = fixedObserver
        self.notes = notes
    }

    public func validate() throws {
        var reasons: [String] = []
        if observerId.trimmingASCIIWhitespace().isEmpty {
            reasons.append("observer_id must not be blank")
        }
        if observerId.count > ObservationInvariants.maxIdLength {
            reasons.append("observer_id exceeds \(ObservationInvariants.maxIdLength) characters")
        }
        if !capabilities.isDisjoint(with: unsupported) {
            reasons.append("a capability cannot be both supported and unsupported")
        }
        if fixedObserver && (xCoordinate == nil || yCoordinate == nil || buildingId == nil) {
            reasons.append("a fixed observer requires building_id and x/y coordinates")
        }
        guard reasons.isEmpty else {
            throw ObservationError.invalid(id: observerId, reasons: reasons)
        }
    }

    /// Filename-safe form used in export package names: `OBS-04` -> `OBS04`.
    public var fileSafeId: String {
        String(observerId.filter { $0.isLetter || $0.isNumber })
    }

    /// Declaration order matches the Kotlin data class, because the bytes are part of the contract.
    public var json: JSONValue {
        .object([
            ("schema_version", .string(schemaVersion)),
            ("observer_id", .string(observerId)),
            ("friendly_name", .string(friendlyName)),
            ("observer_device_type", .string(observerDeviceType.rawValue)),
            ("building_id", .string(buildingId)),
            ("default_zone_id", .string(defaultZoneId)),
            ("device_model", .string(deviceModel)),
            ("manufacturer", .string(manufacturer)),
            ("platform", .string(platform.rawValue)),
            ("os_version", .string(osVersion)),
            ("app_version", .string(appVersion)),
            ("installation_id", .string(installationId)),
            // kotlinx writes a Set in iteration order. Sorting makes the bytes reproducible, which
            // matters more than matching an order the Kotlin side does not itself guarantee.
            ("capabilities", .array(capabilities.map(\.rawValue).sorted().map { .string($0) })),
            ("unsupported", .array(unsupported.map(\.rawValue).sorted().map { .string($0) })),
            ("x_coordinate", .double(xCoordinate)),
            ("y_coordinate", .double(yCoordinate)),
            ("fixed_observer", .bool(fixedObserver)),
            ("notes", .string(notes)),
        ])
    }
}

/// Per-session context recorded alongside a package.
///
/// `throttledScanRequests`, `droppedSamples` and `suspectedServiceKill` turn an unexplained coverage
/// gap into a known one. Recording that data was lost is far more valuable than a clean-looking file
/// that quietly omits it -- and on iOS, where the system throttles and suspends far more
/// aggressively than Android, these are the fields that will actually get used.
public struct SessionSummary: Equatable, Sendable {

    public var sessionId: String
    public var startedAt: String
    public var endedAt: String?
    public var scanProfile: String
    public var buildingId: String?
    public var zoneId: String?
    public var observationCount: Int64
    public var wifiCount: Int64
    public var bleCount: Int64
    public var rttCount: Int64
    public var gpsCount: Int64
    public var droppedSamples: Int64
    public var throttledScanRequests: Int64
    public var backgroundDenied: Bool
    public var suspectedServiceKill: Bool
    public var batteryStartPct: Int?
    public var batteryEndPct: Int?
    public var degradations: [String]

    public init(
        sessionId: String,
        startedAt: String,
        endedAt: String? = nil,
        scanProfile: String,
        buildingId: String? = nil,
        zoneId: String? = nil,
        observationCount: Int64 = 0,
        wifiCount: Int64 = 0,
        bleCount: Int64 = 0,
        rttCount: Int64 = 0,
        gpsCount: Int64 = 0,
        droppedSamples: Int64 = 0,
        throttledScanRequests: Int64 = 0,
        backgroundDenied: Bool = false,
        suspectedServiceKill: Bool = false,
        batteryStartPct: Int? = nil,
        batteryEndPct: Int? = nil,
        degradations: [String] = []
    ) {
        self.sessionId = sessionId
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.scanProfile = scanProfile
        self.buildingId = buildingId
        self.zoneId = zoneId
        self.observationCount = observationCount
        self.wifiCount = wifiCount
        self.bleCount = bleCount
        self.rttCount = rttCount
        self.gpsCount = gpsCount
        self.droppedSamples = droppedSamples
        self.throttledScanRequests = throttledScanRequests
        self.backgroundDenied = backgroundDenied
        self.suspectedServiceKill = suspectedServiceKill
        self.batteryStartPct = batteryStartPct
        self.batteryEndPct = batteryEndPct
        self.degradations = degradations
    }

    public var json: JSONValue {
        .object([
            ("session_id", .string(sessionId)),
            ("started_at", .string(startedAt)),
            ("ended_at", .string(endedAt)),
            ("scan_profile", .string(scanProfile)),
            ("building_id", .string(buildingId)),
            ("zone_id", .string(zoneId)),
            ("observation_count", .int(observationCount)),
            ("wifi_count", .int(wifiCount)),
            ("ble_count", .int(bleCount)),
            ("rtt_count", .int(rttCount)),
            ("gps_count", .int(gpsCount)),
            ("dropped_samples", .int(droppedSamples)),
            ("throttled_scan_requests", .int(throttledScanRequests)),
            ("background_denied", .bool(backgroundDenied)),
            ("suspected_service_kill", .bool(suspectedServiceKill)),
            ("battery_start_pct", .int(batteryStartPct)),
            ("battery_end_pct", .int(batteryEndPct)),
            ("degradations", .array(degradations.map { .string($0) })),
        ])
    }
}
