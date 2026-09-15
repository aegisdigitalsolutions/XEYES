/// One canonical radio observation: a single statement of the form
/// *"observer OBS-04, standing at B7_CENTER, saw identifier X at -61 dBm at this instant"*.
///
/// This is the immutable RAW record. Nothing downstream edits it.
///
/// **`buildingId`, `zoneId`, `xCoordinate`, `yCoordinate`, `latitude` and `longitude` describe where
/// the OBSERVER was, never where the observed device is.** Any claim about a target's location lives
/// exclusively in the DERIVED layer. Confusing the two would silently turn a measurement into an
/// unvalidated location claim.
///
/// Mirrors `core-model/Observation.kt`. The Kotlin type enforces its invariants in `init` so an
/// invalid instance cannot exist; Swift structs cannot fail a memberwise initialiser, so the
/// equivalent guarantee is provided by ``Observation/validated(...)``, which is the only initialiser
/// the capture path uses.
///
/// See `docs/02-observation-schema.md`.
public struct Observation: Equatable, Sendable {

    public var observationId: String
    public var schemaVersion: String
    public var timestampUtc: String
    public var observerId: String
    public var observerDeviceType: ObserverDeviceType
    public var sensorType: SensorType

    /// The managed device this observation is attributed to, or nil. Set only by an explicit
    /// enrollment match or administrator rule -- never inferred from a randomized identifier.
    public var targetDeviceId: String?

    /// Normalized: see ``RadioIdentifierNormalizer``. Deduplication and fingerprinting depend on it.
    public var radioIdentifier: String
    public var identifierType: IdentifierType

    public var ssid: String?
    public var bssid: String?
    public var bleServiceUuid: String?
    public var manufacturerData: String?

    /// Raw, uncalibrated dBm as reported by the radio. Normalization happens only in DERIVED.
    public var rssi: Int?
    public var txPower: Int?
    public var frequency: Int?
    public var channel: Int?

    /// Genuine Wi-Fi RTT range. Never back-filled from an RSSI model, and unreachable on iOS.
    public var rttDistanceMm: Int?
    public var rttStddevMm: Int?

    public var latitude: Double?
    public var longitude: Double?
    public var horizontalAccuracy: Double?

    public var buildingId: String?
    public var zoneId: String?
    public var xCoordinate: Double?
    public var yCoordinate: Double?

    /// Collection-time measurement quality in [0,1]: how much this single sample should be trusted,
    /// given freshness, scan completeness and permission degradation. **Not** a position confidence.
    public var confidence: Double?

    public var metadata: [String: String]

    public init(
        observationId: String,
        schemaVersion: String = SchemaVersion.current,
        timestampUtc: String,
        observerId: String,
        observerDeviceType: ObserverDeviceType,
        sensorType: SensorType,
        targetDeviceId: String? = nil,
        radioIdentifier: String,
        identifierType: IdentifierType,
        ssid: String? = nil,
        bssid: String? = nil,
        bleServiceUuid: String? = nil,
        manufacturerData: String? = nil,
        rssi: Int? = nil,
        txPower: Int? = nil,
        frequency: Int? = nil,
        channel: Int? = nil,
        rttDistanceMm: Int? = nil,
        rttStddevMm: Int? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        horizontalAccuracy: Double? = nil,
        buildingId: String? = nil,
        zoneId: String? = nil,
        xCoordinate: Double? = nil,
        yCoordinate: Double? = nil,
        confidence: Double? = nil,
        metadata: [String: String] = [:]
    ) {
        self.observationId = observationId
        self.schemaVersion = schemaVersion
        self.timestampUtc = timestampUtc
        self.observerId = observerId
        self.observerDeviceType = observerDeviceType
        self.sensorType = sensorType
        self.targetDeviceId = targetDeviceId
        self.radioIdentifier = radioIdentifier
        self.identifierType = identifierType
        self.ssid = ssid
        self.bssid = bssid
        self.bleServiceUuid = bleServiceUuid
        self.manufacturerData = manufacturerData
        self.rssi = rssi
        self.txPower = txPower
        self.frequency = frequency
        self.channel = channel
        self.rttDistanceMm = rttDistanceMm
        self.rttStddevMm = rttStddevMm
        self.latitude = latitude
        self.longitude = longitude
        self.horizontalAccuracy = horizontalAccuracy
        self.buildingId = buildingId
        self.zoneId = zoneId
        self.xCoordinate = xCoordinate
        self.yCoordinate = yCoordinate
        self.confidence = confidence
        self.metadata = metadata
    }

