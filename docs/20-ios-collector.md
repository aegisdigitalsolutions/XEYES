# Deliverable 20 — The iOS Collector

[`06-ios-capability-matrix.md`](06-ios-capability-matrix.md) set out what iOS can and cannot do
before any Swift existed. This document describes what was built against that matrix, and — more
usefully — the places where building it changed something elsewhere in the system.

**The shape.** A SwiftUI app over a Swift Package Manager package. The package holds the model, the
export format and the capture layer; the app holds views and wiring and nothing worth testing. The
package builds and its tests run on Linux, which is deliberate: the contract tests must be runnable
by anyone, not only by whoever has a Mac.

## 1. Layout

| Path | Contents |
|---|---|
| `ios/Packages/RFMapper/Sources/RFMapperCore` | Observation model, CSV codec, canonical JSON, manifest, ISO-8601, SHA-256, zip writer |
| `ios/Packages/RFMapper/Sources/RFMapperCollectorKit` | Providers, `ObservationFactory`, `ObservationStore`, `CollectionSession`, `PackageExporter` |
| `ios/Packages/RFMapper/Tests` | Unit tests plus the contract fixture test |
| `ios/RFMapperCollector` | The app: views, `Info.plist`, entitlements, privacy manifest, assets |
| `ios/project.yml` | The XcodeGen spec |
| `ios/RFMapperCollector.xcodeproj` | Generated from that spec, committed so the repo opens in Xcode directly |

The `.xcodeproj` is generated, not authored. Regenerate it with `xcodegen generate` from `ios/`
rather than repairing it by hand; `project.yml` is the file under review.

## 2. The export must be byte-identical, not merely valid

A package written by the Swift Collector has to be the same bytes Kotlin would have written from the
same observations. Equivalence is not enough: the manifest carries a SHA-256 of each entry, and an
import that recomputes a different digest cannot tell a formatting difference from a corrupted file.

Four things had to be written rather than imported to get there.

| Component | Why not the platform's |
|---|---|
| `Sha256` | CryptoKit does not exist on Linux, and these tests must run there |
| `ZipWriter` | Foundation's archiving embeds the current time, so two runs over identical data differ |
| `CanonicalNumber` | Swift and Java disagree about how to print a `Double`, and the difference lands in the CSV |
| `MiniJSON` | A Foundation-free parser for the string-to-string metadata map |

`CanonicalNumber` is the one that looks like over-engineering and is not. Java's `Double.toString`
emits the shortest decimal that round-trips, with its own rules about exponent form; Swift's
`description` does something similar but not the same. A confidence of `0.85` is safe either way, an
accuracy of `1e-7` is not.

## 3. What the platform cannot do, recorded rather than omitted

The observer declares `capabilities: [BLE, GPS, WIFI_ASSOCIATION]` and
`unsupported: [WIFI_SCAN, RTT]`. The second set is the load-bearing one. An observer that reports no
Wi-Fi scan rows and does not say it cannot produce them is indistinguishable from a site with no
access points, and the Lab would read that silence as evidence of absence — driving every
fingerprint's Wi-Fi visibility probability toward zero. The app pre-fills both sets rather than
leaving `unsupported` to the operator, because it is exactly the field that gets left empty.

The same principle governs individual rows:

| Reading | How it is recorded | Why |
|---|---|---|
| BLE peripheral | `identifier_type: OTHER`, `metadata.identifier_scope: APP_INSTALL` | `CBPeripheral.identifier` is scoped to this app install. Typing it as a MAC would invite a reader to join it across observers |
| Wi-Fi association | `rssi` null, `metadata.permission_degraded: true`, `missing_permissions` naming the reason | iOS does not expose the associated AP's signal strength. `NEHotspotNetwork.signalStrength` is a bar-level value documented as unspecified; mapping it to dBm would be inventing a measurement |
| Reduced-accuracy location | `permission_degraded` with `LOCATION_REDUCED_ACCURACY` | iOS 14's approximate mode is good to kilometres — usable for "which site", useless for anything finer |

## 4. Two things this surfaced elsewhere

Neither was an iOS bug. Both were existing defects that only became visible once a second platform
had to interoperate with the first.

### A short Bluetooth UUID was unjoinable

`CBUUID.uuidString` returns the SIG shorthand for an assigned service — a heart-rate service is
`180D`. Android's `ParcelUuid.toString()` always returns the 128-bit form. The normalizer rejected
four hex digits as not a UUID, so an iOS sighting of a tag advertising a standard service recorded
**no service UUID at all** and could not be joined to the Android sighting of the same hardware.

