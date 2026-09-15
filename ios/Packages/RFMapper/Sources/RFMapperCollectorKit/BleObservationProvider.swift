#if canImport(CoreBluetooth)
import CoreBluetooth
import Foundation
import RFMapperCore

/// BLE capture via CoreBluetooth. The core of an iOS Collector, and the only radio on which iOS is
/// genuinely competitive with Android.
///
/// Two CoreBluetooth behaviours shape everything here, and both are documented in
/// `docs/06-ios-capability-matrix.md` §2:
///
/// 1. **`CBCentralManagerScanOptionAllowDuplicatesKey` must be true** to receive repeated
///    advertisements from one peripheral. Without it the delegate fires once per peripheral per
///    scan, which yields a presence list rather than the sequence of RSSI samples that positioning
///    needs.
/// 2. **An unfiltered scan (`withServices: nil`) works in the foreground only.** In the background
///    a service-UUID filter is mandatory and `allowDuplicates` is ignored outright. There is no way
///    to opt out of this and no iOS equivalent of Android's foreground service, so unattended
///    all-day collection is not achievable; the honest deployment is Android for unattended work and
///    iOS for supervised survey.
///
/// The class reports the restriction rather than working around it. When a scan starts in a mode the
/// system will silently narrow, that fact is recorded as a degradation so the resulting gap in the
/// data has a stated cause.
public final class BleObservationProvider: NSObject, ObservationProvider {

    public let capability: ObserverCapability = .ble

    private var central: CBCentralManager?
    private let factory: ObservationFactory
    private weak var sink: ObservationSink?

    private var profile: ScanProfile
    private var sessionId: String
    private var serviceFilter: [CBUUID]

    /// Set once the caller has asked to scan, so a start that arrives before Bluetooth is powered on
    /// is honoured when it becomes available rather than dropped.
    private var wantsToScan = false
    private var hasReportedUnauthorized = false

    /// Advertisements arrive faster than they are worth recording -- a nearby beacon can advertise
    /// ten times a second -- so one row per peripheral per interval is kept and the rest are
    /// counted. Counted, not discarded quietly: the count goes into the session's `dropped_samples`
    /// so the record shows how much was thinned.
    private var lastRecorded: [UUID: Int64] = [:]
    private var thinnedCount = 0

    public init(
        factory: ObservationFactory,
        sink: ObservationSink,
        sessionId: String,
        profile: ScanProfile = .foregroundSurvey,
        serviceFilter: [String] = []
    ) {
        self.factory = factory
        self.sink = sink
        self.sessionId = sessionId
        self.profile = profile
        self.serviceFilter = Self.filter(from: serviceFilter)
        super.init()
    }

    /// Converts enrolled UUID strings to `CBUUID` without trusting them.
    ///
    /// `CBUUID(string:)` raises an `NSException` on a malformed string rather than returning nil, and
    /// an Objective-C exception is not catchable in Swift -- so one bad entry terminates the app at
    /// the moment a session starts, which is the worst possible time. The values normally come from
    /// ``RadioIdentifierNormalizer/bluetoothUuid(_:)`` via the enrolment screen and are already
    /// 128-bit, but they are persisted in `UserDefaults`, which means they outlive the build that
    /// wrote them and can be carried forward from an older normalizer or edited by hand.
    ///
    /// Normalizing again here is cheap and makes the crash unreachable rather than unlikely.
    private static func filter(from raw: [String]) -> [CBUUID] {
        raw.compactMap { candidate in
            RadioIdentifierNormalizer.bluetoothUuid(candidate).map(CBUUID.init(string:))
        }
    }

    public var isAvailable: Bool {
        central?.state == .poweredOn
    }

    public func start() {
        wantsToScan = true
        if central == nil {
            // Created lazily: instantiating a CBCentralManager triggers the system permission
            // prompt, and that should happen when the operator asks to scan rather than at launch.
            central = CBCentralManager(delegate: self, queue: .main)
            return
        }
        beginScanIfPossible()
    }

    public func stop() {
        wantsToScan = false
        central?.stopScan()
        flushThinnedCount()
    }

    public func reconfigure(profile: ScanProfile, sessionId: String, serviceFilter: [String]) {
        self.profile = profile
        self.sessionId = sessionId
        self.serviceFilter = Self.filter(from: serviceFilter)
        lastRecorded.removeAll()
        if wantsToScan {
            central?.stopScan()
            beginScanIfPossible()
        }
    }