    /// The invariant-checked construction path. Throws rather than returning a partially valid row,
    /// so an invalid observation never reaches the database or an export.
    public static func validated(_ observation: Observation) throws -> Observation {
        let violations = ObservationInvariants.check(observation)
        guard violations.isEmpty else {
            throw ObservationError.invalid(id: observation.observationId, reasons: violations)
        }
        return observation
    }

    public var timestampEpochMillis: Int64? {
        Iso8601.parseToEpochMillis(timestampUtc)
    }

    public var sampleKind: SampleKind {
        SampleKind.fromWireOrDefault(metadata[MetadataKeys.sampleKind])
    }

    public var sessionId: String? {
        metadata[MetadataKeys.sessionId]
    }

    public var resultFreshness: ResultFreshness {
        switch metadata[MetadataKeys.resultFreshness] {
        case ResultFreshness.fresh.rawValue: return .fresh
        case ResultFreshness.cached.rawValue: return .cached
        default: return .unknown
        }
    }

    /// True when a permission or hardware limitation means fields are missing from this record.
    public var isPermissionDegraded: Bool {
        metadata[MetadataKeys.permissionDegraded] == "true"
    }
}

public enum ObservationError: Error, CustomStringConvertible {
    case invalid(id: String, reasons: [String])

    public var description: String {
        switch self {
        case .invalid(let id, let reasons):
            return "Invalid Observation \(id): \(reasons.joined(separator: "; "))"
        }
    }
}

/// The cross-field invariants from `docs/02-observation-schema.md` §1, in one place so that the
/// construction path, the CSV reader and the export writer cannot drift apart.
///
/// Returns human-readable violations rather than throwing, so an importer can report a bad row and
/// continue with the rest of a package. The order and wording of the messages deliberately track
/// the Kotlin implementation, since both appear in operator-facing import reports.
public enum ObservationInvariants {

    public static let maxIdLength = 64
    public static let maxSsidLength = 64

