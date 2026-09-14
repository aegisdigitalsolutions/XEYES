# Deliverable A — Repository and module architecture

## 1. Design constraints that drive the module graph

1. **Algorithms must be replaceable** (master prompt §26). Every engine sits behind an interface, and
   the interface lives in a module that does not depend on any implementation.
2. **Radio APIs must stay native** (§3). No shared-code abstraction may leak an Android or Apple type.
3. **The Collector must build and run with no knowledge of the Master** and vice versa. They
   communicate only through the export package.
4. **Core logic must be testable without a device or an emulator.** Anything that can be pure JVM
   Kotlin is pure JVM Kotlin, so that `./gradlew test` proves the parsing, validation, export,
   checksum and deduplication logic without an Android SDK.

Constraint 4 is the reason the module list is longer than a typical Android app. Roughly 70% of the
code that actually determines data correctness lives in modules with zero Android dependencies.

## 2. Module graph

```
                     ┌────────────────┐
                     │   core-model   │  pure JVM (kotlin-jvm)
                     │  Observation,  │  no Android, no Room, no I/O
                     │  enums, codecs │
                     └───┬────────┬───┘
              ┌──────────┘        └──────────┐
     ┌────────▼───────┐              ┌───────▼────────┐
     │  core-export   │              │  core-import   │  pure JVM
     │ package writer │              │ validate+dedup │
     │ manifest, hash │              │ report         │
     └────────┬───────┘              └───────┬────────┘
              │        ┌────────────┐        │
              │        │ core-radio │        │       pure JVM
              │        │ provider   │        │       interfaces only
              │        │ interfaces │        │
              │        └──────┬─────┘        │
              │               │              │
              │        ┌──────▼───────┐      │
              │        │radio-android │      │       android-library
              │        │ WifiManager, │      │       the ONLY module that
              │        │ BLE, RTT, GPS│      │       touches radio APIs
              │        └──────┬───────┘      │
              │               │              │
              │        ┌──────▼───────┐      │
              └────────►  data-room   ◄──────┘       android-library
                       │ entities,DAO │
                       │  migrations  │
                       └───┬──────┬───┘
                  ┌────────┘      └────────┐
        ┌─────────▼──────┐       ┌─────────▼──────┐
        │ collector-app  │       │  master-app    │  android-application
        │  Milestone 1   │       │  Milestone 2+  │
        └────────────────┘       └────────────────┘

        positioning-lab/  (Python, separate build, consumes schema/ only)
```

### Dependency rules (enforced in review; violations are build-breaking by construction)

| Rule | Rationale |
|---|---|
| `core-*` modules must not depend on Android | Keeps correctness logic unit-testable on the JVM |
| `core-model` depends on nothing but kotlinx-serialization | It is the contract; it must stay stable |
| Only `radio-android` may import `android.net.wifi`, `android.bluetooth`, `android.location` | Single choke point for permission handling and platform quirks |
| App modules must not implement algorithms | They wire, present, and request permissions |
| `positioning-lab` must not read an Android database | It consumes export packages, nothing else |

## 3. Module responsibilities

### `core-model` (pure JVM)
The contract, and nothing else.

- `Observation` and every enum (`SensorType`, `IdentifierType`, `ObserverDeviceType`, `DeviceStatus`,
  `InfrastructureType`, `MovementState`, `PrecisionTier`).
- `ObserverIdentity`, `ManagedDevice`, `InfrastructureNode`, `Building`, `Zone`, `SurveyPoint`,
  `FingerprintPoint`, `PositionEstimate`, `ZoneTransition`, `MovementEstimate`.
- `ObservationCsvCodec` — the single authority for the CSV column order and cell encoding.
- `ObservationJsonCodec` — kotlinx-serialization wiring, canonical field naming.
- `RadioIdentifierNormalizer` — lowercase colon-separated MAC, hex normalization. Must be
  deterministic; deduplication depends on it.
- `SchemaVersion` — the single source of truth for `schema_version` and its compatibility rules.
- `ObservationValidator` — field-level validation shared by export and import so that a package that
  passes on write cannot fail on read.

Invariants live in `init` blocks. An `Observation` that violates the schema cannot be constructed.

