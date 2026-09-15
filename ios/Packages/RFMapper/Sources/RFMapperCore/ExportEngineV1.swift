/// Writes the V1 observation package described in `docs/16-export-package-specification.md`, byte
/// for byte as the Android Collector writes it.
///
/// One deliberate divergence from the Kotlin engine: that one streams, holding peak memory
/// independent of package size, and makes two passes over the row source specifically so neither
/// the CSV nor the JSON view has to be buffered. This one takes an array.
///
/// The reason is not laziness about memory but honesty about the platform. An iOS Collector cannot
/// run unattended for a day -- unfiltered BLE scanning is a foreground, screen-on activity, and
/// there is no equivalent of Android's foreground service (`docs/06-ios-capability-matrix.md` §4).
/// Its packages are supervised-session sized, thousands of rows rather than hundreds of thousands.
/// Streaming machinery sized for a volume the platform cannot produce would be speculative
/// complexity, and the two-pass cross-check it exists to enable is vacuous when the rows are already
/// a value that cannot change between passes.
public struct ExportEngineV1 {

    public static let version = "export_engine-1.0.0"

    public init() {}

    public func write(request: ExportRequest, observations: [Observation]) throws -> ExportResult {
        try request.summary.validate()
        try request.observer.validate()

        let manifest = ExportPackage.manifest(for: request)
        try manifest.validate()

        try verifyOrdering(observations)

        var writer = ZipWriter()
        var digests: [String: String] = [:]
        var sizes: [String: Int] = [:]

        func writeEntry(_ name: String, _ text: String) {
            let bytes = Array(text.utf8)
            writer.addEntry(name: name, bytes: bytes)
            digests[name] = Sha256.hex(bytes)
            sizes[name] = bytes.count
        }

        writeEntry(ExportPackage.manifest, CanonicalJSON.pretty(manifest.json) + "\n")

        var csv = ObservationCsvCodec.header
        csv += "\n"
        for observation in observations {
            csv += ObservationCsvCodec.encode(observation)
            csv += "\n"
        }
        writeEntry(ExportPackage.observationsCsv, csv)

        // The array layout the Kotlin engine produces: an opening bracket, then each row on its own
        // line as compact JSON, separated by commas. Not a pretty-printed array -- the rows are
        // compact so that a package stays greppable one observation at a time.
        var json = "["
        for (index, observation) in observations.enumerated() {
            if index > 0 { json += "," }
            json += "\n"
            json += observation.compactJSON
        }
        if !observations.isEmpty { json += "\n" }
        json += "]\n"
        writeEntry(ExportPackage.observationsJson, json)

        writeEntry(ExportPackage.observer, CanonicalJSON.pretty(request.observer.json) + "\n")

        if !request.sessions.isEmpty {
            let array = JSONValue.array(request.sessions.map(\.json))
            writeEntry(ExportPackage.sessions, CanonicalJSON.pretty(array) + "\n")
        }

        let statistics = Statistics(observations)
        try verify(manifest: manifest, against: statistics)

        // Written last, so its presence means every other entry completed.
        writeEntry(ExportPackage.checksum, Sha256.checksumFile(digests))

        return ExportResult(
            packageName: ExportPackage.fileName(
                observerId: request.observer.observerId,
                dateStamp: dateStamp(for: request),
                sessionId: request.exportKind == .session ? request.sessionIdForName : nil
            ),
            bytes: writer.finish(),
            observationsWritten: statistics.count,
            entryDigests: digests,
            bytesPerEntry: sizes
        )
    }

    private func verifyOrdering(_ observations: [Observation]) throws {
        var previous: (timestamp: String, id: String)?
        for observation in observations {
            if let previous {
                let ordered = (previous.timestamp, previous.id)
                    <= (observation.timestampUtc, observation.observationId)
                if !ordered {
                    throw ExportError.unorderedRows(
                        previous: previous.id,
                        next: observation.observationId
                    )
                }
            }
            previous = (observation.timestampUtc, observation.observationId)
        }
    }

    private func dateStamp(for request: ExportRequest) -> String {
        if let from = request.dateRange?.from {
            return String(from.prefix(10))
        }
        if let first = request.summary.firstObservationUtc {
            return String(first.prefix(10))
        }
        return Iso8601.utcDateStamp(request.createdAtEpochMillis)
    }

    /// Checks the precomputed manifest against what was actually written. A disagreement means the
    /// aggregates and the rows disagree, which would otherwise ship as a package whose manifest
    /// quietly misstates its contents.
    private func verify(manifest: ExportManifest, against observed: Statistics) throws {
        if manifest.observationCount != observed.count {
            throw ExportError.manifestDisagreement(
                "manifest declares \(manifest.observationCount) observations but "
                    + "\(observed.count) were written"
            )
        }
        if observed.count > 0 {
            if manifest.firstObservation != observed.firstTimestamp {
                throw ExportError.manifestDisagreement(
                    "manifest first_observation \(manifest.firstObservation ?? "nil") != "
                        + "written \(observed.firstTimestamp ?? "nil")"
                )
            }
            if manifest.lastObservation != observed.lastTimestamp {
                throw ExportError.manifestDisagreement(
                    "manifest last_observation \(manifest.lastObservation ?? "nil") != "
                        + "written \(observed.lastTimestamp ?? "nil")"
                )
            }
        }
        if !manifest.countsBySensorType.isEmpty,
           manifest.countsBySensorType != observed.countsBySensorType {
            throw ExportError.manifestDisagreement(
                "manifest counts_by_sensor_type \(manifest.countsBySensorType) != "
                    + "written \(observed.countsBySensorType)"
            )
        }
        if manifest.groundTruthCount != observed.groundTruthCount {
            throw ExportError.manifestDisagreement(
                "manifest ground_truth_count \(manifest.groundTruthCount) != "
                    + "written \(observed.groundTruthCount)"
            )
        }
        if let stray = observed.observerIds.sorted().first(where: { $0 != manifest.observerId }) {
            throw ExportError.manifestDisagreement(
                "package declares observer \(manifest.observerId) but contains a row from \(stray)"
            )
        }
    }

    private struct Statistics {
        var count: Int64 = 0
        var firstTimestamp: String?
        var lastTimestamp: String?
        var groundTruthCount: Int64 = 0
        var countsBySensorType: [String: Int64] = [:]
        var observerIds: Set<String> = []

        init(_ observations: [Observation]) {
            for observation in observations {
                count += 1
                if firstTimestamp == nil { firstTimestamp = observation.timestampUtc }
                lastTimestamp = observation.timestampUtc
                countsBySensorType[observation.sensorType.rawValue, default: 0] += 1
                if observation.sampleKind == .groundTruth { groundTruthCount += 1 }
                observerIds.insert(observation.observerId)
            }
        }
    }
}
