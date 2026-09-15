# Deliverable F — iOS / iPadOS capability and limitation matrix

Milestone 6. This document exists to prevent a design that assumes Android parity, which both
specifications explicitly warn against. It describes the platform; the Collector built against it is
[`20-ios-collector.md`](20-ios-collector.md).

**Bottom line: iOS cannot be a Wi-Fi observer.** An iOS Collector is a *BLE* observer plus a Master
viewing/administration client. Any plan that budgets iOS Wi-Fi scanning is wrong.

## 1. Capability matrix

| Capability | iOS status | API | Notes |
|---|---|---|---|
| **General Wi-Fi scanning (all nearby APs)** | **Unavailable** | — | No public API. Withdrawn with `Apple80211` in iOS 9. `NEHotspotHelper` requires a special entitlement granted only to hotspot-provisioning apps, and even then returns limited data during a narrow lifecycle window. Treat as unavailable. |
| Connected network SSID/BSSID | Restricted | `CNCopyCurrentNetworkInfo` (deprecated), `NEHotspotNetwork.fetchCurrent` (iOS 14+) | Needs *one* of: `Access WiFi Information` entitlement + location permission, an active VPN/HotspotHelper config, or NEHotspotConfiguration ownership of the network. Yields exactly one network — the one we are joined to. Usable as a `WIFI_ASSOCIATION` observation only. |
| Wi-Fi RSSI of the connected AP | Unavailable publicly | — | Not exposed. Do not attempt to infer from `NEHotspotNetwork.signalStrength`, which is a coarse 0–1 bar-level value and is documented as unspecified. |
| **BLE scanning** | **Available** | `CoreBluetooth` `CBCentralManager` | The core of an iOS Collector. See §2. |
| BLE RSSI | Available | `CBCentralManager` advertisement callback | dBm, per advertisement. |
| BLE peripheral MAC address | **Unavailable** | — | iOS returns an app-and-install-scoped `CBPeripheral.identifier` UUID, not the hardware address. See §3 — this is the single biggest cross-platform data-model consequence. |
| BLE manufacturer data / service data / service UUIDs / TX power | Available | `CBAdvertisementData*` keys | `kCBAdvDataManufacturerData`, `kCBAdvDataServiceData`, `kCBAdvDataServiceUUIDs`, `kCBAdvDataTxPowerLevel`. |
| iBeacon (`CLBeacon`) ranging | Available | `CoreLocation` region monitoring | Requires knowing the beacon UUID in advance; gives `accuracy` (m) and `proximity`. Works in background including after termination, unlike raw BLE scanning. Useful for site-presence and zone events. |
| Wi-Fi RTT (802.11mc) | **Unavailable** | — | No public API on any iOS version. |
| UWB ranging | Available, constrained | `NearbyInteraction` | Requires U1/U2 hardware on *both* peers and a running app session on each. Not applicable to passive observation of enrolled devices. Candidate only for Milestone 7 with dedicated hardware. |
| GNSS location | Available | `CoreLocation` | Full parity with Android in practice. |
| Motion / sensors | Available | `CoreMotion` | Useful for stationary-vs-moving hints. |
| Background execution | Constrained | see §4 | The hard limitation for an all-day Collector. |
| Local persistence | Available | SQLite / Core Data / SwiftData | Full parity. |
| File export | Available | `UIDocumentPickerViewController`, Files app, share sheet | A `.zip` export package works fine. |

## 2. What an iOS Collector can actually do

`CBCentralManager.scanForPeripherals(withServices:options:)`:

- `CBCentralManagerScanOptionAllowDuplicatesKey = true` is **required** to receive repeated
  advertisements from the same peripheral, which is what a sequence of RSSI samples needs. Without
  it, one callback per peripheral per scan.
- `withServices: nil` (scan everything) works in the **foreground only**. In the background, a
  service-UUID filter is mandatory and `allowDuplicates` is ignored.
- Consequence: unfiltered environmental BLE collection is a **foreground, screen-on** activity on
  iOS. Background collection is possible only for enrolled devices that advertise a *known service
  UUID*.

This makes the practical iOS deployment shape: a supervised, foreground survey/verification tool, or
a mains-powered iPad acting as a fixed observer with the screen on and a known service filter.

## 3. The identifier problem, and how the schema already handles it

iOS gives `CBPeripheral.identifier`: a UUID that is stable for one peripheral *for this app install
on this device*, and different for a different app or a reinstall. It is not the hardware address, so
an observation from an iPhone and an observation of the same tag from an Android phone **cannot be
joined on `radio_identifier`**.

