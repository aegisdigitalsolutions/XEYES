import Foundation
import XCTest
import RFMapperCore
@testable import RFMapperCollectorKit

/// Tests for the parts of collection that are decisions rather than framework plumbing: how a
/// platform reading becomes a canonical row, what the store guarantees, and whether a session
/// accounts honestly for what it lost.
///
/// The CoreBluetooth, CoreLocation and NetworkExtension providers are not covered here, because
/// there is nothing to assert about them off-device that would not just be asserting the shape of a
/// mock. What they *decide* -- the identifier scope, the missing RSSI, the confidence for a
/// degraded fix -- lives in ``ObservationFactory`` and is tested directly.

private func fixedFactory(
    startingAt millis: Int64 = 1_777_888_800_000,
    // Distinct per factory when a test needs several, since two factories sharing a counter would
    // mint the same observation ids and the store would -- correctly -- reject the second set as
    // duplicates.
    idsFrom firstId: Int = 1,
    observer: ObserverIdentity? = nil
) -> ObservationFactory {
    var clock = millis
    var sequence = firstId - 1
    return ObservationFactory(
        observer: observer ?? testObserver(),
        appVersion: "1.0.0",
        osVersion: "18.4",
        deviceModel: "iPhone15,2",
        now: {
            clock += 1_000
            return clock
        },
        newIdentifier: {
            sequence += 1
            let hex = String(sequence, radix: 16)
            return "00000000-0000-4000-8000-" + String(repeating: "0", count: 12 - hex.count) + hex
        }
    )
}

private func testObserver() -> ObserverIdentity {
    ObserverIdentity(
        observerId: "OBS-I1",
        friendlyName: "Survey iPhone",
        observerDeviceType: .iosPhone,
        buildingId: "B1",
        defaultZoneId: "B1-NORTH",
        deviceModel: "iPhone15,2",
        manufacturer: "Apple",
        platform: .ios,
        osVersion: "18.4",
        appVersion: "1.0.0",
        installationId: "a1b2c3d4-2222-4333-8444-5555666677a1",
        capabilities: [.ble, .gps, .wifiAssociation],
        unsupported: [.wifiScan, .rtt]
    )
}

final class ObservationFactoryTests: XCTestCase {

    /// The single most consequential iOS-specific decision in the whole Collector.
    func testABlePeripheralIsRecordedAsAppInstallScopedRatherThanAsAnAddress() throws {
        let factory = fixedFactory()
        let observation = try factory.ble(
            peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
            rssi: -58,
            serviceUuids: ["6B1A7E10-3C4D-4F5A-9B8C-1D2E3F405162"],
            manufacturerData: [0x00, 0x4C, 0x02, 0x15],
            txPower: -8,
            sessionId: "session-1",
            confidence: 0.9
        )

        // CoreBluetooth never discloses a hardware address. Claiming BLE_MAC_PUBLIC or
        // BLE_MAC_RANDOM would invite a reader to treat this UUID as one.
        XCTAssertEqual(observation.identifierType, .other)
        XCTAssertEqual(observation.radioIdentifier, "9f8e7d6c-5b4a-4392-8281-706f5e4d3c2b")

        // The field that stops the Lab joining this to another observer's sighting of something
        // else entirely.
        XCTAssertEqual(observation.metadata[MetadataKeys.identifierScope], "APP_INSTALL")
        XCTAssertEqual(
            observation.metadata[MetadataKeys.iosPeripheralIdentifier],
            "9f8e7d6c-5b4a-4392-8281-706f5e4d3c2b"
        )

        // The only key by which this sighting can be joined to an Android sighting of the same tag.
        XCTAssertEqual(observation.bleServiceUuid, "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162")
        XCTAssertEqual(observation.manufacturerData, "004C0215")
    }

    func testAllAdvertisedServiceUuidsAreKeptNotJustTheFirst() throws {
        let observation = try fixedFactory().ble(
            peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
            rssi: -60,
            serviceUuids: [
                "FFFF0000-0000-1000-8000-00805F9B34FB",
                "6B1A7E10-3C4D-4F5A-9B8C-1D2E3F405162",
            ],
            sessionId: "session-1",
            confidence: 0.9
        )

        // A device advertising several must not be reduced to whichever happened to come first.
        XCTAssertEqual(
            observation.metadata[MetadataKeys.bleServiceUuids],
            "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162,ffff0000-0000-1000-8000-00805f9b34fb"
        )
    }

