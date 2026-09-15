#if canImport(CoreLocation)
import CoreLocation
import Foundation
import RFMapperCore

/// GNSS capture via CoreLocation, where iOS is at parity with Android in practice.
///
/// Location permission does double duty on iOS and it is worth knowing why this provider matters
/// even on an indoor site: iOS will not disclose the current Wi-Fi network's BSSID unless location
/// access has been granted. So the association provider depends on this one having been authorised,
/// even though the two capture different things.
///
/// Two cases are recorded rather than smoothed over. Reduced accuracy -- iOS 14's approximate
/// location, good to kilometres -- is tagged on every row it produces, because a fix that coarse is
/// usable for "which site" and useless for anything finer. And a denied authorisation is recorded as
/// a degradation, so the absence of GNSS rows has a stated cause.
public final class LocationObservationProvider: NSObject, ObservationProvider {

    public let capability: ObserverCapability = .gps

    private let manager = CLLocationManager()
    private let factory: ObservationFactory
    private weak var sink: ObservationSink?
    private var sessionId: String

    /// Minimum gap between recorded fixes. CoreLocation delivers far more often than a site survey
    /// needs, and a GNSS row per second would bloat a package without improving anything.
    private let minimumIntervalMillis: Int64
    private var lastRecorded: Int64?
    private var hasReportedDenied = false

    public init(
        factory: ObservationFactory,
        sink: ObservationSink,
        sessionId: String,
        minimumIntervalMillis: Int64 = 30_000
    ) {
        self.factory = factory
        self.sink = sink
        self.sessionId = sessionId
        self.minimumIntervalMillis = minimumIntervalMillis
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
    }

    public var isAvailable: Bool {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: return true
        default: return false
        }
    }

    public func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            reportDenied()
        default:
            manager.startUpdatingLocation()
        }
    }

    public func stop() {
        manager.stopUpdatingLocation()
    }

    public func reconfigure(sessionId: String) {
        self.sessionId = sessionId
        lastRecorded = nil
    }

    private func reportDenied() {
        guard !hasReportedDenied else { return }
        hasReportedDenied = true
        sink?.recordDegradation(
            Degradation.locationDenied,
            detail: "location access was declined. No GNSS rows, and iOS will also withhold the "
                + "associated network's BSSID, so Wi-Fi association capture stops too."
        )
    }
}

extension LocationObservationProvider: CLLocationManagerDelegate {

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            if manager.accuracyAuthorization == .reducedAccuracy {
                sink?.recordDegradation(
                    Degradation.locationReducedAccuracy,
                    detail: "precise location is off; fixes are accurate to kilometres and are "
                        + "tagged as degraded"
                )
            }
            manager.startUpdatingLocation()
        case .denied, .restricted:
            reportDenied()
        case .notDetermined:
            break
        @unknown default:
            break
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let location = locations.last else { return }

        // A negative horizontal accuracy is CoreLocation's way of saying the fix is invalid. It is
        // a sentinel, not a measurement, and the schema forbids a negative accuracy anyway.
        guard location.horizontalAccuracy >= 0 else { return }

        let now = factory.now()
        if let last = lastRecorded, now - last < minimumIntervalMillis { return }
        lastRecorded = now

        let reduced = manager.accuracyAuthorization == .reducedAccuracy
        do {
            let observation = try factory.location(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                horizontalAccuracy: location.horizontalAccuracy,
                sessionId: sessionId,
                reducedAccuracy: reduced,
                // Trust falls as the reported accuracy worsens. A 5 m fix and a 500 m fix are not
                // the same evidence and should not arrive looking the same.
                confidence: Self.confidence(forAccuracy: location.horizontalAccuracy, reduced: reduced)
            )
            sink?.record(observation)
        } catch {
            sink?.recordDropped(count: 1, reason: "GNSS_ROW_REJECTED")
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .denied {
            reportDenied()
        }
    }

    static func confidence(forAccuracy accuracy: Double, reduced: Bool) -> Double {
        if reduced { return 0.2 }
        // 1.0 at 5 m or better, decaying to 0.2 by 100 m.
        let clamped = max(5.0, min(100.0, accuracy))
        return max(0.2, min(1.0, 1.0 - (clamped - 5.0) / 95.0 * 0.8))
    }
}
#endif
