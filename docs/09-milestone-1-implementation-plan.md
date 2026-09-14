# Deliverable I — Milestone 1 implementation plan (Android Collector)

**Objective, stated exactly as the specification does:** *reliable, timestamped, reproducible,
exportable RF observations from real hardware.* Not positioning mathematics. Not visual polish.

## 1. Scope

**In scope**

| Item | Notes |
|---|---|
| Observer identity | `observer_id`, friendly name, building, default zone, device model, platform, app version, installation id |
| Wi-Fi scanner | Broadcast-driven, throttle-aware, fresh-vs-cached labelled |
| BLE scanner | `BluetoothLeScanner`, optional filters for enrolled devices |
| RTT (opportunistic) | Dynamic capability detection; never required |
| GNSS (optional) | Recorded when authorized |
| Room persistence | Batched writes, WAL, RAW layer only |
| Live dashboard | Counters, session duration, last-observation age, blockers |
| Foreground service | Long sessions, wake lock, notification with live counts |
| Permission onboarding | The sequenced flow from deliverable E, ending in a capability matrix |
| Export | `EXPORT TODAY` and `EXPORT SESSION`, producing the full package from deliverable 16 |
| Survey Mode capture | Timed ground-truth capture at a labelled point (`sample_kind=GROUND_TRUTH`) |

**Out of scope for Milestone 1** — positioning, fingerprint *matching*, site map, import, any
networking, iOS.

Survey capture is included in Milestone 1 even though the specification lists survey under
Milestone 3, for one reason: it is a *collection* feature, it reuses the identical write path, and
without it the very first field trip produces no ground truth. Fingerprint *construction and
matching* remain in Milestones 3 and 4 where they belong.

## 2. Build order

Each step lands as its own commit with tests, and each is independently verifiable.

| # | Step | Modules | Verified by |
|---|---|---|---|
| 1 | Gradle skeleton, version catalog, module graph | all | `./gradlew build` succeeds on an empty project |
| 2 | Canonical model + enums + invariants | `core-model` | Invariant tests; JSON round-trip; conformance against `schema/observation.schema.json` |
| 3 | CSV codec | `core-model` | Round-trip, quoting/escaping, null-vs-empty, determinism, golden-file tests |
| 4 | Identifier normalizer | `core-model` | MAC/UUID/hex table-driven tests, including 16-bit UUID expansion |
| 5 | Validator | `core-model` | Every invariant produces its specified error code |
| 6 | Export package writer | `core-export` | In-memory sink; manifest correctness; checksum verification; deterministic bytes |
| 7 | Provider interfaces + `ObservationFactory` | `core-radio` | Fake providers; stamping of observer/session/schema cannot be skipped |
| 8 | Room entities, DAOs, migration-1 schema | `data-room` | Instrumented DAO tests; dedup-by-primary-key test |
| 9 | Wi-Fi provider | `radio-android` | Robolectric for the scheduler; on-device for real scans |
| 10 | BLE provider | `radio-android` | On-device |
| 11 | RTT provider | `radio-android` | On-device with RTT hardware, plus a no-RTT device for the degradation path |
| 12 | Location + sensor providers | `radio-android` | On-device |
| 13 | Collection service + batched writer | `collector-app` | Long-session soak test |
| 14 | Permission onboarding | `collector-app` | Manual matrix across API 26/28/30/33/35 |
| 15 | Dashboard | `collector-app` | On-device |
| 16 | Export UI (SAF) | `collector-app` | Exported zip validated by `core-import`'s `PackageValidator` |
| 17 | Survey capture | `collector-app` | On-device; ground-truth labelling asserted |

Steps 1–8 are pure JVM or instrumented-local and account for the majority of the correctness risk.
They are complete and green before any radio code is written, which means a failure during field
testing can be attributed to the radio layer rather than to parsing or persistence.

## 3. Definition of done for Milestone 1

Functional:

1. A six-hour session on real hardware produces a monotonically growing observation count with no
   crash, no ANR, and no unexplained gap.