    /// A tag advertising a SIG-assigned service must stay joinable to the Android sighting of it.
    ///
    /// CoreBluetooth hands back `"180D"` where Android hands back the 128-bit form. If the capture
    /// path stored the shorthand, or dropped it for not being a UUID, the service UUID column would
    /// be empty for exactly the devices whose service UUID is most standard -- and the service UUID
    /// is the only key by which an iOS sighting can be joined to anything at all
    /// (`docs/06-ios-capability-matrix.md` §3).
    func testAStandardServiceUuidIsRecordedInTheFormAndroidWouldRecord() throws {
        let observation = try fixedFactory().ble(
            peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
            rssi: -60,
            serviceUuids: ["180D"],
            sessionId: "session-1",
            confidence: 0.9
        )

        XCTAssertEqual(observation.bleServiceUuid, "0000180d-0000-1000-8000-00805f9b34fb")
        XCTAssertEqual(
            observation.metadata[MetadataKeys.bleServiceUuids],
            "0000180d-0000-1000-8000-00805f9b34fb"
        )
    }

    /// The iOS Wi-Fi row, and the reason the Lab had to change to accept it.
    func testAnAssociationCarriesABssidAndDeliberatelyNoRssi() throws {
        let observation = try fixedFactory().association(
            bssid: "AA-BB-CC-00-00-01",
            ssid: "RFMAPPER-SITE",
            sessionId: "session-1",
            confidence: 0.8
        )

        XCTAssertEqual(observation.sensorType, .wifiAssociation)
        XCTAssertEqual(observation.bssid, "aa:bb:cc:00:00:01")
        XCTAssertEqual(observation.identifierType, .wifiBssid)

        // iOS does not expose the associated network's signal strength. Substituting
        // NEHotspotNetwork.signalStrength -- a coarse bar-level value documented as unspecified --
        // would be inventing a measurement.
        XCTAssertNil(observation.rssi)

        // And the row says why, so a reader does not mistake the absence for a collection fault.
        XCTAssertTrue(observation.isPermissionDegraded)
        XCTAssertEqual(
            observation.metadata[MetadataKeys.missingPermissions],
            Degradation.wifiRssiUnavailable
        )
    }

    func testAReducedAccuracyFixIsTaggedAndDiscounted() throws {
        let factory = fixedFactory()
        let precise = try factory.location(
            latitude: 51.50742,
            longitude: -0.12781,
            horizontalAccuracy: 5,
            sessionId: "session-1",
            reducedAccuracy: false,
            confidence: LocationConfidence.forAccuracy(5, reduced: false)
        )
        let approximate = try factory.location(
            latitude: 51.5,
            longitude: -0.12,
            horizontalAccuracy: 3_000,
            sessionId: "session-1",
            reducedAccuracy: true,
            confidence: LocationConfidence.forAccuracy(3_000, reduced: true)
        )

        XCTAssertFalse(precise.isPermissionDegraded)
        // iOS 14's approximate location is good to kilometres: usable for "which site", useless for
        // anything finer, and the row has to say so rather than looking like a 3 km GPS fix.
        XCTAssertTrue(approximate.isPermissionDegraded)
        XCTAssertEqual(
            approximate.metadata[MetadataKeys.missingPermissions],
            Degradation.locationReducedAccuracy
        )
        XCTAssertGreaterThan(precise.confidence!, approximate.confidence!)
    }

    func testAGnssRowDescribesTheObserverAndSaysSo() throws {
        let observation = try fixedFactory().location(
            latitude: 51.50742,
            longitude: -0.12781,
            horizontalAccuracy: 14.5,
            sessionId: "session-1",
            reducedAccuracy: false,
            confidence: 0.8
        )

        // The schema keeps "where the observer was" and "where the target is" strictly apart, and
        // this is the row most likely to be misread as the latter.
        XCTAssertEqual(observation.identifierType, .gnssFix)
        XCTAssertEqual(observation.radioIdentifier, "OBSERVER_SELF")
        XCTAssertEqual(observation.observerId, "OBS-I1")
    }

    func testAnUnusableIdentifierIsRefusedRatherThanCoerced() {
        let factory = fixedFactory()
        XCTAssertThrowsError(try factory.ble(
            peripheralIdentifier: "not-a-uuid",
            rssi: -60,
            sessionId: "session-1",
            confidence: 0.9
        ))
        XCTAssertThrowsError(try factory.association(
            bssid: "nonsense",
            ssid: nil,
            sessionId: "session-1",
            confidence: 0.8
        ))
    }

