# Deliverable D — Room entity and DAO design

One Room database per app, both built from the same `data-room` module, with the app selecting which
table groups it uses. Database name: `rfmapper.db`. Current version: **1**.

Tables are grouped by data layer, and the layer determines what operations exist at all:

| Layer | Prefix | Allowed DAO operations |
|---|---|---|
| RAW | `raw_` | `INSERT` (ignore-on-conflict), `SELECT` |
| REFERENCE | `ref_` | `INSERT`, `UPDATE`, `SELECT`, soft-delete via status |
| DERIVED | `der_` | `INSERT`, `SELECT`, `DELETE BY algorithm_version` |

## 1. Immutability of RAW, enforced by the type system

`ObservationDao` contains **no** `@Update` and **no** `@Delete` for `raw_observation`. There is no
"fix up a row" method to call by accident, and adding one would be a visible, reviewable diff. The
only write is:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAll(rows: List<RawObservationEntity>): List<Long>   // -1 == already present
```

`OnConflictStrategy.IGNORE` on a primary key of `observation_id` *is* the deduplication mechanism: it
is atomic, it works inside a transaction, and re-importing a package is therefore a no-op by
construction rather than by a check-then-write race. The returned row-id list (`-1` for skipped)
gives the exact duplicate count for the import report.

Purging old raw data is a separate, explicitly administrative operation (`RetentionDao`), gated behind
a confirmation, and it is a *retention* action, not an edit.

## 2. RAW layer

### `raw_observation`

Columns mirror the canonical schema one-to-one (see deliverable B), plus three storage-only columns.
`metadata` is stored as a JSON `TEXT` column via a `MetadataConverter`.

```kotlin
@Entity(
    tableName = "raw_observation",
    indices = [
        Index("timestamp_utc"),
        Index("observer_id", "timestamp_utc"),
        Index("radio_identifier", "timestamp_utc"),
        Index("target_device_id", "timestamp_utc"),
        Index("sensor_type", "timestamp_utc"),
        Index("session_id"),
        Index("import_batch_id"),
    ],
)
data class RawObservationEntity(
    @PrimaryKey val observationId: String,
    val schemaVersion: String,
    val timestampUtc: String,          // ISO-8601; stored as TEXT for lossless round-trip
    val timestampEpochMs: Long,        // derived, indexed, for range queries
    val observerId: String,
    val observerDeviceType: String,
    val sensorType: String,
    val targetDeviceId: String?,
    val radioIdentifier: String,
    val identifierType: String,
    val ssid: String?,
    val bssid: String?,
    val bleServiceUuid: String?,
    val manufacturerData: String?,
    val rssi: Int?,
    val txPower: Int?,
    val frequency: Int?,
    val channel: Int?,
    val rttDistanceMm: Int?,
    val rttStddevMm: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracy: Double?,
    val buildingId: String?,
    val zoneId: String?,
    val xCoordinate: Double?,
    val yCoordinate: Double?,
    val confidence: Double?,
    val metadata: Map<String, String>,
    // storage-only
    val sessionId: String?,            // denormalized from metadata for indexed session queries
    val sampleKind: String,            // ORDINARY | GROUND_TRUTH, denormalized for the survey path
    val importBatchId: String?,        // null on the Collector; set on the Master
)
```

`timestamp_utc` is kept as `TEXT` **and** as `timestampEpochMs`. The text form guarantees the export
is byte-identical to what was collected; the epoch form makes range scans use an index instead of
string comparison. The two are written together in one place (`RawObservationEntity.from`), so they
cannot drift.

`sessionId` and `sampleKind` are denormalized out of `metadata` because the Collector dashboard and
the survey flow query on them constantly, and `json_extract` on a `TEXT` column cannot use an index.

### `raw_import_batch` (Master only)

```
import_batch_id TEXT PK | observer_id | package_name | package_sha256 | imported_at_utc
| schema_version | declared_count | accepted_count | duplicate_count | invalid_count
| manifest_json | operator | status (PREVIEWED|COMMITTED|REJECTED)
```

`package_sha256` has a unique index, so importing the identical file twice is caught before a single
row is parsed. Deduplicating by `observation_id` still handles the case of overlapping packages that
are not byte-identical (e.g. `EXPORT TODAY` run twice with more data the second time).

## 3. REFERENCE layer

```
ref_managed_device
  device_id TEXT PK | friendly_name | device_type | notes | status
  | first_seen_utc | last_seen_utc | created_at_utc | updated_at_utc

