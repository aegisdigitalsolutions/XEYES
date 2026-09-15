#if canImport(NetworkExtension)
import Foundation
import NetworkExtension
import RFMapperCore

/// The current Wi-Fi association: the one Wi-Fi observation an iOS Collector can produce.
///
/// This provider is the whole of iOS's Wi-Fi story, and it is worth being precise about what it can
/// and cannot do, because the temptation to treat it as a scanner is the single most likely way to
/// design something that does not work:
///
/// - **It is not a scan.** It reports exactly one network -- the one this device is joined to. There
///   is no public API on any iOS version that enumerates nearby access points; `Apple80211` was
///   withdrawn in iOS 9 and `NEHotspotHelper` needs an entitlement granted only to
///   hotspot-provisioning apps. Any plan that budgets iOS Wi-Fi scanning is wrong.
/// - **It carries no RSSI.** iOS does not expose the associated network's signal strength.
///   `NEHotspotNetwork.signalStrength` is a coarse bar-level value documented as unspecified, and
///   is deliberately not read here: putting it in the `rssi` column would be fabricating a
///   measurement.
/// - **It needs an entitlement.** `com.apple.developer.networking.wifi-info`, plus location
///   permission. Without them `fetchCurrent` yields a network with no BSSID, which is recorded as a
///   degradation rather than as an absence of Wi-Fi.
///
/// What remains is genuinely useful, and for an association-based deployment it is the entire
/// signal: *this device is attached to that access point*. Where the access points are fixed and
/// their locations are known, that places the device in the access point's zone with no survey at
/// all (`docs/19-association-only-deployment.md`).
public final class AssociationObservationProvider: ObservationProvider {

    public let capability: ObserverCapability = .wifiAssociation

    private let factory: ObservationFactory
    private weak var sink: ObservationSink?
    private var sessionId: String
    private let interval: TimeInterval

    private var timer: Timer?

    /// The last BSSID recorded, so a device sitting on one access point produces a sample per
    /// interval rather than a row per poll, and a roam is visible as a change.
    private var lastBssid: String?
    private var hasReportedUnavailable = false

    public init(
        factory: ObservationFactory,
        sink: ObservationSink,
        sessionId: String,
        interval: TimeInterval = 20
    ) {
        self.factory = factory
        self.sink = sink
        self.sessionId = sessionId
        self.interval = interval
    }

    /// Unknowable without asking. iOS reports the entitlement's absence by returning a network with
    /// no BSSID rather than by failing, so availability is discovered at the first poll.
    public var isAvailable: Bool { true }

    public func start() {
        stop()
        poll()
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.poll()
        }
        // `.common` so polling continues while the operator is scrolling the dashboard, which the
        // default run-loop mode would suspend.
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    public func reconfigure(sessionId: String) {
        self.sessionId = sessionId
        lastBssid = nil
    }

    private func poll() {
        NEHotspotNetwork.fetchCurrent { [weak self] network in
            guard let self else { return }
            guard let network else {
                // Not joined to Wi-Fi. An ordinary state, not a fault, and not recorded as one: a
                // degradation per poll while a phone is on cellular would drown the real ones.
                self.lastBssid = nil
                return
            }
            self.record(network)
        }
    }

    private func record(_ network: NEHotspotNetwork) {
        // An empty or all-zero BSSID is what iOS returns when the entitlement or the location
        // permission is missing. It is not an access point.
        let bssid = network.bssid
        guard !bssid.isEmpty, RadioIdentifierNormalizer.mac(bssid) != nil,
              !bssid.allSatisfy({ $0 == "0" || $0 == ":" })
        else {
            if !hasReportedUnavailable {
                hasReportedUnavailable = true
                sink?.recordDegradation(
                    Degradation.wifiInfoUnavailable,
                    detail: "joined to '\(network.ssid)' but iOS disclosed no BSSID. The "
                        + "com.apple.developer.networking.wifi-info entitlement and location "
                        + "permission are both required."
                )
            }
            return
        }

        do {
            let observation = try factory.association(
                bssid: bssid,
                ssid: network.ssid.isEmpty ? nil : network.ssid,
                sessionId: sessionId,
                // An association is a strong proximity statement but a coarse one, and the
                // collection-time quality says so: there is no signal level to corroborate it and
                // an access point's coverage may be large.
                confidence: bssid == lastBssid ? 0.75 : 0.8
            )
            lastBssid = bssid
            sink?.record(observation)
        } catch {
            sink?.recordDropped(count: 1, reason: "ASSOCIATION_ROW_REJECTED")
        }
    }
}
#endif
