import Combine
import Foundation
import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

#if canImport(UIKit)
import UIKit
#endif

/// The app's single source of truth: owns the store, the providers and the running session, and
/// publishes what the views draw.
///
/// All the logic worth testing lives below this in `RFMapperCollectorKit`. What remains here is
/// wiring and presentation, which is why this type has no tests of its own and the kit has many.
@MainActor
final class CollectorModel: ObservableObject {

    @Published private(set) var observer: ObserverIdentity?
    @Published private(set) var isCollecting = false
    @Published private(set) var profile: ScanProfile = .foregroundSurvey
    @Published private(set) var recent: [Observation] = []
    @Published private(set) var summary: SessionSummary?
    @Published private(set) var degradations: [(reason: String, detail: String?)] = []
    @Published private(set) var totalStored = 0
    @Published private(set) var days: [Int64] = []
    @Published var lastError: String?
    @Published var lastExport: ExportSummary?

    struct ExportSummary: Identifiable {
        let id = UUID()
        let url: URL
        let observations: Int64
        let sha256: String
        let bytes: Int
    }

    private var store: ObservationStore?
    private var session: CollectionSession?
    private var providers: [ObservationProvider] = []
    private var settings = CollectorSettings()

    /// Enrolled service UUIDs. The one procurement decision that matters most on iOS: a tag which
    /// advertises a fixed service UUID can be joined to an Android sighting of the same hardware and
    /// can be seen in the background; one that does not can do neither
    /// (`docs/06-ios-capability-matrix.md` §3).
    @Published private(set) var enrolledServiceUuids: [String] = []

    func prepare() {
        guard store == nil else { return }
        do {
            let directory = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            #if canImport(SQLite3)
            store = try SqliteObservationStore(
                path: directory.appendingPathComponent("observations.sqlite").path
            )
            #else
            store = InMemoryObservationStore()
            #endif
            observer = settings.loadObserver()
            enrolledServiceUuids = settings.enrolledServiceUuids
            refresh()
        } catch {
            lastError = "Could not open the local store: \(error)"
        }
    }

    // MARK: - Observer identity

    func saveObserver(_ identity: ObserverIdentity) {
        do {
            try identity.validate()
            settings.save(identity)
            observer = identity
            lastError = nil
        } catch {
            lastError = "\(error)"
        }
    }

    /// The identity an iPhone should declare, with the capability sets already correct.
    ///
    /// Offered as a starting point rather than left to the operator, because `unsupported` is the
    /// field most likely to be left empty and the one whose absence does the most damage: an iOS
    /// observer that does not declare it cannot see Wi-Fi will have its silence read as "no access
    /// points were present here", which drives every fingerprint's Wi-Fi visibility toward zero.
    func suggestedIdentity(observerId: String, friendlyName: String) -> ObserverIdentity {
        ObserverIdentity(
            observerId: observerId,
            friendlyName: friendlyName,
            observerDeviceType: Self.isPad ? .iosTablet : .iosPhone,
            deviceModel: Self.deviceModel,
            manufacturer: "Apple",
            platform: .ios,
            osVersion: Self.osVersion,
            appVersion: Self.appVersion,
            installationId: settings.installationId,
            capabilities: [.ble, .gps, .wifiAssociation],
            unsupported: [.wifiScan, .rtt],
            fixedObserver: false
        )
    }

    // MARK: - Enrolled tags

    /// Adds a service UUID to the background scan filter.
    ///
    /// Normalized through the same function the capture path uses, for two reasons. `CBUUID(string:)`
    /// raises on a malformed string rather than returning nil, so an unvalidated entry here would
    /// crash the app the next time a background session started. And a shorthand typed as `180D` has
    /// to become the 128-bit value the scanner will compare against, or the filter will match
    /// nothing and the session will look like a site with no tags in it.
    func enroll(serviceUuid raw: String) {
        guard let normalized = RadioIdentifierNormalizer.bluetoothUuid(raw) else {
            lastError = "'\(raw)' is not a Bluetooth service UUID."
            return
        }
        guard !enrolledServiceUuids.contains(normalized) else { return }

        enrolledServiceUuids.append(normalized)
        enrolledServiceUuids.sort()
        settings.enrolledServiceUuids = enrolledServiceUuids
        lastError = nil
    }

    func removeEnrolledServiceUuids(at offsets: IndexSet) {
        enrolledServiceUuids.remove(atOffsets: offsets)
        settings.enrolledServiceUuids = enrolledServiceUuids
    }

    // MARK: - Collection