### `core-export` (pure JVM)
- `ExportPackageWriter` — writes `manifest.json`, `observations.csv`, `observations.json`,
  `observer.json`, `checksum.txt` into a zip via an injected `ExportSink`.
- `Sha256` / `ChecksumFile` — deterministic `sha256  filename` lines, sorted by filename.
- `ExportEngine` interface + `ExportEngineV1`.
- No `java.io.File` in the interface: the Android layer supplies a `SAF`-backed sink, tests supply an
  in-memory sink.

### `core-import` (pure JVM)
- `ImportEngine` interface + `ImportEngineV1`.
- `PackageValidator` — schema version, observer id, required columns, JSON validity, timestamp
  sanity, checksum verification, manifest/content agreement.
- `DeduplicationPlanner` — given package observation ids and an `ExistingIdLookup`, produces the
  `ImportPreview` (`new`, `duplicate`, `invalid` counts plus per-row reasons) that the Master shows
  before the administrator commits.
- Importing the same package twice produces zero new rows. This is a unit test, not a hope.

### `core-radio` (pure JVM)
Interfaces only, so both platforms and the fake providers used by tests implement the same thing:

```kotlin
interface WifiObservationProvider  { fun observations(): Flow<RadioSample>;  suspend fun capability(): Capability }
interface BleObservationProvider   { fun observations(): Flow<RadioSample>;  suspend fun capability(): Capability }
interface RttObservationProvider   { suspend fun range(targets: List<RttTarget>): List<RadioSample> }
interface LocationProvider         { fun locations(): Flow<LocationSample> }
interface SensorProvider           { suspend fun snapshot(): Map<String, String> }
```

`RadioSample` is a platform-neutral carrier. The mapping `RadioSample -> Observation` happens once,
in `core-radio`'s `ObservationFactory`, so observer id / session id / schema version stamping cannot
be forgotten by a platform implementation.

### `radio-android` (android-library)
The only module allowed to see radio APIs.

- `AndroidWifiObservationProvider` — registers `SCAN_RESULTS_AVAILABLE_ACTION`, respects throttling
  with a token-bucket scheduler, marks results `FRESH` or `CACHED` from
  `ScanResult.timestamp`.
- `AndroidBleObservationProvider` — `BluetoothLeScanner` with configurable `ScanSettings` and
  optional filters for enrolled devices.
- `AndroidRttObservationProvider` — dynamic `FEATURE_WIFI_RTT` detection; absent hardware degrades
  to "unsupported", never to an error.
- `AndroidLocationProvider`, `AndroidSensorProvider`.
- `RadioPermissions` — the runtime permission matrix from deliverable E, expressed as code.

### `data-room` (android-library)
- Entities for the RAW, REFERENCE and DERIVED layers in one database with separate table groups.
- `ObservationDao` deliberately exposes **no** update or delete for raw observations.
- Explicit migrations from version 1. No destructive fallback in release builds.

### `collector-app` / `master-app` (android-application)
Compose UI, ViewModels, DI wiring, foreground service, permission flows, SAF file pickers.

## 4. Why a separate Python Positioning Lab rather than on-device math

The architecture brief asks for three components, and the split is also the right engineering call:

- Positioning needs to be **re-runnable over history** with a new algorithm version. That is a batch
  workload, not a phone workload.
- Validation needs train/validation/test splits, benchmark tables and statistical libraries.
- Keeping inference off the collection device makes it structurally impossible for a derived estimate
  to contaminate a raw observation.

The Master remains the system of record: it exports `RAW + REFERENCE`, the Lab returns a
`DERIVED_*.zip`, and the Master imports it for visualization. The Lab never writes to the Master's
database.

## 5. Naming and versioning

- Module names are lowercase, hyphenated, and describe layer before platform (`core-export`,
  `radio-android`).
- `schema_version` (data contract) is versioned independently from `app_version` and from each engine
  version (`fingerprint_engine`, `zone_engine`, `movement_engine`). See deliverable 9.
- Kotlin packages: `com.rfmapper.<module>`, e.g. `com.rfmapper.core.model`.
- Python package: `rfmapper_lab`, with one subpackage per pipeline phase.
