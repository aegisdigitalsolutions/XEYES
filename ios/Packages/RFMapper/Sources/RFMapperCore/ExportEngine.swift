/// Aggregate facts about the rows an export will contain, obtained before writing begins.
///
/// The manifest has to be written before the rows (it is the first entry in the archive), so the
/// counts cannot come from the rows themselves. Precomputing them has a useful side effect: the
/// writer compares them against what it actually wrote and fails on disagreement, which turns a
/// database aggregate bug into a loud error rather than a manifest that quietly lies.
public struct ObservationSummary: Equatable, Sendable {

    public var observationCount: Int64
    public var firstObservationUtc: String?
    public var lastObservationUtc: String?
    public var countsBySensorType: [String: Int64]
    public var groundTruthCount: Int64
    public var sessionIds: [String]

    public init(
        observationCount: Int64,
        firstObservationUtc: String? = nil,
        lastObservationUtc: String? = nil,
        countsBySensorType: [String: Int64] = [:],
        groundTruthCount: Int64 = 0,
        sessionIds: [String] = []
    ) {
        self.observationCount = observationCount
        self.firstObservationUtc = firstObservationUtc
        self.lastObservationUtc = lastObservationUtc
        self.countsBySensorType = countsBySensorType
        self.groundTruthCount = groundTruthCount
        self.sessionIds = sessionIds
    }

    public func validate() throws {
        if observationCount < 0 {
            throw ExportError.invalidRequest("observationCount must not be negative")
        }
        if observationCount > 0 && (firstObservationUtc == nil || lastObservationUtc == nil) {
            throw ExportError.invalidRequest(
                "a non-empty export must know its first and last observation timestamps"
            )
        }
    }
}

public struct ExportRequest {
    public var exportId: String
    public var observer: ObserverIdentity
    public var exportKind: ExportKind
    public var createdAtEpochMillis: Int64
    public var summary: ObservationSummary
    public var dateRange: DateRange?
    public var sessions: [SessionSummary]
    public var appVersion: String
    public var generator: Generator

    /// Included in the package file name for a session export.
    public var sessionIdForName: String?

    public init(
        exportId: String,
        observer: ObserverIdentity,
        exportKind: ExportKind,
        createdAtEpochMillis: Int64,
        summary: ObservationSummary,
        dateRange: DateRange? = nil,
        sessions: [SessionSummary] = [],
        appVersion: String,
        generator: Generator,
        sessionIdForName: String? = nil
    ) {
        self.exportId = exportId
        self.observer = observer
        self.exportKind = exportKind
        self.createdAtEpochMillis = createdAtEpochMillis
        self.summary = summary
        self.dateRange = dateRange
        self.sessions = sessions
        self.appVersion = appVersion
        self.generator = generator
        self.sessionIdForName = sessionIdForName
    }
}

public struct ExportResult {
    public let packageName: String
    public let bytes: [UInt8]
    public let observationsWritten: Int64
    public let entryDigests: [String: String]
    public let bytesPerEntry: [String: Int]

    /// The digest of the whole archive, which is how the Master keys a re-import.
    public var packageSha256: String {
        Sha256.hex(bytes)
    }
}

public enum ExportError: Error, CustomStringConvertible {
    case invalidRequest(String)
    case unorderedRows(previous: String, next: String)
    case manifestDisagreement(String)

    public var description: String {
        switch self {
        case .invalidRequest(let detail):
            return detail
        case .unorderedRows(let previous, let next):
            return "observations must be ordered by (timestamp_utc, observation_id): "
                + "\(previous) preceded \(next)"
        case .manifestDisagreement(let detail):
            return detail
        }
    }
}

public enum ExportPackage {
    public static let manifest = "manifest.json"
    public static let observationsCsv = "observations.csv"
    public static let observationsJson = "observations.json"
    public static let observer = "observer.json"
    public static let sessions = "sessions.json"
    public static let checksum = "checksum.txt"

    public static let requiredEntries = [manifest, observationsCsv, observationsJson, observer, checksum]

    /// `RFMapper_OBS04_2026-09-14.zip`, or `RFMapper_OBS04_2026-09-14_0d6b1f4a.zip` for a session
    /// export.
    public static func fileName(observerId: String, dateStamp: String, sessionId: String? = nil) -> String {
        let safeObserver = String(observerId.filter { $0.isLetter || $0.isNumber })
        let suffix = sessionId.map { "_" + String($0.prefix(8)) } ?? ""
        return "RFMapper_\(safeObserver)_\(dateStamp)\(suffix).zip"
    }

    public static func manifest(for request: ExportRequest) -> ExportManifest {
        ExportManifest(
            exportId: request.exportId,
            observerId: request.observer.observerId,
            createdAt: Iso8601.format(request.createdAtEpochMillis),
            exportKind: request.exportKind,
            dateRange: request.dateRange,
            observationCount: request.summary.observationCount,
            firstObservation: request.summary.firstObservationUtc,
            lastObservation: request.summary.lastObservationUtc,
            appVersion: request.appVersion,
            platform: request.observer.platform.rawValue,
            osVersion: request.observer.osVersion,
            deviceModel: request.observer.deviceModel,
            installationId: request.observer.installationId,
            sessionIds: request.summary.sessionIds,
            countsBySensorType: request.summary.countsBySensorType,
            groundTruthCount: request.summary.groundTruthCount,
            generator: request.generator
        )
    }
}
