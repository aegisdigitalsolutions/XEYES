/// `manifest.json` for an observation package. Mirrors `core-model/ExportManifest.kt`.
///
/// `countsBySensorType` and `observationCount` let the Master's import preview summarise a package
/// without parsing 18,000 rows, and let a mismatch against the actual content be detected as
/// `MANIFEST_COUNT_MISMATCH` rather than silently accepted.
public struct ExportManifest: Equatable, Sendable {

    public var schemaVersion: String
    public var packageType: PackageType
    public var exportId: String
    public var observerId: String
    public var createdAt: String
    public var exportKind: ExportKind
    public var dateRange: DateRange?
    public var observationCount: Int64
    public var firstObservation: String?
    public var lastObservation: String?
    public var appVersion: String
    public var platform: String?
    public var osVersion: String?
    public var deviceModel: String?
    public var installationId: String?
    public var sessionIds: [String]
    public var countsBySensorType: [String: Int64]
    public var groundTruthCount: Int64
    public var generator: Generator

    public init(
        schemaVersion: String = SchemaVersion.current,
        packageType: PackageType = .observations,
        exportId: String,
        observerId: String,
        createdAt: String,
        exportKind: ExportKind,
        dateRange: DateRange? = nil,
        observationCount: Int64,
        firstObservation: String? = nil,
        lastObservation: String? = nil,
        appVersion: String,
        platform: String? = nil,
        osVersion: String? = nil,
        deviceModel: String? = nil,
        installationId: String? = nil,
        sessionIds: [String] = [],
        countsBySensorType: [String: Int64] = [:],
        groundTruthCount: Int64 = 0,
        generator: Generator
    ) {
        self.schemaVersion = schemaVersion
        self.packageType = packageType
        self.exportId = exportId
        self.observerId = observerId
        self.createdAt = createdAt
        self.exportKind = exportKind
        self.dateRange = dateRange
        self.observationCount = observationCount
        self.firstObservation = firstObservation
        self.lastObservation = lastObservation
        self.appVersion = appVersion
        self.platform = platform
        self.osVersion = osVersion
        self.deviceModel = deviceModel
        self.installationId = installationId
        self.sessionIds = sessionIds
        self.countsBySensorType = countsBySensorType
        self.groundTruthCount = groundTruthCount
        self.generator = generator
    }

    public func validate() throws {
        var reasons: [String] = []
        if observerId.trimmingASCIIWhitespace().isEmpty {
            reasons.append("observer_id must not be blank")
        }
        if observationCount < 0 {
            reasons.append("observation_count must not be negative")
        }
        if observationCount > 0 && (firstObservation == nil || lastObservation == nil) {
            reasons.append("a non-empty package must declare first_observation and last_observation")
        }
        guard reasons.isEmpty else {
            throw ObservationError.invalid(id: exportId, reasons: reasons)
        }
    }

    /// Declaration order matches the Kotlin data class, because the bytes are part of the contract.
    public var json: JSONValue {
        .object([
            ("schema_version", .string(schemaVersion)),
            ("package_type", .string(packageType.rawValue)),
            ("export_id", .string(exportId)),
            ("observer_id", .string(observerId)),
            ("created_at", .string(createdAt)),
            ("export_kind", .string(exportKind.rawValue)),
            ("date_range", dateRange?.json ?? .null),
            ("observation_count", .int(observationCount)),
            ("first_observation", .string(firstObservation)),
            ("last_observation", .string(lastObservation)),
            ("app_version", .string(appVersion)),
            ("platform", .string(platform)),
            ("os_version", .string(osVersion)),
            ("device_model", .string(deviceModel)),
            ("installation_id", .string(installationId)),
            ("session_ids", .array(sessionIds.map { .string($0) })),
            ("counts_by_sensor_type", .sortedCounts(countsBySensorType)),
            ("ground_truth_count", .int(groundTruthCount)),
            ("generator", generator.json),
        ])
    }
}

public struct DateRange: Equatable, Sendable {
    public var from: String
    public var to: String

    public init(from: String, to: String) {
        self.from = from
        self.to = to
    }

    public var json: JSONValue {
        .object([("from", .string(from)), ("to", .string(to))])
    }
}

public struct Generator: Equatable, Sendable {
    public var name: String
    public var version: String

    public init(name: String, version: String) {
        self.name = name
        self.version = version
    }

    public var json: JSONValue {
        .object([("name", .string(name)), ("version", .string(version))])
    }
}
