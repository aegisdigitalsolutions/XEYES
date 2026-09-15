import Foundation
import RFMapperCore

/// Builds an export package from the store and writes it where the operator can get at it.
///
/// The interesting part is the summary: the counts that go into `manifest.json` are computed here
/// from the rows the store returned, and the export engine then recomputes them from the rows it
/// actually wrote and refuses the package if the two disagree. That looks redundant and is not --
/// it is what turns a query bug into a loud failure rather than a package whose manifest quietly
/// misstates its contents.
public struct PackageExporter {

    public let store: ObservationStore
    public let observer: ObserverIdentity
    public let appVersion: String
    public var newIdentifier: () -> String
    public var now: () -> Int64

    public init(
        store: ObservationStore,
        observer: ObserverIdentity,
        appVersion: String,
        newIdentifier: @escaping () -> String,
        now: @escaping () -> Int64
    ) {
        self.store = store
        self.observer = observer
        self.appVersion = appVersion
        self.newIdentifier = newIdentifier
        self.now = now
    }

    public static let generator = Generator(name: "RFMapper iOS Collector", version: "1.0.0")

    /// A whole UTC day. The unit the Master imports and the Lab processes.
    public func exportDay(_ dayStartMillis: Int64) throws -> ExportResult {
        let observations = try store.observations(onUtcDay: dayStartMillis)
        return try write(
            observations: observations,
            kind: .day,
            dateRange: DateRange(
                from: Iso8601.format(dayStartMillis),
                to: Iso8601.format(dayStartMillis + Iso8601.millisPerDay - 1)
            ),
            sessionIdForName: nil
        )
    }

    /// One session, for handing over a single survey run without waiting for the day to end.
    public func exportSession(_ sessionId: String) throws -> ExportResult {
        let observations = try store.observations(inSession: sessionId)
        return try write(
            observations: observations,
            kind: .session,
            dateRange: nil,
            sessionIdForName: sessionId
        )
    }

    private func write(
        observations: [Observation],
        kind: ExportKind,
        dateRange: DateRange?,
        sessionIdForName: String?
    ) throws -> ExportResult {
        var counts: [String: Int64] = [:]
        var groundTruth: Int64 = 0
        var sessionIds: [String] = []
        for observation in observations {
            counts[observation.sensorType.rawValue, default: 0] += 1
            if observation.sampleKind == .groundTruth { groundTruth += 1 }
            if let sessionId = observation.sessionId, !sessionIds.contains(sessionId) {
                sessionIds.append(sessionId)
            }
        }

        let summary = ObservationSummary(
            observationCount: Int64(observations.count),
            firstObservationUtc: observations.first?.timestampUtc,
            lastObservationUtc: observations.last?.timestampUtc,
            countsBySensorType: counts,
            groundTruthCount: groundTruth,
            sessionIds: sessionIds.sorted()
        )

        // Only the sessions this package's rows actually belong to. Shipping every session the
        // device has ever run would attach context to a package that does not describe it.
        let sessions = try store.sessions().filter { sessionIds.contains($0.sessionId) }

        let request = ExportRequest(
            exportId: newIdentifier(),
            observer: observer,
            exportKind: kind,
            createdAtEpochMillis: now(),
            summary: summary,
            dateRange: dateRange,
            sessions: sessions,
            appVersion: appVersion,
            generator: Self.generator,
            sessionIdForName: sessionIdForName
        )

        return try ExportEngineV1().write(request: request, observations: observations)
    }

    /// Writes a built package into a directory, returning the file URL.
    ///
    /// Separate from building it so the bytes can be produced and checked in a test without
    /// touching a filesystem.
    public func save(_ result: ExportResult, to directory: URL) throws -> URL {
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let url = directory.appendingPathComponent(result.packageName)
        try Data(result.bytes).write(to: url, options: .atomic)
        return url
    }
}
