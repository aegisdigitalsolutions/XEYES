# Positioning Lab deliverable 8 — Derived output schema

Package: `DERIVED_<YYYY-MM-DD>.zip`, produced by the Lab, imported by the Master.

```
DERIVED_2026-09-14.zip
├── manifest.json
├── position_estimates.json      (canonical)
├── position_estimates.csv       (human-inspectable)
├── zone_transitions.json
├── zone_transitions.csv
├── movement_estimates.json
├── quality_report.json
├── algorithm_report.json
└── checksum.txt
```

Same conventions as the observation package: UTF-8, LF, ISO-8601 UTC, JSON canonical, CSV
inspectable, `checksum.txt` with `sha256  filename` lines sorted by filename.

JSON Schemas: [`../schema/position_estimate.schema.json`](../schema/position_estimate.schema.json),
[`../schema/zone_transition.schema.json`](../schema/zone_transition.schema.json),
[`../schema/movement_estimate.schema.json`](../schema/movement_estimate.schema.json),
[`../schema/derived_manifest.schema.json`](../schema/derived_manifest.schema.json).

## 1. `PositionEstimate`

| Field | Type | Null | Notes |
|---|---|---|---|
| `estimate_id` | uuid | no | |
| `algorithm_version` | string | no | Composite pipeline version, e.g. `pipeline-1.0.0` |
| `engine_versions` | object | no | `{"fingerprint_engine":"1.2.0","zone_engine":"1.1.3",...}` |
| `device_id` | string | no | A **managed** device id. Never a randomized identifier |
| `timestamp_utc` | iso8601 | no | Instant the estimate refers to, not when it was computed |
| `computed_at_utc` | iso8601 | no | |
| `precision_tier` | enum | no | `SITE_PRESENCE`, `BUILDING`, `ZONE`, `APPROXIMATE_POSITION`, `PRECISION_RANGE` |
| `building_id` | string | yes | Null only for `SITE_PRESENCE` |
| `zone_id` | string | yes | Null for `SITE_PRESENCE`/`BUILDING` |
| `x` | double | yes | Site-frame metres. Null unless tier ≥ `APPROXIMATE_POSITION` |
| `y` | double | yes | Null unless tier ≥ `APPROXIMATE_POSITION` |
| `horizontal_uncertainty_m` | double | yes | **Required whenever `x`/`y` are present** |
| `confidence` | double 0–1 | no | |
| `confidence_factors` | object | no | The named factors whose product is `confidence` |
| `method` | string | no | Algorithm id, e.g. `pos_wknn_centroid_v1` |
| `supporting_observer_ids` | string[] | no | Non-empty |
| `supporting_observation_ids` | string[] | no | Non-empty; the exact RAW rows used |
| `source_dataset_ids` | string[] | no | Import batch / package ids |
| `calibration_set_id` | string | yes | Which observer-calibration set was in force |
| `quality_flags` | string[] | no | e.g. `["UNVALIDATED_UNCERTAINTY"]` |

### Enforced invariants

1. `x` present ⇔ `y` present.
2. `x` present ⇒ `horizontal_uncertainty_m` present and > 0. **A coordinate without uncertainty cannot
   be constructed** — this is a constructor assertion in Python and Kotlin, not a convention.
3. `x` present ⇒ `precision_tier` ∈ {`APPROXIMATE_POSITION`, `PRECISION_RANGE`}.
4. `precision_tier = PRECISION_RANGE` ⇒ at least one supporting observation has `sensor_type = RTT`.
   Tier 4 requires genuine ranging evidence; no other evidence can reach it.
5. `supporting_observation_ids` non-empty. An estimate with no supporting observation is not an
   estimate.
6. `zone_id` present ⇒ `building_id` present.

Invariant 4 is the structural defence against fake precision. `PRECISION_RANGE` is unreachable
without an RTT measurement in the supporting set, so no amount of confident RSSI can be promoted into
it.

A valid and *preferred* record shape, straight from the specification:

```json
{
  "precision_tier": "ZONE",
  "building_id": "B9", "zone_id": "B9-EAST",
  "x": null, "y": null, "horizontal_uncertainty_m": null,
  "confidence": 0.94, "method": "zone_bayes_v1"
}
```

## 2. `ZoneTransition`

| Field | Type | Null | Notes |
|---|---|---|---|
| `transition_id` | uuid | no | |
| `algorithm_version`, `engine_versions` | | no | |
| `device_id` | string | no | |
| `event_type` | enum | no | `RF_ZONE_ENTER`, `RF_ZONE_EXIT`, `RF_ZONE_TRANSITION` |
| `origin_zone_id` | string | yes | Null for `RF_ZONE_ENTER` into the site |
| `destination_zone_id` | string | yes | Null for `RF_ZONE_EXIT` |
| `transition_start_utc` | iso8601 | no | When the candidate zone first appeared |
| `transition_confirmed_utc` | iso8601 | no | When hysteresis committed it |
| `confidence` | double | no | |
| `supporting_observer_ids` | string[] | no | |
| `supporting_estimate_ids` | string[] | no | |
| `topology_status` | enum | no | `ADJACENT`, `NON_ADJACENT`, `RESTRICTED`, `UNKNOWN_EDGE` |
| `quality_flags` | string[] | no | |