    func start(profile: ScanProfile) {
        guard let store, let observer else {
            lastError = "Set the observer identity before collecting."
            return
        }
        stop()

        self.profile = profile
        let session = CollectionSession(
            sessionId: UUID().uuidString.lowercased(),
            profile: profile,
            store: store,
            observer: observer,
            now: Self.nowMillis
        )
        #if canImport(UIKit)
        UIDevice.current.isBatteryMonitoringEnabled = true
        session.batteryStartPct = Self.batteryPercent
        #endif
        session.onChange = { [weak self] in
            Task { @MainActor in self?.refreshFromSession() }
        }
        self.session = session

        let factory = ObservationFactory(
            observer: observer,
            appVersion: Self.appVersion,
            osVersion: Self.osVersion,
            deviceModel: Self.deviceModel,
            now: Self.nowMillis,
            newIdentifier: { UUID().uuidString.lowercased() }
        )

        var started: [ObservationProvider] = []
        #if canImport(CoreBluetooth)
        started.append(BleObservationProvider(
            factory: factory,
            sink: session,
            sessionId: session.sessionId,
            profile: profile,
            serviceFilter: profile.requiresServiceFilter ? enrolledServiceUuids : []
        ))
        #endif
        #if canImport(CoreLocation)
        started.append(LocationObservationProvider(
            factory: factory,
            sink: session,
            sessionId: session.sessionId
        ))
        #endif
        #if canImport(NetworkExtension)
        started.append(AssociationObservationProvider(
            factory: factory,
            sink: session,
            sessionId: session.sessionId
        ))
        #endif

        providers = started
        for provider in providers {
            provider.start()
        }

        isCollecting = true
        #if canImport(UIKit)
        // A foreground survey ends when the screen sleeps, so do not let it.
        UIApplication.shared.isIdleTimerDisabled = profile != .backgroundFiltered
        #endif
        refreshFromSession()
    }

    func stop() {
        for provider in providers {
            provider.stop()
        }
        providers = []
        #if canImport(UIKit)
        session?.batteryEndPct = Self.batteryPercent
        UIApplication.shared.isIdleTimerDisabled = false
        #endif
        session?.finish()
        isCollecting = false
        refresh()
    }

    private func refreshFromSession() {
        guard let session else { return }
        recent = session.recentObservations.reversed()
        summary = session.summary()
        degradations = session.degradationExplanations
        totalStored = (try? store?.count()) ?? totalStored
    }

    func refresh() {
        totalStored = (try? store?.count()) ?? 0
        days = (try? store?.daysWithObservations()) ?? []
        if let session {
            summary = session.summary()
            degradations = session.degradationExplanations
        }
    }

    // MARK: - Export

    func exportDay(_ dayStartMillis: Int64) {
        export { try $0.exportDay(dayStartMillis) }
    }

    func exportSession(_ sessionId: String) {
        export { try $0.exportSession(sessionId) }
    }

    private func export(_ build: (PackageExporter) throws -> ExportResult) {
        guard let store, let observer else { return }
        let exporter = PackageExporter(
            store: store,
            observer: observer,
            appVersion: Self.appVersion,
            newIdentifier: { UUID().uuidString.lowercased() },
            now: Self.nowMillis
        )
        do {
            let result = try build(exporter)
            let directory = try FileManager.default.url(
                for: .documentDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("Exports", isDirectory: true)
            let url = try exporter.save(result, to: directory)

            lastExport = ExportSummary(
                url: url,
                observations: result.observationsWritten,
                sha256: result.packageSha256,
                bytes: result.bytes.count
            )
            lastError = nil
        } catch {
            // Including the export engine's own refusals, which are the interesting ones: it
            // declines to write a package whose manifest disagrees with its rows.
            lastError = "\(error)"
        }
    }

    // MARK: - Environment

    static func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    static var osVersion: String {
        #if canImport(UIKit)
        return UIDevice.current.systemVersion
        #else
        return ProcessInfo.processInfo.operatingSystemVersionString
        #endif
    }

    /// The hardware identifier (`iPhone15,2`), not the marketing name. It is what a per-observer
    /// RSSI calibration has to be keyed on, since an iPhone's BLE radio is not interchangeable with
    /// a Pixel's or with another iPhone model's.
    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafePointer(to: &info.machine) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        return machine.isEmpty ? "unknown" : machine
    }

    static var isPad: Bool {
        #if canImport(UIKit)
        return UIDevice.current.userInterfaceIdiom == .pad
        #else
        return false
        #endif
    }

    static var batteryPercent: Int? {
        #if canImport(UIKit)
        let level = UIDevice.current.batteryLevel
        return level < 0 ? nil : Int((level * 100).rounded())
        #else
        return nil
        #endif
    }
}

/// Small persisted settings. `UserDefaults` rather than the store, because none of this is
/// observation data and it must survive the store being rebuilt.
struct CollectorSettings {

    private let defaults = UserDefaults.standard
    private let observerKey = "rfmapper.observer.json"
    private let installationKey = "rfmapper.installation.id"
    private let serviceUuidsKey = "rfmapper.enrolled.service.uuids"

    /// Stable for the life of this install, and the value that tells the Master that two packages
    /// came from the same app installation rather than from two devices.
    var installationId: String {
        if let existing = defaults.string(forKey: installationKey) { return existing }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: installationKey)
        return created
    }

    var enrolledServiceUuids: [String] {
        get { defaults.stringArray(forKey: serviceUuidsKey) ?? [] }
        set { defaults.set(newValue, forKey: serviceUuidsKey) }
    }

    func loadObserver() -> ObserverIdentity? {
        guard let text = defaults.string(forKey: observerKey) else { return nil }
        return ObserverIdentity(json: text)
    }

    func save(_ identity: ObserverIdentity) {
        defaults.set(CanonicalJSON.compact(identity.json), forKey: observerKey)
    }
}