    func testEveryProducedRowSatisfiesTheSchemaInvariants() throws {
        let factory = fixedFactory()
        let rows = [
            try factory.ble(
                peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
                rssi: -58,
                sessionId: "s",
                confidence: 0.9
            ),
            try factory.association(bssid: "aa:bb:cc:00:00:01", ssid: "S", sessionId: "s", confidence: 0.8),
            try factory.location(
                latitude: 1,
                longitude: 2,
                horizontalAccuracy: 3,
                sessionId: "s",
                reducedAccuracy: false,
                confidence: 0.8
            ),
        ]
        for row in rows {
            XCTAssertEqual(ObservationInvariants.check(row), [], "row \(row.observationId)")
        }
    }
}

/// The accuracy-to-confidence curve, lifted out of the CoreLocation provider so it can be tested
/// without the framework.
enum LocationConfidence {
    static func forAccuracy(_ accuracy: Double, reduced: Bool) -> Double {
        if reduced { return 0.2 }
        let clamped = max(5.0, min(100.0, accuracy))
        return max(0.2, min(1.0, 1.0 - (clamped - 5.0) / 95.0 * 0.8))
    }
}

final class ObservationStoreTests: XCTestCase {

    func testRowsComeBackInTheOrderTheExportEngineRequires() throws {
        let store = InMemoryObservationStore()
        let factory = fixedFactory()

        // Appended out of order on purpose: the ordering guarantee has to belong to the query, not
        // to the caller's discipline.
        var rows: [Observation] = []
        for _ in 0..<5 {
            rows.append(try factory.ble(
                peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
                rssi: -60,
                sessionId: "s",
                confidence: 0.9
            ))
        }
        for row in rows.shuffled() {
            XCTAssertTrue(try store.append(row))
        }

        let day = Iso8601.startOfUtcDay(rows[0].timestampEpochMillis!)
        let fetched = try store.observations(onUtcDay: day)

        XCTAssertEqual(fetched.count, 5)
        XCTAssertEqual(
            fetched.map(\.observationId),
            fetched.sorted { ($0.timestampUtc, $0.observationId) < ($1.timestampUtc, $1.observationId) }
                .map(\.observationId)
        )
    }

    func testAppendingTheSameIdTwiceIsReportedRatherThanStoredTwice() throws {
        let store = InMemoryObservationStore()
        let row = try fixedFactory().ble(
            peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
            rssi: -60,
            sessionId: "s",
            confidence: 0.9
        )

        XCTAssertTrue(try store.append(row))
        XCTAssertFalse(try store.append(row), "the second append should report the duplicate")
        XCTAssertEqual(try store.count(), 1)
    }

    func testDaysAreReportedMostRecentFirst() throws {
        let store = InMemoryObservationStore()
        let dayOne: Int64 = 1_777_888_800_000
        for (index, offset) in [Int64(0), Iso8601.millisPerDay, 2 * Iso8601.millisPerDay].enumerated() {
            let factory = fixedFactory(startingAt: dayOne + offset, idsFrom: 1 + index * 10)
            _ = try store.append(try factory.ble(
                peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
                rssi: -60,
                sessionId: "s",
                confidence: 0.9
            ))
        }

        let days = try store.daysWithObservations()
        XCTAssertEqual(days.count, 3)
        XCTAssertEqual(days, days.sorted(by: >))
    }
}

final class CollectionSessionTests: XCTestCase {

    private func session(
        profile: ScanProfile = .foregroundSurvey,
        store: ObservationStore
    ) -> CollectionSession {
        CollectionSession(
            sessionId: "00000000-0000-4000-9000-000000000101",
            profile: profile,
            store: store,
            observer: testObserver(),
            now: { 1_777_888_800_000 }
        )
    }

    /// Recorded at the start of every session, not on failure: the limits are structural and a
    /// package should be self-describing without the reader knowing which platform wrote it.
    func testASessionDeclaresWhatIosCannotDoBeforeAnyRowArrives() {
        let summary = session(store: InMemoryObservationStore()).summary()

        XCTAssertTrue(summary.degradations.contains(Degradation.wifiScanUnsupported))
        XCTAssertTrue(summary.degradations.contains(Degradation.rttUnsupported))
        XCTAssertEqual(summary.observationCount, 0)
    }

    func testABackgroundSessionSaysItCannotSeeUnenrolledDevices() {
        let summary = session(profile: .backgroundFiltered, store: InMemoryObservationStore()).summary()

        XCTAssertTrue(summary.degradations.contains(Degradation.backgroundRequiresServiceFilter))
        XCTAssertTrue(summary.backgroundDenied)
    }

