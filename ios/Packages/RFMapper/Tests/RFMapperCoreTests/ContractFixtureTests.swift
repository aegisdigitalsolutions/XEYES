import Foundation
import XCTest
@testable import RFMapperCore

/// Generates and checks the Swift side of the cross-language contract.
///
/// The repository already has fixtures produced by Kotlin and consumed by Python. This adds the
/// third producer: an export package written by the Swift core, committed under `contract/`, and
/// then read back by the Kotlin `PackageValidator` (`ContractFixtureTest.kt`) and the Python Lab
/// (`test_ios_contract.py`). That round trip is what `docs/06-ios-capability-matrix.md` §7 promised
/// as the acceptance criterion for an iOS Collector, and it is stronger than comparing the Swift
/// output against Swift's own expectations: Python recomputes every `row_checksum` and every entry
/// digest independently, and cross-checks the CSV against the JSON, so agreement there means the
/// two implementations genuinely match rather than sharing a bug.
///
/// Set `RFMAPPER_WRITE_CONTRACT=1` to regenerate the committed fixture, mirroring the Kotlin side's
/// `-Drfmapper.contract.write=true`. Regenerating is a deliberate act: the diff on those bytes is
/// the review surface for a change to the file contract.
final class ContractFixtureTests: XCTestCase {

    private static let packageName = "RFMapper_OBSI1_2026-05-04.zip"

    func testTheIosPackageMatchesTheCommittedFixture() throws {
        let observations = try IosContractScenario.observations()
        let result = try ExportEngineV1().write(
            request: IosContractScenario.request(for: observations),
            observations: observations
        )

        XCTAssertEqual(result.packageName, Self.packageName)
        XCTAssertEqual(result.observationsWritten, Int64(observations.count))

        let path = try contractDirectory().appendingPathComponent(Self.packageName)
        if ProcessInfo.processInfo.environment["RFMAPPER_WRITE_CONTRACT"] == "1" {
            try Data(result.bytes).write(to: path)
            print("wrote \(path.path) (\(result.bytes.count) bytes)")
            return
        }

        guard let committed = try? Data(contentsOf: path) else {
            XCTFail(
                "\(path.path) is missing. Run with RFMAPPER_WRITE_CONTRACT=1 to generate it."
            )
            return
        }

        // Compared as whole-archive bytes, which is only a fair test because the writer is
        // deterministic by construction: STORED entries, fixed order, and a pinned modification
        // time that does not vary with the device's time zone.
        XCTAssertEqual(
            Array(committed), result.bytes,
            "the Swift export no longer reproduces the committed contract fixture. If the change to "
                + "the file format is intended, regenerate with RFMAPPER_WRITE_CONTRACT=1 and review "
                + "the diff; if not, this is the regression the fixture exists to catch."
        )
    }

    func testTwoExportsOfTheSameDataAreByteIdentical() throws {
        let observations = try IosContractScenario.observations()
        let request = IosContractScenario.request(for: observations)
        let engine = ExportEngineV1()

        let first = try engine.write(request: request, observations: observations)
        let second = try engine.write(request: request, observations: observations)

        XCTAssertEqual(first.bytes, second.bytes)
        XCTAssertEqual(first.packageSha256, second.packageSha256)
    }

    /// The archive must not depend on the device's time zone.
    ///
    /// This is the bug the Kotlin side shipped and then fixed: `ZipEntry.setTime` converts through
    /// *local* DOS time, so the same rows exported in Tokyo and in Niue produced different bytes and
    /// therefore different package digests. A Collector carried across the date line must not be
    /// able to change a package without changing an observation.
    func testTheArchiveDoesNotDependOnTheHostTimeZone() throws {
        let observations = try IosContractScenario.observations()
        let request = IosContractScenario.request(for: observations)

        let original = getenv("TZ").map { String(cString: $0) }
        defer {
            if let original { setenv("TZ", original, 1) } else { unsetenv("TZ") }
            tzset()
        }

        var produced: [[UInt8]] = []
        for zone in ["Asia/Tokyo", "Pacific/Niue", "UTC"] {
            setenv("TZ", zone, 1)
            tzset()
            produced.append(try ExportEngineV1().write(request: request, observations: observations).bytes)
        }

        XCTAssertEqual(produced[0], produced[1])
        XCTAssertEqual(produced[1], produced[2])
    }

    func testTheEngineRefusesRowsThatAreOutOfOrder() throws {
        let observations = try IosContractScenario.observations()
        let reversed = Array(observations.reversed())

        XCTAssertThrowsError(
            try ExportEngineV1().write(
                request: IosContractScenario.request(for: observations),
                observations: reversed
            )
        ) { error in
            guard case ExportError.unorderedRows = error else {
                return XCTFail("expected an ordering failure, got \(error)")
            }
        }
    }

    /// A manifest is computed from aggregates before the rows are written, so it can disagree with
    /// them. The engine has to notice rather than ship a package whose manifest misstates it.
    func testTheEngineRefusesAManifestThatDisagreesWithTheRows() throws {
        let observations = try IosContractScenario.observations()
        var request = IosContractScenario.request(for: observations)
        request.summary.observationCount += 1

        XCTAssertThrowsError(
            try ExportEngineV1().write(request: request, observations: observations)
        ) { error in
            guard case ExportError.manifestDisagreement = error else {
                return XCTFail("expected a manifest disagreement, got \(error)")
            }
        }
    }

    func testAForeignRowIsRefused() throws {
        var observations = try IosContractScenario.observations()
        let request = IosContractScenario.request(for: observations)
        observations[0].observerId = "OBS-SOMEONE-ELSE"

        XCTAssertThrowsError(
            try ExportEngineV1().write(request: request, observations: observations)
        ) { error in
            guard case ExportError.manifestDisagreement(let detail) = error else {
                return XCTFail("expected a manifest disagreement, got \(error)")
            }
            XCTAssertTrue(detail.contains("OBS-SOMEONE-ELSE"), detail)
        }
    }

    /// Every row must survive a trip through the CSV dialect unchanged, including the awkward SSID.
    func testEveryRowRoundTripsThroughCsv() throws {
        let observations = try IosContractScenario.observations()
        let header = ObservationCsvCodec.columns

        for observation in observations {
            let encoded = ObservationCsvCodec.encode(observation)
            let records = Csv.parse(encoded + "\n")
            XCTAssertEqual(records.count, 1, "row \(observation.observationId) did not parse as one record")

            switch ObservationCsvCodec.decode(header: header, record: records[0]) {
            case .failure(let reasons):
                XCTFail("row \(observation.observationId) failed to decode: \(reasons)")
            case .success(let decoded, let checksumMatched):
                XCTAssertTrue(checksumMatched, "row \(observation.observationId) failed its own checksum")
                XCTAssertEqual(decoded, observation, "row \(observation.observationId) changed in transit")
            }
        }
    }

    /// Locates the repository's `contract/` directory by walking up from this source file, which is
    /// how the Kotlin and Python sides find it too.
    private func contractDirectory() throws -> URL {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent("contract")
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("site_model.json").path) {
                return candidate
            }
            directory = directory.deletingLastPathComponent()
        }
        throw XCTSkip("could not locate the repository's contract/ directory from \(#filePath)")
    }
}