ref_device_identifier                         -- normalized out of the three known_*[] arrays
  identifier TEXT | identifier_type TEXT | device_id TEXT | added_by | added_at_utc | notes
  PK(identifier, identifier_type)             -- one identifier attributes to at most one device
  INDEX(device_id)
  FK device_id -> ref_managed_device ON DELETE CASCADE

ref_infrastructure_node
  node_id TEXT PK | friendly_name | type | building_id | floor | zone_id | x | y
  | latitude | longitude | known_bssid | known_ble_identifier | rtt_capable | notes
  INDEX(known_bssid), INDEX(building_id, zone_id)

ref_observer
  observer_id TEXT PK | friendly_name | building_id | default_zone_id | device_model
  | platform | app_version | installation_id | status | enrolled_at_utc

ref_building   building_id TEXT PK | name | floors | origin_lat | origin_lon | rotation_deg | notes
ref_zone       zone_id TEXT PK | building_id | name | floor | polygon_json | centroid_x | centroid_y | notes
ref_zone_edge  from_zone_id | to_zone_id | edge_type (DOOR|CORRIDOR|OUTDOOR_PATH|RESTRICTED|IMPOSSIBLE)
               | typical_traversal_s | notes | PK(from_zone_id, to_zone_id)
ref_survey_point
  survey_point_id TEXT PK | building_id | zone_id | floor | x | y | label | notes | created_at_utc

ref_fingerprint                                -- the promoted, ground-truth fingerprint
  fingerprint_id TEXT PK | survey_point_id | building_id | zone_id | x | y | observer_id
  | sample_count | source_survey_session_ids_json | engine_version
  | status (CANDIDATE|GROUND_TRUTH|RETIRED) | created_at_utc | updated_at_utc
  INDEX(survey_point_id), INDEX(status)

ref_fingerprint_entry                          -- one row per radio source per fingerprint
  fingerprint_id | radio_identifier | identifier_type | sample_count | visibility_probability
  | rssi_median | rssi_mean | rssi_stddev | rssi_p10 | rssi_p90 | rssi_min | rssi_max
  PK(fingerprint_id, radio_identifier)
  FK fingerprint_id -> ref_fingerprint ON DELETE CASCADE

ref_observer_calibration
  observer_id TEXT PK | rssi_offset_db | measured_at_utc | sample_count | method | notes
```

Two decisions worth calling out:

- **`ref_device_identifier` is a table, not a JSON array.** Attribution is a hot lookup on every
  single observation (`is this identifier enrolled?`). An indexed join answers it; a JSON array
  inside `ref_managed_device` would force a full scan per observation. The composite primary key also
  makes "one identifier claimed by two devices" impossible at the storage level.
- **Fingerprints are two tables.** A fingerprint has an unbounded number of visible radio sources, and
  the matcher wants to iterate only the sources present in the live vector.
  `ref_fingerprint_entry` stores a distribution per source (median/mean/stddev/percentiles/visibility
  probability), never a single RSSI value.

`ref_fingerprint.status` is the promotion gate: Survey Mode writes `CANDIDATE`, and only an explicit
administrator action moves a row to `GROUND_TRUTH`. Nothing in the automatic path can perform that
transition.

## 4. DERIVED layer

```
der_position_estimate
  estimate_id TEXT PK | algorithm_version | engine_versions_json | device_id | timestamp_utc
  | timestamp_epoch_ms | building_id | zone_id | x | y | horizontal_uncertainty_m
  | confidence | method | precision_tier
  | supporting_observer_ids_json | supporting_observation_ids_json
  | source_dataset_ids_json | created_at_utc
  INDEX(device_id, timestamp_epoch_ms), INDEX(algorithm_version), INDEX(building_id, zone_id)

der_zone_transition
  transition_id TEXT PK | algorithm_version | device_id | origin_zone_id | destination_zone_id
  | transition_start_utc | transition_confirmed_utc | confidence | event_type
  | supporting_observer_ids_json | supporting_estimate_ids_json | created_at_utc
  INDEX(device_id, transition_start_utc)

der_movement_estimate
  movement_id TEXT PK | algorithm_version | device_id | timestamp_utc | state
  | origin_zone_id | candidate_destination_zone_id | confirmed_destination_zone_id
  | direction | confidence | supporting_estimate_ids_json | created_at_utc
  INDEX(device_id, timestamp_utc)

der_quality_flag
  flag_id TEXT PK | algorithm_version | created_at_utc | severity | code | scope | scope_id
  | message | evidence_json | acknowledged_at_utc | acknowledged_by