2. `EXPORT TODAY` produces a package that `PackageValidator` accepts with zero errors.
3. Re-importing that package into the Master (Milestone 2) yields `new = 0` on the second attempt.
4. Every observation has a non-null `observer_id`, a valid UUID `observation_id`, and an ISO-8601 UTC
   timestamp.
5. Wi-Fi observations are labelled `FRESH` or `CACHED`, and `CACHED` rows are not counted as
   independent samples by the Lab.
6. On a device without RTT hardware, the app runs normally and reports RTT as unsupported.
7. With location permission denied, the app reports a blocker and records nothing, rather than
   recording empty rows.
8. Survey Mode produces exactly `sample_count` observations tagged `GROUND_TRUTH` with the correct
   `survey_point_id`.

Non-functional:

9. Battery: a full working day on `BALANCED` on a mid-range phone with the screen off.
10. Storage: 24 h of dense collection stays under ~200 MB (measured, not assumed).
11. Export of 500k observations completes without OOM (cursor-paged, streaming zip).
12. All JVM unit tests pass in CI without an Android device.

## 4. Field test protocol (the actual Milestone 1 acceptance test)

Hardware: at least two Android phones of *different* manufacturers — chipset RSSI differences are a
known risk (deliverable 15) and a single-model test would hide them.

1. **Bench test, 15 min.** Both phones in one place. Confirm counters rise, both see the same APs,
   and compare RSSI for identical BSSIDs. A systematic offset here is the first calibration datum.
2. **Walk test, 1 h.** Walk a known route through the buildings with timestamped waypoints recorded
   manually. This becomes the first movement-engine validation set.
3. **Static overnight, 12 h.** One phone stationary. Establishes the site's RF stability baseline and
   exposes Doze effects on scan cadence.
4. **Survey sweep.** 60-second surveys at `B7_CENTER`, `B7_NORTH_DOOR`, `B7_SOUTH_DOOR`,
   `B7_B9_PATH`, `B9_CENTER`, `B4_CENTER`.
5. **Export and validate.** Export from both phones; validate both; import both into the Master;
   import one of them twice and confirm zero duplicates.
6. **Permission matrix.** Repeat step 1 with location denied, Bluetooth off, and Wi-Fi off, checking
   that each produces the specified blocker rather than silent emptiness.

Deliverable from the field test: a short written report with observed sample rates per sensor, the
inter-device RSSI offset, battery consumption, and database growth rate. Those four numbers size
every subsequent design decision, and none of them can be guessed from a desk.

## 5. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Wi-Fi throttling starves the sample rate on 29+ | Broadcast-driven collection harvests other apps' scans; token bucket avoids rejected calls; record `throttled` and `result_freshness` so the real rate is measurable rather than assumed |
| OEM battery managers kill the service | Foreground service + optional battery-optimization exemption + a session-gap detector that flags suspected kills in the session summary |
| Chipset RSSI differences corrupt fingerprints | Store raw RSSI only; per-observer calibration offsets measured in step 1 of the field test and applied only in DERIVED |
| BLE advertisement flood fills the database | Configurable scan mode and report delay; explicit back-pressure policy that *records* dropped counts |
| Randomized BLE MACs create identifier churn | `identifier_type=BLE_MAC_RANDOM`, never auto-attributed; prefer enrolling tags with stable service UUIDs |
| Clock skew between observers | `clock_elapsed_realtime_ms` + `clock_boot_utc` on every row; the Lab estimates offsets and uses fusion windows |
| Export interrupted mid-write | Write to a temporary name and rename on completion; `checksum.txt` makes a truncated package detectable |
| SAF permission lost between sessions | Persist the tree URI grant; re-prompt with a clear message rather than failing silently |

## 6. Immediately after Milestone 1

Milestone 2 (Master) is next, and specifically the **import engine first**, because a Collector whose
exports have never been imported is unproven. `core-import` is built alongside `core-export` in
Milestone 1 for precisely this reason: the round trip is tested from day one, in the same JVM test
suite, with no device required.