    public static func check(_ o: Observation) -> [String] {
        var issues: [String] = []

        if !isLowercaseUuid(o.observationId) {
            // Rejected rather than lower-cased: coercion would let two encodings of one id coexist,
            // which would defeat deduplication.
            issues.append("observation_id must be a lowercase hyphenated UUID")
        }
        if SchemaVersion.parse(o.schemaVersion) == nil {
            issues.append("schema_version must be semantic (got '\(o.schemaVersion)')")
        }
        if !Iso8601.isValid(o.timestampUtc) {
            issues.append("timestamp_utc must be ISO-8601 UTC with milliseconds and a literal Z")
        }
        if o.observerId.trimmingASCIIWhitespace().isEmpty {
            issues.append("observer_id must not be blank")
        }
        if o.observerId.count > maxIdLength {
            issues.append("observer_id exceeds \(maxIdLength) chars")
        }
        if o.radioIdentifier.trimmingASCIIWhitespace().isEmpty {
            issues.append("radio_identifier must not be blank")
        }

        if let bssid = o.bssid, !isLowercaseMac(bssid) {
            issues.append("bssid must be lowercase colon-separated")
        }
        if let data = o.manufacturerData {
            if data.isEmpty {
                issues.append("manufacturer_data must be null rather than empty")
            } else if !data.allSatisfy({ $0.isUppercaseHexDigit }) {
                issues.append("manufacturer_data must be uppercase hex")
            }
        }
        if let ssid = o.ssid, ssid.count > maxSsidLength {
            issues.append("ssid exceeds \(maxSsidLength) chars")
        }

        if let rssi = o.rssi, !(-127...20).contains(rssi) {
            issues.append("rssi \(rssi) outside [-127,20] dBm")
        }
        if let txPower = o.txPower, !(-127...127).contains(txPower) {
            issues.append("tx_power \(txPower) outside [-127,127] dBm")
        }
        if let frequency = o.frequency, !(400...80_000).contains(frequency) {
            issues.append("frequency \(frequency) MHz implausible")
        }
        if let channel = o.channel, !(0...255).contains(channel) {
            issues.append("channel \(channel) outside [0,255]")
        }
        if let stddev = o.rttStddevMm, stddev < 0 {
            issues.append("rtt_stddev_mm must not be negative")
        }

        // Invariant 1: RTT ranges only ever appear on RTT records. Prevents an RSSI-derived distance
        // from ever masquerading as a genuine range measurement.
        if (o.rttDistanceMm != nil || o.rttStddevMm != nil) && o.sensorType != .rtt {
            issues.append("rtt_* fields require sensor_type=RTT (got \(o.sensorType.rawValue))")
        }

        // Invariant 2: a GNSS fix must carry both coordinates.
        if o.sensorType == .gps && (o.latitude == nil || o.longitude == nil) {
            issues.append("sensor_type=GPS requires both latitude and longitude")
        }
        if let latitude = o.latitude, !(-90.0...90.0).contains(latitude) {
            issues.append("latitude \(latitude) out of range")
        }
        if let longitude = o.longitude, !(-180.0...180.0).contains(longitude) {
            issues.append("longitude \(longitude) out of range")
        }
        if let accuracy = o.horizontalAccuracy, accuracy < 0 {
            issues.append("horizontal_accuracy must not be negative")
        }

        // Invariant 3: site-local coordinates are meaningless without a building.
        if (o.xCoordinate != nil || o.yCoordinate != nil) && o.buildingId == nil {
            issues.append("x/y_coordinate require building_id")
        }
        if (o.xCoordinate == nil) != (o.yCoordinate == nil) {
            issues.append("x_coordinate and y_coordinate must both be present or both absent")
        }

        // Invariant 4: confidence is a probability.
        if let confidence = o.confidence, !(0.0...1.0).contains(confidence) {
            issues.append("confidence \(confidence) outside [0,1]")
        }

        let numbers = [o.latitude, o.longitude, o.horizontalAccuracy, o.xCoordinate, o.yCoordinate, o.confidence]
        if numbers.compactMap({ $0 }).contains(where: { $0.isNaN || $0.isInfinite }) {
            issues.append("numeric fields must be finite")
        }

        return issues
    }

    static func isLowercaseUuid(_ text: String) -> Bool {
        let groups = text.split(separator: "-", omittingEmptySubsequences: false)
        guard groups.count == 5 else { return false }
        let widths = [8, 4, 4, 4, 12]
        for (group, width) in zip(groups, widths) {
            guard group.count == width, group.allSatisfy({ $0.isLowercaseHexDigit }) else {
                return false
            }
        }
        return true
    }

    static func isLowercaseMac(_ text: String) -> Bool {
        let groups = text.split(separator: ":", omittingEmptySubsequences: false)
        guard groups.count == 6 else { return false }
        return groups.allSatisfy { $0.count == 2 && $0.allSatisfy(\.isLowercaseHexDigit) }
    }
}

extension Character {
    var isLowercaseHexDigit: Bool {
        ("0"..."9").contains(self) || ("a"..."f").contains(self)
    }

    var isUppercaseHexDigit: Bool {
        ("0"..."9").contains(self) || ("A"..."F").contains(self)
    }
}