Since §3 of the capability matrix names the service UUID as the only cross-platform join key iOS
has, this quietly removed the one mechanism the document depends on.
`RadioIdentifierNormalizer.bluetoothUuid` now expands 16- and 32-bit forms against the Bluetooth
Base UUID. It also strips a `0x` prefix, because operator-typed UUIDs reach the same function and
`0x180D` would otherwise be counted as five hex digits and match nothing.

### The Master was not applying its own attribution policy

[`17-identity-and-attribution-policy.md`](17-identity-and-attribution-policy.md) §5 lists four
things that happen when a package is imported. One of them happened. `ATTRIBUTION_DISAGREEMENT` was
a declared error code with no code path capable of emitting it, so a Collector's stale claim was
overwritten in silence and no administrator could see it.

Attribution also matched on `radio_identifier` alone. That excluded the `SERVICE_UUID` rule the same
document calls recommended — and because CoreBluetooth never discloses a peripheral's address, an
iOS row's identifier is an app-install-scoped UUID that no Master registry can hold. Every BLE row
from every iPhone was unattributable, whatever the tag advertised.

`PackageImporter` now re-evaluates every row against the Master's registry on both keys, at preview
time as well as commit, preserves a differing claim in `metadata.collector_claimed_device_id`, and
raises the disagreement as an advisory rather than a block. A randomized address is still never
matched *on the address*: a row carrying one that also advertises an enrolled service UUID is
attributed on the service UUID, which is what "survives MAC randomization" was always supposed to
mean.

## 5. Scan profiles

| Profile | Behaviour |
|---|---|
| `IOS_FOREGROUND_SURVEY` | Continuous, duplicates allowed. Screen on, mains power preferred |
| `IOS_FOREGROUND_ECONOMY` | Duty-cycled, for a longer supervised session on battery |
| `IOS_BACKGROUND_FILTERED` | Service-filtered only — all the system permits once the app is not in front |

The background profile scans for nothing unless service UUIDs have been enrolled in the app, which
is not a limitation that can be engineered away: `withServices: nil` works in the foreground only.
This is why enrolling tags that advertise a fixed service UUID is the single procurement decision
that most affects what an iOS deployment can do.

`CollectionSession` counts throttled scan requests, dropped samples and suspected suspensions into
the session summary. On iOS these fields will actually get used, and a recorded gap is far more
valuable than a clean-looking file that quietly omits one.

## 6. How the contract is checked

The loop is closed in all three languages, against one fixture rather than three descriptions of it.

1. Swift's `ContractFixtureTests`, run with `RFMAPPER_WRITE_CONTRACT=1`, writes
   `contract/RFMapper_OBSI1_2026-05-04.zip` from the real export engine. Run without that variable
   — which is how CI runs it — it asserts the committed bytes still match.
2. Kotlin's `IosPackageContractTest` imports that file through the Master's real importer and pins
   the iOS-specific parts: null association RSSI with metadata explaining it, `OTHER`/`APP_INSTALL`
   peripheral identity, `WIFI_SCAN` and `RTT` surviving as declared-unsupported, a package from an
   unenrolled observer being refused, and a tag attributed by service UUID.
3. The Python Lab consumes the same package end to end.

The attribution assertion reads `commit.attributed` rather than the resulting `target_device_id`,
because in this fixture the Collector's claim and the Master's verdict agree — reading the field
alone would pass without the Master having done any work. The Kotlin attribution tests were checked
by reverting the importer fix and confirming they fail.

## 7. Not yet verified

The app has never been built or run on Apple hardware. This repository's CI is Linux, so there is no
`xcodebuild` and no simulator. What that leaves unexercised is worth stating plainly:

- the app target, the SwiftUI views, and every `#if canImport` branch that Linux does not compile —
  CoreBluetooth, CoreLocation, NetworkExtension, UIKit and the SQLite store;
- permission prompts, the `Access WiFi Information` entitlement, background modes, and signing;
- whether `NEHotspotNetwork.fetchCurrent` returns anything at all under the entitlement as
  configured.

The first Xcode build should be treated as the real one. What *is* verified is everything the
contract depends on: the model, the CSV and JSON encoders, the manifest, the checksums, the zip and
the observation factory's identity decisions, all tested on Linux and pinned by the fixture above.

[`21-ios-shipping-checklist.md`](21-ios-shipping-checklist.md) is the practical companion to this
section: what has to happen on a Mac, what has to happen in the Developer portal, and which
functional gaps remain.