```

`event_type` on a transition is `RF_ZONE_ENTER` / `RF_ZONE_EXIT` / `RF_ZONE_TRANSITION`.

Every derived row carries `algorithm_version` plus the finer-grained `engine_versions_json`
(`{"fingerprint_engine":"1.2.0","zone_engine":"1.1.3","movement_engine":"0.8.2"}`). Reprocessing
inserts a **new** generation of rows and never overwrites an old one; the UI filters to the active
version. `DELETE BY algorithm_version` exists so an administrator can discard a superseded
generation deliberately.

`der_quality_flag` is where "Building 4 fingerprint could be improved" and
`POSSIBLE_RF_ENVIRONMENT_CHANGE` live. Flags are advisory and must be acknowledged by a human; no code
path consumes a flag to change calibration automatically.

## 5. DAOs

| DAO | Key queries |
|---|---|
| `ObservationDao` | `insertAll` (ignore-on-conflict); `countAll`; `countBySensorType`; `observeLiveCounters(sessionId)`; `pageByTimeRange`; `selectForExport(from,to)` (ordered by timestamp then id, cursor-paged); `existingIds(ids)` for the import preview; `distinctIdentifiers(from,to)`; `bySurveySession` |
| `ManagedDeviceDao` | CRUD; `withIdentifiers()` (`@Transaction` + `@Relation`); `findDeviceIdFor(identifier, type)`; `touchLastSeen(deviceId, ts)` |
| `InfrastructureDao` | CRUD; `byBuilding`; `findByBssid`; `rttCapableNodes()` |
| `ObserverDao` | CRUD; `localIdentity()` (Collector: exactly one row) |
| `SiteModelDao` | buildings, zones, edges, survey points; `topologyGraph()` |
| `FingerprintDao` | `upsertFingerprint` + entries in one `@Transaction`; `groundTruthFingerprints()`; `candidates()`; `promote(fingerprintId)`; `entriesFor(ids)` |
| `ImportBatchDao` | `insertPreview`; `commit`; `findByPackageSha256`; `history()` |
| `DerivedDao` | bulk insert per table; `latestEstimatePerDevice(version)`; `estimatesInRange`; `transitionsForDevice`; `deleteByAlgorithmVersion` |
| `RetentionDao` | `deleteRawBefore(epochMs)` — the only raw delete, administrative and confirmed |

### Patterns used throughout

- **`Flow` for anything on screen** (`observeLiveCounters`) so the Collector dashboard is push-driven
  rather than polling a database while the radios are busy.
- **`PagingSource` for the observation browser.** A day of collection is comfortably over 100k rows;
  nothing loads a full table into memory.
- **Cursor-paged export.** `selectForExport` takes `(afterTimestamp, afterId, limit)` rather than
  `LIMIT/OFFSET`, so exporting 500k rows is constant-memory and immune to concurrent inserts
  shifting offsets.
- **`@Transaction` for multi-table writes**: import commit, fingerprint upsert, derived-generation
  insert.

## 6. Write path under load

Wi-Fi and BLE scan callbacks arrive in bursts. Each provider emits into a `Channel`, and a single
writer coroutine drains it with `chunked(size = 200, timeout = 500.ms)` into one `insertAll`
transaction. Batching is what keeps a multi-hour session from doing a `fsync` per advertisement, and
a single writer avoids SQLite write contention. WAL journal mode is enabled.

Back-pressure policy: if the channel fills (sustained rate above the writer's throughput), the oldest
*BLE* samples are dropped first and a `throttled`/`dropped_samples` counter is recorded in the session
row, because silently losing data without recording that it was lost would corrupt the Lab's
visibility statistics. Wi-Fi samples are never dropped — they arrive at scan cadence, not
advertisement cadence.

## 7. Migrations

`fallbackToDestructiveMigration()` is **not** used in release builds. RAW data is unrecoverable field
work.

Migration rules:
1. Every schema change ships an explicit `Migration(n, n+1)` and a test that opens a version-`n`
   fixture database, migrates, and asserts the data survived (`MigrationTest` with
   `MigrationTestHelper`).
2. Room's exported schema JSON (`data-room/schemas/`) is committed, so a schema change that the author
   forgot to migrate fails the build rather than the device.
3. Adding a canonical observation field is additive: nullable column + `schema_version` minor bump.
4. Debug builds may use destructive migration; a release build asserts at startup that it did not.
