# Deliverable E — Android permission matrix by version

Encoded in `radio-android/.../RadioPermissions.kt`. The table below is the specification; the code is
generated from the same decision tree and unit-tested per API level.

`minSdk = 26` (Android 8.0) · `targetSdk = 35` (Android 15) · `compileSdk = 35`

`minSdk 26` is chosen because `BluetoothLeScanner` with `ScanSettings.CALLBACK_TYPE_ALL_MATCHES` on a
background-capable service, and `ScanResult.getTimestampNanos()` (needed for fresh-vs-cached
determination), are both dependable from 26 onward.

## 1. Manifest permissions

| Permission | `maxSdkVersion` | `minSdkVersion` | Needed for |
|---|---|---|---|
| `ACCESS_WIFI_STATE` | — | — | Enumerate scan results |
| `CHANGE_WIFI_STATE` | — | — | `WifiManager.startScan()` |
| `ACCESS_FINE_LOCATION` | — | — | Wi-Fi scan results and BLE results are location-derived |
| `ACCESS_COARSE_LOCATION` | — | — | Companion to fine; required to request fine on 31+ |
| `ACCESS_BACKGROUND_LOCATION` | — | 29 | Scanning while the app is not in the foreground |
| `BLUETOOTH` | 30 | — | Legacy BLE |
| `BLUETOOTH_ADMIN` | 30 | — | Legacy BLE scan start |
| `BLUETOOTH_SCAN` | — | 31 | BLE scanning (runtime, `neverForLocation` **not** used — see §4) |
| `BLUETOOTH_CONNECT` | — | 31 | Reading the local adapter name/state |
| `NEARBY_WIFI_DEVICES` | — | 33 | Wi-Fi scanning without location intent on 33+ |
| `FOREGROUND_SERVICE` | — | 28 | Long collection sessions |
| `FOREGROUND_SERVICE_LOCATION` | — | 34 | Required subtype declaration on 34+ |
| `POST_NOTIFICATIONS` | — | 33 | The mandatory foreground-service notification |
| `RECEIVE_BOOT_COMPLETED` | — | — | Optional: resume a session after reboot (off by default) |
| `WAKE_LOCK` | — | — | Partial wake lock during an active session |

Manifest features, all `required="false"` so a device without the hardware can still install and act
as a partial observer:

```xml
<uses-feature android:name="android.hardware.wifi"          android:required="false" />
<uses-feature android:name="android.hardware.wifi.rtt"      android:required="false" />
<uses-feature android:name="android.hardware.bluetooth_le"  android:required="false" />
<uses-feature android:name="android.hardware.location.gps"  android:required="false" />
```

## 2. Runtime permission requirements by API level

| Capability | 26–27 | 28 | 29–30 | 31–32 | 33 | 34–35 |
|---|---|---|---|---|---|---|
| **Wi-Fi scan results** | FINE_LOCATION + Location Services on | same | same | same | FINE_LOCATION **or** NEARBY_WIFI_DEVICES | same as 33 |
| **`startScan()` succeeds** | CHANGE_WIFI_STATE | same | + throttling enforced (§3) | same | same | same |
| **BLE scan** | FINE_LOCATION + BLUETOOTH_ADMIN | same | same | BLUETOOTH_SCAN | same | same |
| **BLE local adapter info** | BLUETOOTH | same | same | BLUETOOTH_CONNECT | same | same |
| **Wi-Fi RTT** | n/a (API 28+) | FINE_LOCATION + `FEATURE_WIFI_RTT` | same | + NEARBY_WIFI_DEVICES accepted | same | same |
| **GNSS location** | FINE_LOCATION | same | same | same | same | same |
| **Background collection** | foreground service | + `FOREGROUND_SERVICE` | + `ACCESS_BACKGROUND_LOCATION` | same | + `POST_NOTIFICATIONS` | + `FOREGROUND_SERVICE_LOCATION` + `foregroundServiceType="location"` |
| **Connected network SSID/BSSID** | FINE_LOCATION | same | same | same | same | same |

### Things that are easy to get wrong and are therefore asserted in code

- **Location Services must be *enabled*, not merely granted.** With FINE_LOCATION granted but the
  device location toggle off, `getScanResults()` returns an empty list with no error. The Collector
  checks `LocationManager.isLocationEnabled` and surfaces
  `CollectionBlockReason.LOCATION_SERVICES_OFF` instead of quietly recording nothing.
- **`ACCESS_BACKGROUND_LOCATION` cannot be requested in the same dialog as foreground location.** It
  must be a second request, after foreground is granted, and on 30+ it opens Settings rather than a
  dialog. The onboarding flow sequences this explicitly.
- **On 31+, `BLUETOOTH_SCAN` without `neverForLocation` still requires location permission.** See §4.
- **`POST_NOTIFICATIONS` denial does not stop a foreground service**, but it hides the notification,
  which makes a long session look like it stopped. The UI warns rather than assuming failure.

## 3. Wi-Fi scan throttling

