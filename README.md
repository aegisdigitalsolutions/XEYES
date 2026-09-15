# RFMapper

A private, multi-building RF observation and approximate-location system for a **closed set of
administrator-enrolled devices and administrator-controlled infrastructure**.

```
KNOWN MANAGED DEVICE
  -> RADIO OBSERVATIONS
  -> ZONE IDENTIFICATION
  -> APPROXIMATE LOCATION
  -> MOVEMENT HISTORY
  -> VISUAL SITE MAP
```

## Authorized use

RFMapper is built for a privately administered site. It observes devices that an administrator has
explicitly enrolled, plus infrastructure that the administrator owns. It is **not** a tool for
identifying arbitrary people or devices:

- Unknown and randomized radio identifiers are stored as *environmental RF observations only*.
- The software never automatically attributes a randomized identifier to a person or physical device.
  Attribution requires an explicit administrator rule or authorized-device information
  (see [`docs/17-identity-and-attribution-policy.md`](docs/17-identity-and-attribution-policy.md)).
- Every estimate carries uncertainty. The system prefers an honest "Building 9, zone unknown" over a
  fabricated coordinate.

## Three components

Three roles, on four codebases — the Collector exists for both Android and iOS, and the two are
held to the same file contract rather than merely to the same intent.

| Component | Path | Role |
|---|---|---|
| **Collector** | [`android/collector-app`](android/collector-app) | Field app. `SCAN -> RECORD -> STORE -> EXPORT`. Offline, no server. |
| **Collector (iOS)** | [`ios`](ios) | The same field app on iOS, within [what the platform permits](docs/06-ios-capability-matrix.md): BLE, association, GPS — never Wi-Fi scanning. [Doc](docs/20-ios-collector.md) |
| **Master** | [`android/master-app`](android/master-app) | Owner app. Observation database, registries, site model, import, survey, history. |
| **Positioning Lab** | [`positioning-lab`](positioning-lab) | Python statistical engine. RAW + REFERENCE -> DERIVED estimates. Never mutates RAW. [README](positioning-lab/README.md) |

The three components are coupled only by the **versioned file contract** in [`schema/`](schema).
Nothing shares a process, a database, or a network connection.

Because nothing shares a process, no test can check those contracts by calling one component from
another. What can be checked is the artefact: one side writes a file, the file is committed under
[`contract/`](contract), and the other side's suite reads it. The Kotlin suite writes the site
model, the device registry and the observation packages; the Swift suite writes an iOS observation
package; the Lab's suite reads them, runs the pipeline and writes the derived package; the Kotlin
suite imports both the iOS package and the derived one back. A format change that breaks a consumer
fails a test instead of failing in the field.

## Data layers

| Layer | Mutability | Contents |
|---|---|---|
| `RAW` | append-only, never edited | Normalized observations exactly as collected |
| `REFERENCE` | administrator-curated | Managed devices, infrastructure, buildings, zones, survey points, ground-truth fingerprints |
| `DERIVED` | fully regenerable | Position estimates, zone estimates, transitions, movement |

Any `DERIVED` result must be reproducible from `RAW + REFERENCE` alone. Swapping
`PositioningEngineV1` for `V2` and reprocessing history must never require recollecting `RAW`.

## Repository layout

```
docs/               First engineering deliverables (read these before the code)
schema/             The versioned cross-component contract (JSON Schema + CSV spec)
contract/           Committed fixtures each side writes and the other side's tests read
android/            Gradle multi-module build
  core-model/         Pure-JVM canonical model, codecs, validation  (no Android deps)
  core-export/        Pure-JVM export package writer + checksums
  core-import/        Pure-JVM import validation + deduplication
  core-radio/         Platform-neutral radio provider interfaces
  radio-android/       Android Wi-Fi / BLE / RTT / Location implementations
  data-room/          Room entities, DAOs, migrations
  collector-app/      Milestone 1 Collector (Compose)
  master-app/         Milestone 2 Master (Compose)
ios/                Swift package + SwiftUI Collector + generated Xcode project
  Packages/RFMapper/  RFMapperCore (model, codecs, export) + RFMapperCollectorKit (capture)
  RFMapperCollector/  The app; project.yml is the spec the .xcodeproj is generated from
positioning-lab/    Python package `rfmapper_lab` + CLI + benchmark harness
```

## Start here

1. [`docs/00-first-deliverables-index.md`](docs/00-first-deliverables-index.md) — index of every
   pre-code deliverable and its status.
2. [`docs/01-repository-architecture.md`](docs/01-repository-architecture.md) — module graph and
   dependency rules.
3. [`docs/02-observation-schema.md`](docs/02-observation-schema.md) — the canonical observation model.

## Build and test

```bash
# Pure-JVM core: model, codecs, export, import, dedup
cd android && ./gradlew :core-model:test :core-export:test :core-import:test

# Android apps (requires an Android SDK with platform 35)
cd android && ./gradlew :collector-app:assembleDebug :master-app:assembleDebug

# iOS core + capture kit. Runs on Linux too, which is the point: the contract tests
# must not need a Mac. The app itself needs Xcode.
cd ios/Packages/RFMapper && swift test

# Positioning Lab (see positioning-lab/README.md for the pipeline and the CLI)
cd positioning-lab && pip install -e '.[dev]' && pytest
python -m rfmapper_lab.cli demo --out /tmp/rfmapper-demo   # synthetic end-to-end pipeline
```

## Current status

| Milestone | State |
|---|---|
| 0 — First engineering deliverables | Complete |
| 1 — Android Collector | Implemented; **requires validation on real hardware** |
| 2 — Master data engine (registries, import, dedup, browser) | Implemented, including reference export for the Lab and derived import back |
| 3 — Survey / site map / fingerprint collection | Survey capture + fingerprint build implemented; interactive map drawing not started |
| 4 — Positioning Lab inference | Implemented (baselines, fusion, uncertainty, movement, benchmark harness) |
| 5 — Visualization | Not started |
| 6 — iOS | Collector implemented and contract-tested on Linux; **never built or run on Apple hardware** |
| 7 — Optional future work | Not started |

Accuracy numbers have **not** been measured on this site. The benchmark harness reports numbers for
the synthetic simulator only; see
[`docs/15-assumptions-requiring-validation.md`](docs/15-assumptions-requiring-validation.md).

Until a real survey has been collected and benchmarked, every coordinate the Lab emits carries the
`UNVALIDATED_UNCERTAINTY` flag and an error bar derived from geometry rather than measurement. That
is the intended state, not a gap to be papered over: `rfmapper-lab benchmark` on real ground truth
is what replaces it.

A site with no survey at all is also supported, and is a different proposition rather than a lesser
one. Where the only evidence is which access point a device is associated with — APs on the towers
and enclosures already on site — the answer is the name of a tower, with no coordinates and no
figure in metres. See
[`docs/19-association-only-deployment.md`](docs/19-association-only-deployment.md) for how to model
it, which classifier to select, and why the movement thresholds need setting before that deployment
will record anything moving.