    func testCountsAndLossesAreAccountedSeparately() throws {
        let store = InMemoryObservationStore()
        let session = self.session(store: store)
        let factory = fixedFactory()

        let ble = try factory.ble(
            peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
            rssi: -58,
            sessionId: session.sessionId,
            confidence: 0.9
        )
        session.record(ble)
        session.record(ble) // the same row again: a duplicate, not a second observation
        session.record(try factory.association(
            bssid: "aa:bb:cc:00:00:01",
            ssid: "S",
            sessionId: session.sessionId,
            confidence: 0.8
        ))
        session.recordDropped(count: 12, reason: "BLE_ADVERTISEMENT_THINNED")

        let summary = session.summary()
        XCTAssertEqual(summary.observationCount, 2)
        XCTAssertEqual(session.duplicatesRejected, 1)
        XCTAssertEqual(summary.bleCount, 1)
        // An association is a Wi-Fi row; what iOS cannot do is *scan*.
        XCTAssertEqual(summary.wifiCount, 1)
        XCTAssertEqual(summary.rttCount, 0)
        // Thinning is a real loss of samples and is reported as one.
        XCTAssertEqual(summary.droppedSamples, 12)
        XCTAssertTrue(summary.degradations.contains("BLE_ADVERTISEMENT_THINNED"))
    }

    func testTheSessionIsPersistedBeforeAnyRowSoAKilledSessionStillHasARecord() throws {
        let store = InMemoryObservationStore()
        let session = self.session(store: store)

        // The alternative -- writing the summary at stop -- loses exactly the sessions whose loss
        // most needs explaining.
        let stored = try store.session(session.sessionId)
        XCTAssertNotNil(stored)
        XCTAssertNil(stored?.endedAt, "an unfinished session should have no end time")

        session.finish()
        XCTAssertNotNil(try store.session(session.sessionId)?.endedAt)
    }

    func testARepeatedDegradationIsRecordedOnce() {
        let session = self.session(store: InMemoryObservationStore())
        for _ in 0..<10 {
            session.recordDegradation(Degradation.bluetoothPoweredOff, detail: "off")
        }

        let occurrences = session.summary().degradations
            .filter { $0 == Degradation.bluetoothPoweredOff }
        XCTAssertEqual(occurrences.count, 1, "one standing condition should not bury the others")
    }
}

final class PackageExporterTests: XCTestCase {

    func testADayExportRoundTripsThroughTheStore() throws {
        let store = InMemoryObservationStore()
        let factory = fixedFactory()
        let session = CollectionSession(
            sessionId: "00000000-0000-4000-9000-000000000101",
            profile: .foregroundSurvey,
            store: store,
            observer: testObserver(),
            now: { 1_777_888_800_000 }
        )

        for _ in 0..<10 {
            session.record(try factory.ble(
                peripheralIdentifier: "9F8E7D6C-5B4A-4392-8281-706F5E4D3C2B",
                rssi: -58,
                sessionId: session.sessionId,
                confidence: 0.9
            ))
        }
        session.finish()

        var sequence = 100
        let exporter = PackageExporter(
            store: store,
            observer: testObserver(),
            appVersion: "1.0.0",
            newIdentifier: {
                sequence += 1
                return "00000000-0000-4000-a000-0000000000\(sequence)"
            },
            now: { 1_777_888_800_000 + 12 * 3_600_000 }
        )

        let day = Iso8601.startOfUtcDay(1_777_888_800_000)
        let result = try exporter.exportDay(day)

        XCTAssertEqual(result.observationsWritten, 10)
        XCTAssertEqual(result.packageName, "RFMapper_OBSI1_2026-05-04.zip")
        // Every required entry, plus sessions.json because the rows belong to a session.
        for entry in ExportPackage.requiredEntries {
            XCTAssertNotNil(result.entryDigests[entry], "missing \(entry)")
        }
        XCTAssertNotNil(result.entryDigests[ExportPackage.sessions])
    }

    /// The store's rows and the manifest's aggregates are computed by different code paths. If they
    /// ever disagree the engine must refuse the package rather than ship a manifest that lies.
    func testAnEmptyDayProducesAWellFormedEmptyPackage() throws {
        let store = InMemoryObservationStore()
        let exporter = PackageExporter(
            store: store,
            observer: testObserver(),
            appVersion: "1.0.0",
            newIdentifier: { "00000000-0000-4000-a000-000000000001" },
            now: { 1_777_888_800_000 }
        )

        let result = try exporter.exportDay(Iso8601.startOfUtcDay(1_777_888_800_000))
        XCTAssertEqual(result.observationsWritten, 0)
        XCTAssertNotNil(result.entryDigests[ExportPackage.observationsCsv])
    }
}