| API level | Foreground app | Background |
|---|---|---|
| ≤ 27 | unthrottled in practice | unthrottled in practice |
| 28 | 4 `startScan()` calls per 2 minutes | 1 per 30 minutes |
| 29+ | 4 calls per 2 minutes | 1 per 30 minutes |
| 30+ | as 29, and developer options expose a global throttle toggle | as 29 |

Implementation (`WifiScanScheduler`):

- A **token bucket** of 4 tokens refilling over a 2-minute window, so the app never exceeds the quota
  and therefore never receives a silent `startScan()` rejection.
- Collection is driven by **`SCAN_RESULTS_AVAILABLE_ACTION`**, not by a polling loop. Scans initiated
  by *other* apps and by the system's own connectivity logic deliver results to us for free, which
  in practice yields a higher effective sample rate than hammering `startScan()` ever would.
- `EXTRA_RESULTS_UPDATED` distinguishes a genuine new scan from a stale-cache broadcast.
- Every result is stamped `result_freshness` = `FRESH` when
  `SystemClock.elapsedRealtime() - ScanResult.timestamp/1000 <= freshnessWindowMs` (default 5000),
  otherwise `CACHED`, and `scan_result_age_ms` records the measured age.
- If `startScan()` returns `false`, `metadata.throttled=true` is recorded on the next batch. A
  throttled request is a data-quality fact, not an error to swallow.

Recording freshness per row is what lets the Lab weight samples honestly: on Android 29+ a
background observer may legitimately be reporting the same cached scan for 30 minutes, and a
fingerprint built from 60 copies of one scan is not a 60-sample fingerprint. The Lab's
`sample_count` deduplicates on `(radio_identifier, scan_result_age_ms, rssi)` for exactly this
reason.

## 4. `neverForLocation` decision

`android:usesPermissionFlags="neverForLocation"` on `BLUETOOTH_SCAN` would let the Collector drop
location permission for BLE. **It is deliberately not used**, because:

1. The system strips location-derivable data from scan results when the flag is set, and BLE RSSI from
   fixed anchors *is* the positioning evidence.
2. The app genuinely does use BLE results to derive approximate location, so declaring otherwise would
   be a false statement in the manifest.

This is a considered trade-off, not an oversight: RFMapper is a location system and declares itself
as one.

## 5. Degradation behaviour

No permission denial crashes or silently produces empty data. Each provider reports a `Capability`:

```kotlin
data class Capability(
    val supported: Boolean,          // hardware/OS can do this at all
    val permitted: Boolean,          // permissions currently granted
    val enabled: Boolean,            // radio + services actually switched on
    val degradations: Set<Degradation>,
    val missingPermissions: List<String>,
)
```

| Situation | Behaviour |
|---|---|
| FINE_LOCATION denied | Wi-Fi and BLE providers report `permitted=false`. Dashboard shows an actionable blocker. No observations are fabricated. |
| Background location denied | Collection runs foreground-only; the session row records `background_denied=true` so gaps in coverage are explainable later. |
| Bluetooth adapter off | BLE `enabled=false`; Wi-Fi collection continues. |
| Wi-Fi off | Wi-Fi `enabled=false`; BLE continues. |
| `FEATURE_WIFI_RTT` absent | RTT `supported=false`. Never an error — RTT is optional by design. |
| RTT present but ranging fails | `rtt_status` recorded; **no RSSI-derived distance is substituted**. |
| Any degradation active | Affected observations carry `permission_degraded=true` and `missing_permissions` |

## 6. Permission request sequencing (Collector onboarding)

1. Explain the purpose on-screen **before** any system dialog (required by policy and it materially
   improves grant rates).
2. Request `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` together.
3. On 31+, request `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`.
4. On 33+, request `NEARBY_WIFI_DEVICES` and `POST_NOTIFICATIONS`.
5. Only after foreground location is granted, explain and then request
   `ACCESS_BACKGROUND_LOCATION` (Settings redirect on 30+).
6. Verify Location Services and the Bluetooth adapter are *enabled*; offer the relevant settings
   intent for each.
7. Show the resulting capability matrix so the field engineer can see exactly which sensors are live
   before starting a session.

Step 7 exists because the most expensive failure in this system is discovering at the end of a
six-hour session that BLE was off.

## 7. Battery, Doze and long sessions

- Collection runs in a **foreground service** with `foregroundServiceType="location"` (34+ requires
  the subtype).
- A `PARTIAL_WAKE_LOCK` is held only while a session is active, and released on stop.
- The app requests exemption from battery optimization **as an explicit, explained, optional step**;
  without it, Doze will suppress scan cadence during long idle periods, and the session summary says
  so rather than leaving an unexplained gap.
- `WorkManager` handles the deferrable work (daily export assembly, integrity verification,
  housekeeping) — not collection, which must not be deferred.
- Scan cadence is configurable per session (`ScanProfile`: `AGGRESSIVE`, `BALANCED`, `ENDURANCE`) so a
  full-day survey is achievable on a phone battery. The chosen profile is recorded on the session, so
  sampling rate is never a mystery after the fact.
