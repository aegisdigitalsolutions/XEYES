import Foundation
import RFMapperCore

/// One capture session: the thing an operator starts, watches and stops.
///
/// It owns the ``ObservationSink`` the providers write into, keeps the running counts that become
/// `sessions.json`, and is the single place that knows how much was lost and why. That last
/// responsibility is the reason this is not simply a counter. A Collector that quietly produces
/// fewer rows -- because a permission was declined, because the system throttled a background scan,
/// because advertisements were thinned -- looks from the Lab's side like a site with fewer radios in
/// it. Recording the loss is what keeps an unexplained coverage gap from becoming a false statement
/// about the site.
///
/// **Main-queue confined.** The counters and `recentObservations` are plain mutable state with no
/// lock, so every ``ObservationSink`` call must arrive on the main queue. That is an invariant the
/// providers uphold rather than a hope: `CBCentralManager` is constructed with `queue: .main`,
/// `CLLocationManager` calls back on the queue it was created on, the association poll timer runs on
/// the main run loop, and ``AssociationObservationProvider`` explicitly hops `fetchCurrent`'s
/// completion to main because that one API does not document its queue. A new provider that calls in
/// from elsewhere must hop too -- appending to an array from two queues corrupts it rather than
/// merely racing.
public final class CollectionSession: ObservationSink {

    public let sessionId: String
    public let profile: ScanProfile
    public private(set) var startedAtMillis: Int64
    public private(set) var endedAtMillis: Int64?

    private let store: ObservationStore
    private let observer: ObserverIdentity
    private let now: () -> Int64

    private var counts: [SensorType: Int64] = [:]
    private var recorded: Int64 = 0
    private var duplicates: Int64 = 0
    private var dropped: Int64 = 0
    private var degradations: [String] = []
    private var degradationDetails: [String: String] = [:]
    private var storeFailures: Int64 = 0

    /// Most recent rows, for the live dashboard. Bounded, because the dashboard shows a window and
    /// an unbounded buffer would grow for the length of a survey.
    public private(set) var recentObservations: [Observation] = []
    private let recentLimit = 200

    /// Called after each change so a UI can refresh. Invoked on whichever queue the provider was
    /// on; the view model hops to the main actor.
    public var onChange: (() -> Void)?

    public init(
        sessionId: String,
        profile: ScanProfile,
        store: ObservationStore,
        observer: ObserverIdentity,
        now: @escaping () -> Int64
    ) {
        self.sessionId = sessionId
        self.profile = profile
        self.store = store
        self.observer = observer
        self.now = now
        self.startedAtMillis = now()

        // Written at once, before any row arrives, so a session killed by the system still has a
        // record that it existed and when. The alternative -- writing it at stop -- loses exactly
        // the sessions whose loss most needs explaining.
        try? store.upsertSession(summary())
        recordStructuralLimits()
    }

    /// The limits that are not faults and will never resolve: iOS has no Wi-Fi scanning API and no
    /// public RTT. Recorded once at the start of every session so a package is self-describing
    /// without a reader having to know which platform produced it.
    private func recordStructuralLimits() {
        appendDegradation(
            Degradation.wifiScanUnsupported,
            detail: "iOS has no Wi-Fi scanning API for third-party apps; only the joined network "
                + "is observable"
        )
        appendDegradation(
            Degradation.rttUnsupported,
            detail: "no public 802.11mc API exists on iOS"
        )
        if profile.requiresServiceFilter {
            appendDegradation(
                Degradation.backgroundRequiresServiceFilter,
                detail: "unfiltered BLE scanning is a foreground activity on iOS"
            )
        }
    }

    // MARK: - ObservationSink

    public func record(_ observation: Observation) {
        do {
            if try store.append(observation) {
                recorded += 1
                counts[observation.sensorType, default: 0] += 1
                recentObservations.append(observation)
                if recentObservations.count > recentLimit {
                    recentObservations.removeFirst(recentObservations.count - recentLimit)
                }
            } else {
                duplicates += 1
            }
        } catch {
            // A failed write is a lost observation, counted as such rather than swallowed.
            storeFailures += 1
            dropped += 1
        }
        persistAndNotify()
    }

    public func recordDegradation(_ reason: String, detail: String?) {
        appendDegradation(reason, detail: detail)
        persistAndNotify()
    }

    public func recordDropped(count: Int, reason: String) {
        dropped += Int64(count)
        appendDegradation(reason, detail: nil)
        persistAndNotify()
    }

    private func appendDegradation(_ reason: String, detail: String?) {
        if !degradations.contains(reason) {
            degradations.append(reason)
        }
        if let detail, degradationDetails[reason] == nil {
            degradationDetails[reason] = detail
        }
    }

    private func persistAndNotify() {
        try? store.upsertSession(summary())
        onChange?()
    }

    // MARK: - Lifecycle

    public func finish() {
        endedAtMillis = now()
        try? store.upsertSession(summary())
        onChange?()
    }

    public func summary() -> SessionSummary {
        SessionSummary(
            sessionId: sessionId,
            startedAt: Iso8601.format(startedAtMillis),
            endedAt: endedAtMillis.map(Iso8601.format),
            scanProfile: profile.rawValue,
            buildingId: observer.buildingId,
            zoneId: observer.defaultZoneId,
            observationCount: recorded,
            // Association rows are Wi-Fi rows. iOS simply cannot produce the scanning kind, which
            // is what `degradations` says.
            wifiCount: (counts[.wifiAssociation] ?? 0) + (counts[.wifiScan] ?? 0),
            bleCount: counts[.ble] ?? 0,
            rttCount: counts[.rtt] ?? 0,
            gpsCount: counts[.gps] ?? 0,
            droppedSamples: dropped,
            // iOS throttles background BLE without telling the app how much, so this stays zero
            // rather than carrying a guess. The `degradations` entry is the honest statement.
            throttledScanRequests: 0,
            backgroundDenied: profile.requiresServiceFilter,
            suspectedServiceKill: false,
            batteryStartPct: batteryStartPct,
            batteryEndPct: batteryEndPct,
            degradations: degradations
        )
    }

    public var duplicatesRejected: Int64 { duplicates }
    public var droppedSamples: Int64 { dropped }
    public var observationsRecorded: Int64 { recorded }
    public var countsBySensor: [SensorType: Int64] { counts }

    /// Explanations for the session's degradations, for the dashboard. A bare code tells an operator
    /// nothing they can act on.
    public var degradationExplanations: [(reason: String, detail: String?)] {
        degradations.map { ($0, degradationDetails[$0]) }
    }

    public var batteryStartPct: Int?
    public var batteryEndPct: Int?
}