    private func beginScanIfPossible() {
        guard let central, central.state == .poweredOn, wantsToScan else { return }

        if profile.requiresServiceFilter && serviceFilter.isEmpty {
            // Scanning with no filter in this mode would appear to work and return nothing once the
            // app is not in front. Refusing loudly is better than collecting silence.
            sink?.recordDegradation(
                Degradation.backgroundRequiresServiceFilter,
                detail: "background scanning needs at least one service UUID; no scan was started"
            )
            return
        }

        central.scanForPeripherals(
            withServices: serviceFilter.isEmpty ? nil : serviceFilter,
            options: [
                CBCentralManagerScanOptionAllowDuplicatesKey: profile.advertisesDuplicates
            ]
        )

        if profile.requiresServiceFilter {
            sink?.recordDegradation(
                Degradation.backgroundRequiresServiceFilter,
                detail: "only the \(serviceFilter.count) enrolled service UUID(s) are visible, "
                    + "and the system ignores the duplicates option"
            )
        }
    }

    private func flushThinnedCount() {
        guard thinnedCount > 0 else { return }
        sink?.recordDropped(count: thinnedCount, reason: "BLE_ADVERTISEMENT_THINNED")
        thinnedCount = 0
    }

    /// Minimum gap between recorded rows for one peripheral.
    private var thinningIntervalMillis: Int64 {
        switch profile {
        case .foregroundSurvey: return 1_000
        case .foregroundEconomy: return 5_000
        case .backgroundFiltered: return 15_000
        }
    }
}

extension BleObservationProvider: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            beginScanIfPossible()
        case .unauthorized:
            // Reported once. A repeated degradation for one standing condition would bury the rest.
            if !hasReportedUnauthorized {
                hasReportedUnauthorized = true
                sink?.recordDegradation(
                    Degradation.bluetoothUnauthorized,
                    detail: "the operator declined Bluetooth access; no BLE rows can be collected"
                )
            }
        case .poweredOff:
            sink?.recordDegradation(
                Degradation.bluetoothPoweredOff,
                detail: "Bluetooth is switched off"
            )
        default:
            break
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // CoreBluetooth reports 127 to mean "not available" rather than a signal level, and the
        // schema's plausible range stops at 20 dBm. Recording it would put a sentinel into a
        // measurement column.
        let rssi = RSSI.intValue
        guard rssi >= -127, rssi <= 20 else { return }

        let now = factory.now()
        if let previous = lastRecorded[peripheral.identifier],
           now - previous < thinningIntervalMillis {
            thinnedCount += 1
            return
        }
        lastRecorded[peripheral.identifier] = now

        let serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?
            .map(\.uuidString) ?? []
        let manufacturerBytes = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data)
            .map { Array($0) }
        var serviceData: [String: [UInt8]] = [:]
        if let raw = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] {
            for (uuid, data) in raw {
                serviceData[uuid.uuidString.lowercased()] = Array(data)
            }
        }

        do {
            let observation = try factory.ble(
                // Not a hardware address: see `ObservationFactory.ble`.
                peripheralIdentifier: peripheral.identifier.uuidString,
                rssi: rssi,
                serviceUuids: serviceUuids,
                manufacturerData: manufacturerBytes,
                serviceData: serviceData,
                txPower: (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue,
                localName: advertisementData[CBAdvertisementDataLocalNameKey] as? String,
                isConnectable: (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue,
                sessionId: sessionId,
                confidence: confidence(for: advertisementData)
            )
            sink?.record(observation)
        } catch {
            sink?.recordDropped(count: 1, reason: "BLE_ROW_REJECTED")
        }

        if thinnedCount >= 500 { flushThinnedCount() }
    }

    /// Collection-time measurement quality, not a position confidence.
    ///
    /// A background, service-filtered scan is throttled by the system to an unspecified rate, so its
    /// samples deserve less trust than a foreground scan's -- and the number says so rather than
    /// leaving every sample looking equally good.
    private func confidence(for advertisementData: [String: Any]) -> Double {
        var value = profile == .backgroundFiltered ? 0.5 : 0.9
        if advertisementData[CBAdvertisementDataServiceUUIDsKey] == nil {
            // No service UUID means this sighting cannot be joined across platforms at all.
            value -= 0.1
        }
        return max(0.1, min(1.0, value))
    }
}
#endif