Keeping `transition_start_utc` and `transition_confirmed_utc` separate is what makes
transition-detection latency measurable — `confirmed − start` is the hysteresis delay, and collapsing
them into one timestamp would hide the system's own lag.

`topology_status = NON_ADJACENT` is emitted with a `TOPOLOGY_VIOLATION` flag rather than being
suppressed, because the site graph may be wrong.

## 3. `MovementEstimate`

| Field | Type | Null |
|---|---|---|
| `movement_id` | uuid | no |
| `algorithm_version`, `engine_versions` | | no |
| `device_id` | string | no |
| `timestamp_utc` | iso8601 | no |
| `state` | enum: `STATIONARY`/`MOVING`/`ZONE_TRANSITION`/`LOST`/`REAPPEARED`/`UNCERTAIN` | no |
| `origin_zone_id` | string | yes |
| `candidate_destination_zone_id` | string | yes |
| `confirmed_destination_zone_id` | string | yes |
| `direction` | string | yes | Region pair, e.g. `B7->B9`; a heading **only** with geometric evidence |
| `confidence` | double | no |
| `supporting_estimate_ids` | string[] | no |

## 4. `quality_report.json`

```json
{
  "report_id": "...", "generated_at_utc": "...", "date_range": {"from": "...", "to": "..."},
  "dataset_kind": "REAL",
  "ingest": { "packages": 3, "rows_read": 54892, "rows_accepted": 54601,
              "duplicates": 291, "invalid": 0, "duplicate_id_content_mismatch": 0 },
  "observers": [ { "observer_id": "OBS-04", "observations": 18391,
                   "observations_per_hour": 2170, "fresh_ratio": 0.71,
                   "cached_ratio": 0.29, "clock_jumps": 0,
                   "calibration_offset_db": null, "capabilities": ["WIFI","BLE","GPS"] } ],
  "coverage": { "zones_with_ground_truth": 6, "zones_without_ground_truth": 2,
                "calibration_density_per_zone": { "B7-CENTER": 4, "B4-CENTER": 1 } },
  "flags": [ { "code": "SPARSE_CALIBRATION", "severity": "WARNING", "scope": "ZONE",
               "scope_id": "B4-CENTER",
               "message": "Building 4 fingerprint could be improved: 1 survey session, 2 zones uncovered.",
               "evidence": { "sessions": 1, "required": 3 } } ]
}
```

`flags` is where every administrator-facing recommendation surfaces. Each carries the evidence that
produced it, so the administrator can judge it rather than trust it.

## 5. `algorithm_report.json`

The benchmark output from deliverable 12: dataset hash, split definition and seed, per-algorithm
metrics with bootstrap CIs, the selection decision and its justification, plus the parameter set in
force. This is the file that makes an accuracy claim auditable — it records what was measured, on
what data, with which split.

## 6. `manifest.json` (derived)

```json
{
  "schema_version": "1.0.0",
  "package_type": "DERIVED",
  "export_id": "uuid",
  "created_at": "2026-09-14T23:10:04.000Z",
  "date_range": { "from": "2026-09-14T00:00:00.000Z", "to": "2026-09-14T23:59:59.999Z" },
  "algorithm_version": "pipeline-1.0.0",
  "engine_versions": { "fingerprint_engine": "1.2.0", "zone_engine": "1.1.3",
                       "positioning_engine": "1.0.0", "movement_engine": "0.8.2" },
  "source_dataset_ids": ["OBS04_2026-09-14", "OBS07_2026-09-14", "OBS09_2026-09-14"],
  "reference_model_id": "site-2026-09-01",
  "calibration_set_id": "cal-2026-09-01",
  "dataset_kind": "REAL",
  "counts": { "position_estimates": 4182, "zone_transitions": 37, "movement_estimates": 4182,
              "quality_flags": 2 },
  "generator": { "name": "rfmapper_lab", "version": "1.0.0" }
}
```

## 7. Master import of a derived package

1. Verify `checksum.txt`, then `schema_version` and `package_type`.
2. Verify that every `source_dataset_ids` entry corresponds to a known import batch — a derived
   package referencing data the Master has never seen is rejected. That check is what makes the
   derived layer traceable back to raw evidence.
3. Insert into `der_*` tables keyed by `algorithm_version`. **Never** overwrite a previous generation.
4. Register the new `algorithm_version` as available; the administrator chooses which generation the
   UI displays.
5. Import is idempotent on `estimate_id` / `transition_id` / `movement_id`.

## 8. CSV forms

`position_estimates.csv` columns, in order:

```
estimate_id,algorithm_version,device_id,timestamp_utc,computed_at_utc,precision_tier,building_id,zone_id,x,y,horizontal_uncertainty_m,confidence,method,supporting_observer_ids,supporting_observation_ids,source_dataset_ids,calibration_set_id,quality_flags,engine_versions_json,confidence_factors_json
```

`zone_transitions.csv`:

```
transition_id,algorithm_version,device_id,event_type,origin_zone_id,destination_zone_id,transition_start_utc,transition_confirmed_utc,confidence,topology_status,supporting_observer_ids,supporting_estimate_ids,quality_flags,engine_versions_json
```

List-valued columns are `;`-separated inside a single quoted CSV field (a `;` cannot appear in a UUID
or an id), and object-valued columns are compact JSON with sorted keys, matching the
`metadata_json` convention in deliverable C.
