#if canImport(SQLite3)
import Foundation
import SQLite3
import RFMapperCore

/// The on-device store: SQLite, which is what Room gives the Android Collector, reached directly
/// rather than through Core Data or SwiftData.
///
/// The choice is about the data rather than about taste. RAW is append-only, the rows are flat, the
/// only queries are "one UTC day in order" and "one session", and the export path needs them in a
/// fixed order to stay byte-reproducible. An object graph with change tracking and faulting adds
/// machinery for problems this table does not have, while making the exact ordering and the
/// dedupe-on-insert harder to state than `INSERT OR IGNORE` and an `ORDER BY`.
///
/// The row is stored as its canonical JSON plus the columns worth indexing. That keeps a schema
/// migration from being required every time a minor schema version adds an optional field, which is
/// the compatibility rule the contract promises (`docs/02-observation-schema.md` §5).
public final class SqliteObservationStore: ObservationStore {

    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "com.rfmapper.collector.store")

    public init(path: String) throws {
        var db: OpaquePointer?
        guard sqlite3_open_v2(
            path,
            &db,
            SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
            nil
        ) == SQLITE_OK, let db else {
            throw StoreError.cannotOpen(path)
        }
        handle = db

        // WAL so a long capture session is not blocked by a read, and a normal-synchronous
        // tradeoff: a crash may lose the last transaction, which for append-only samples costs a
        // few observations rather than the database.
        try execute("PRAGMA journal_mode=WAL")
        try execute("PRAGMA synchronous=NORMAL")
        try migrate()
    }

    deinit {
        if let handle { sqlite3_close(handle) }
    }

    private func migrate() throws {
        try execute("""
            CREATE TABLE IF NOT EXISTS observation (
                observation_id TEXT PRIMARY KEY NOT NULL,
                timestamp_utc TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                day_start_ms INTEGER NOT NULL,
                session_id TEXT,
                sensor_type TEXT NOT NULL,
                radio_identifier TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )
            """)
        // Covers the export query exactly, including its ordering, so a day's rows come back sorted
        // without a temporary b-tree.
        try execute("""
            CREATE INDEX IF NOT EXISTS observation_day
                ON observation (day_start_ms, timestamp_utc, observation_id)
            """)
        try execute("""
            CREATE INDEX IF NOT EXISTS observation_session
                ON observation (session_id, timestamp_utc, observation_id)
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS session (
                session_id TEXT PRIMARY KEY NOT NULL,
                started_at TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )
            """)
    }

    public func append(_ observation: Observation) throws -> Bool {
        guard let millis = observation.timestampEpochMillis else {
            throw StoreError.invalidRow(observation.observationId)
        }
        return try queue.sync {
            let statement = try prepare("""
                INSERT OR IGNORE INTO observation
                    (observation_id, timestamp_utc, timestamp_ms, day_start_ms, session_id,
                     sensor_type, radio_identifier, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            defer { sqlite3_finalize(statement) }

            bind(statement, 1, observation.observationId)
            bind(statement, 2, observation.timestampUtc)
            sqlite3_bind_int64(statement, 3, millis)
            sqlite3_bind_int64(statement, 4, Iso8601.startOfUtcDay(millis))
            bind(statement, 5, observation.sessionId)
            bind(statement, 6, observation.sensorType.rawValue)
            bind(statement, 7, observation.radioIdentifier)
            bind(statement, 8, observation.compactJSON)

            guard sqlite3_step(statement) == SQLITE_DONE else {
                throw StoreError.sqlite(lastErrorMessage())
            }
            // `INSERT OR IGNORE` reports nothing when the id was already there, which is how a
            // duplicate is distinguished from an insert.
            return sqlite3_changes(handle) > 0
        }
    }

    public func observations(onUtcDay dayStartMillis: Int64) throws -> [Observation] {
        try query(
            """
            SELECT payload_json FROM observation
            WHERE day_start_ms = ?
            ORDER BY timestamp_utc, observation_id
            """,
            bind: { sqlite3_bind_int64($0, 1, dayStartMillis) }
        )
    }

    public func observations(inSession sessionId: String) throws -> [Observation] {
        try query(
            """
            SELECT payload_json FROM observation
            WHERE session_id = ?
            ORDER BY timestamp_utc, observation_id
            """,
            bind: { [weak self] statement in self?.bind(statement, 1, sessionId) }
        )
    }

    public func daysWithObservations() throws -> [Int64] {
        try queue.sync {
            let statement = try prepare(
                "SELECT DISTINCT day_start_ms FROM observation ORDER BY day_start_ms DESC"
            )
            defer { sqlite3_finalize(statement) }
            var days: [Int64] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                days.append(sqlite3_column_int64(statement, 0))
            }
            return days
        }
    }

    public func count() throws -> Int {
        try queue.sync {
            let statement = try prepare("SELECT COUNT(*) FROM observation")
            defer { sqlite3_finalize(statement) }
            guard sqlite3_step(statement) == SQLITE_ROW else { return 0 }
            return Int(sqlite3_column_int64(statement, 0))
        }
    }

    public func upsertSession(_ session: SessionSummary) throws {
        try queue.sync {
            let statement = try prepare("""
                INSERT INTO session (session_id, started_at, payload_json)
                VALUES (?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    started_at = excluded.started_at,
                    payload_json = excluded.payload_json
                """)
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, session.sessionId)
            bind(statement, 2, session.startedAt)
            bind(statement, 3, CanonicalJSON.compact(session.json))
            guard sqlite3_step(statement) == SQLITE_DONE else {
                throw StoreError.sqlite(lastErrorMessage())
            }
        }
    }

    public func sessions() throws -> [SessionSummary] {
        try queue.sync {
            let statement = try prepare(
                "SELECT payload_json FROM session ORDER BY started_at, session_id"
            )
            defer { sqlite3_finalize(statement) }
            var result: [SessionSummary] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                if let text = column(statement, 0), let session = SessionSummary(json: text) {
                    result.append(session)
                }
            }
            return result
        }
    }

    public func session(_ sessionId: String) throws -> SessionSummary? {
        try sessions().first { $0.sessionId == sessionId }
    }

    // MARK: - SQLite plumbing

    private func query(
        _ sql: String,
        bind binder: (OpaquePointer?) -> Void
    ) throws -> [Observation] {
        try queue.sync {
            let statement = try prepare(sql)
            defer { sqlite3_finalize(statement) }
            binder(statement)

            var result: [Observation] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                guard let text = column(statement, 0),
                      let observation = Observation(compactJSON: text)
                else {
                    // A row that cannot be decoded is reported rather than skipped: silently
                    // dropping it would make an export quietly incomplete.
                    throw StoreError.undecodableRow
                }
                result.append(observation)
            }
            return result
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw StoreError.sqlite(lastErrorMessage())
        }
        return statement
    }

    private func execute(_ sql: String) throws {
        guard sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK else {
            throw StoreError.sqlite(lastErrorMessage())
        }
    }

    private func bind(_ statement: OpaquePointer?, _ index: Int32, _ value: String?) {
        if let value {
            sqlite3_bind_text(statement, index, value, -1, SQLITE_TRANSIENT)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func column(_ statement: OpaquePointer?, _ index: Int32) -> String? {
        guard let pointer = sqlite3_column_text(statement, index) else { return nil }
        return String(cString: pointer)
    }

    private func lastErrorMessage() -> String {
        guard let message = sqlite3_errmsg(handle) else { return "unknown SQLite error" }
        return String(cString: message)
    }
}

// SQLite's own marker for "copy this string", which Swift does not re-export.
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public enum StoreError: Error, CustomStringConvertible {
    case cannotOpen(String)
    case sqlite(String)
    case invalidRow(String)
    case undecodableRow

    public var description: String {
        switch self {
        case .cannotOpen(let path): return "could not open the observation store at \(path)"
        case .sqlite(let message): return "SQLite: \(message)"
        case .invalidRow(let id): return "observation \(id) has an unparseable timestamp"
        case .undecodableRow: return "a stored observation could not be decoded"
        }
    }
}
#endif
