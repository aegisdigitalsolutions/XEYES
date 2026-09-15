import Foundation
import RFMapperCore

/// Local persistence for the RAW layer.
///
/// Expressed as a protocol with two implementations so that the parts worth testing -- ordering,
/// deduplication, session accounting, the export query -- can be tested on a build machine against
/// ``InMemoryObservationStore``, while the device uses ``SqliteObservationStore``. The alternative,
/// one SQLite-only type, would make every test of collection logic require a simulator.
public protocol ObservationStore: AnyObject {

    /// Appends a row. RAW is append-only: there is no update and no delete.
    ///
    /// Returns false when the row was already present. Deduplication is on `observation_id`, which
    /// is why the schema insists that column be a canonically-formatted UUID -- two spellings of one
    /// id would both be stored and the duplicate would survive into an export.
    func append(_ observation: Observation) throws -> Bool

    /// Rows for one UTC day, in the `(timestamp_utc, observation_id)` order the export engine
    /// requires. Sorting at the query, not at the export, keeps the ordering guarantee in one place.
    func observations(onUtcDay dayStartMillis: Int64) throws -> [Observation]

    func observations(inSession sessionId: String) throws -> [Observation]

    /// UTC day starts that hold at least one row, most recent first.
    func daysWithObservations() throws -> [Int64]

    func count() throws -> Int

    func upsertSession(_ session: SessionSummary) throws
    func sessions() throws -> [SessionSummary]
    func session(_ sessionId: String) throws -> SessionSummary?
}

/// The reference implementation, and the one the tests use.
public final class InMemoryObservationStore: ObservationStore {

    private var rows: [String: Observation] = [:]
    private var sessionsById: [String: SessionSummary] = [:]
    private let lock = NSLock()

    public init() {}

    public func append(_ observation: Observation) throws -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if rows[observation.observationId] != nil { return false }
        rows[observation.observationId] = observation
        return true
    }

    public func observations(onUtcDay dayStartMillis: Int64) throws -> [Observation] {
        let end = dayStartMillis + Iso8601.millisPerDay
        return ordered(rows.values.filter { observation in
            guard let millis = observation.timestampEpochMillis else { return false }
            return millis >= dayStartMillis && millis < end
        })
    }

    public func observations(inSession sessionId: String) throws -> [Observation] {
        ordered(rows.values.filter { $0.sessionId == sessionId })
    }

    public func daysWithObservations() throws -> [Int64] {
        let days = rows.values.compactMap { observation -> Int64? in
            observation.timestampEpochMillis.map(Iso8601.startOfUtcDay)
        }
        return Array(Set(days)).sorted(by: >)
    }

    public func count() throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        return rows.count
    }

    public func upsertSession(_ session: SessionSummary) throws {
        lock.lock()
        defer { lock.unlock() }
        sessionsById[session.sessionId] = session
    }

    public func sessions() throws -> [SessionSummary] {
        lock.lock()
        defer { lock.unlock() }
        return sessionsById.values.sorted { $0.startedAt < $1.startedAt }
    }

    public func session(_ sessionId: String) throws -> SessionSummary? {
        lock.lock()
        defer { lock.unlock() }
        return sessionsById[sessionId]
    }

    private func ordered<S: Sequence>(_ values: S) -> [Observation] where S.Element == Observation {
        values.sorted { ($0.timestampUtc, $0.observationId) < ($1.timestampUtc, $1.observationId) }
    }
}