The canonical schema accommodates this without a special case:

| Field | iOS value |
|---|---|
| `radio_identifier` | the `CBPeripheral.identifier` UUID, lowercased |
| `identifier_type` | `OTHER` — it is neither a public nor a random MAC |
| `metadata.ios_peripheral_identifier` | the same UUID, explicitly labelled |
| `metadata.identifier_scope` | `APP_INSTALL` (Android BLE sets `GLOBAL`) |

`identifier_scope` is the field that matters. The Lab must never merge two identifiers of different
scope, and must never treat an `APP_INSTALL`-scoped identifier as a site-wide fingerprint key.

**Cross-platform joins must therefore go through advertisement *content*, not the address:**

1. Enrolled devices that advertise a stable **service UUID** join on `ble_service_uuid` — this is the
   recommended enrollment pattern, and the reason `known_service_uuids` exists on `ManagedDevice`.
2. Devices advertising stable **manufacturer data** (e.g. an iBeacon major/minor) join on that
   payload.
3. Everything else cannot be joined across platforms, and the system must not pretend otherwise.

Practical guidance for the administrator: enroll BLE tags that advertise a fixed service UUID. That
one procurement decision is worth more than any amount of identifier-matching cleverness.

## 4. Background execution limits

| Mode | Reality |
|---|---|
| `bluetooth-central` background mode | Scanning continues, but **only with a service-UUID filter**, at a system-throttled rate, with `allowDuplicates` ignored. |
| App suspended / terminated | Raw BLE scanning stops. `CLBeacon` region monitoring and significant-location-change **do** relaunch the app. |
| Background location (`Always`) | Available with justification; keeps the process alive more reliably but does not restore unfiltered BLE scanning. |
| Long foreground session | Works with the screen on; consider `isIdleTimerDisabled` and mains power. |

There is no iOS equivalent of Android's foreground service with a persistent notification, so a
multi-hour unattended iOS Collector is not achievable with public APIs. The honest deployment answer
is: Android for unattended collection, iOS for supervised survey work and for Master administration.

## 5. Required `Info.plist` keys

| Key | Purpose |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | BLE scanning (iOS 13+) |
| `NSLocationWhenInUseUsageDescription` | GNSS + network info |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | Background location, iBeacon monitoring |
| `UIBackgroundModes` = `bluetooth-central`, `location` | Background BLE and location |
| `com.apple.developer.networking.wifi-info` (entitlement) | `NEHotspotNetwork.fetchCurrent` |

## 6. Architectural consequences

1. **Do not create an `iOSWifiObservationProvider`.** The `core-radio` interface set is
   per-capability precisely so a platform can decline to implement one. iOS supplies
   `BleObservationProvider`, `LocationProvider` and `SensorProvider`, and that is the complete,
   correct set.
2. **`observer.json` must advertise capabilities.** An iOS observer declares
   `capabilities: ["BLE","GPS"]`, so the Lab does not interpret the absence of Wi-Fi observations
   from that observer as "no APs were visible there" — an absence of evidence versus evidence of
   absence distinction that would otherwise silently poison fingerprint visibility probabilities.
3. **Fingerprints are per-capability.** A fingerprint built from Android Wi-Fi + BLE cannot be matched
   against an iOS BLE-only vector without restricting the comparison to the shared radio sources.
   `FingerprintMatcher` takes an explicit source filter for this reason.
4. **RSSI calibration is per observer model.** iPhone BLE RSSI is not interchangeable with Pixel BLE
   RSSI; `ref_observer_calibration` already holds a per-observer offset, and it must be measured, not
   assumed.

## 7. Milestone 6 scope

**In scope**

- Swift / SwiftUI Collector: BLE scanning, observer identity, local persistence, live dashboard,
  export package byte-compatible with the Android Collector (validated by running the Kotlin
  `PackageValidator` against an iOS-produced zip in CI).
- Optional `WIFI_ASSOCIATION` observation of the connected network when the entitlement is present.
- Optional `CLBeacon` monitoring for enrolled beacons, giving background zone presence.
- Master viewing/administration interface: registries, observation browser, site map, history.

**Explicitly out of scope**

- Any form of general Wi-Fi scanning.
- Wi-Fi RTT.
- Unattended all-day background collection.
- Cross-platform identifier merging on BLE addresses.
