# Export package specification (observation packages)

The V1 synchronization mechanism: **manual, offline, file-based.** No live server, no networking.

## 1. Package name

```
RFMapper_<OBSERVER>_<YYYY-MM-DD>.zip          EXPORT TODAY
RFMapper_<OBSERVER>_<YYYY-MM-DD>_<SESSION8>.zip   EXPORT SESSION
```

`<OBSERVER>` is the observer id with non-alphanumerics removed (`OBS-04` → `OBS04`), matching the
specification's `RFMapper_OBS04_2026-09-14.zip`. `<SESSION8>` is the first 8 characters of the session
UUID. If a file of that name exists, `_2`, `_3`, … is appended rather than overwriting — an export is
never destructive.

## 2. Contents

```
manifest.json      required   package metadata
observations.csv   required   human-inspectable, per deliverable C
observations.json  required   canonical interchange
observer.json      required   observer identity + capabilities
checksum.txt       required   SHA-256 per file
sessions.json      optional   per-session context (see §6)
```

Compression: DEFLATE. Entries stored in the order above, with fixed timestamps, so two exports of
identical data are byte-identical.

## 3. `manifest.json`

Required fields from the specification, plus the integrity and capability fields the import engine
needs:

```json
{
  "schema_version": "1.0.0",
  "package_type": "OBSERVATIONS",
  "export_id": "3f8e1c20-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
  "observer_id": "OBS-04",
  "created_at": "2026-09-14T18:42:11.004Z",
  "export_kind": "DAY",
  "date_range": { "from": "2026-09-14T00:00:00.000Z", "to": "2026-09-14T23:59:59.999Z" },
  "observation_count": 18391,
  "first_observation": "2026-09-14T06:11:02.310Z",
  "last_observation": "2026-09-14T18:41:55.882Z",
  "app_version": "1.0.0",
  "platform": "android",
  "os_version": "34",
  "device_model": "Pixel 7a",
  "installation_id": "b1c2d3e4-...",
  "session_ids": ["0d6b1f4a-...", "7c2e4a91-..."],
  "counts_by_sensor_type": { "WIFI_SCAN": 12841, "BLE": 5480, "RTT": 61, "GPS": 9 },
  "ground_truth_count": 360,
  "generator": { "name": "RFMapper Collector", "version": "1.0.0" }
}
```

`export_kind` ∈ `DAY` | `SESSION` | `RANGE`. `counts_by_sensor_type` and `ground_truth_count` let the
Master's preview show a meaningful summary before parsing 18k rows, and let a mismatch against the
actual content be detected as `MANIFEST_COUNT_MISMATCH`.

## 4. `observer.json`

```json
{
  "schema_version": "1.0.0",
  "observer_id": "OBS-04",
  "friendly_name": "Building 4 Android Collector",
  "observer_device_type": "ANDROID_PHONE",
  "building_id": "B4",
  "default_zone_id": "B4-CENTER",
  "device_model": "Pixel 7a",
  "manufacturer": "Google",
  "platform": "android",
  "os_version": "34",
  "app_version": "1.0.0",
  "installation_id": "b1c2d3e4-...",
  "capabilities": ["WIFI_SCAN", "WIFI_ASSOCIATION", "BLE", "GPS"],
  "unsupported": ["RTT"],
  "x_coordinate": null,
  "y_coordinate": null,
  "fixed_observer": false,
  "notes": ""
}
```

`capabilities` and `unsupported` are load-bearing for the Lab, not documentation. An observer that
cannot scan Wi-Fi reporting no Wi-Fi observations means *"this observer cannot see Wi-Fi"*, not
*"no APs were present"*. Without this distinction, an iOS observer would silently drive every
fingerprint's Wi-Fi visibility probability toward zero — absence of evidence misread as evidence of
absence.

## 5. `checksum.txt`

```
sha256  <filename>
```

One line per file except itself, sorted by filename, two spaces as separator (the `sha256sum` format,
so it can be verified with standard tools in the field):

```
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  manifest.json
d2a84f4b8b650937ec8f73cd8be2c74add5a911ba64df27458ed8229da804a26  observations.csv
...
```

## 6. `sessions.json` (optional)

Per-session context that explains gaps and sampling behaviour after the fact:

```json
[{
  "session_id": "0d6b1f4a-...",
  "started_at": "2026-09-14T06:11:00.000Z",
  "ended_at": "2026-09-14T12:43:00.000Z",
  "scan_profile": "BALANCED",
  "building_id": "B4", "zone_id": "B4-CENTER",
  "observation_count": 9120,
  "wifi_count": 6400, "ble_count": 2700, "rtt_count": 12, "gps_count": 8,
  "dropped_samples": 0,
  "throttled_scan_requests": 14,
  "background_denied": false,
  "suspected_service_kill": false,
  "battery_start_pct": 92, "battery_end_pct": 41,
  "degradations": []
}]
```

`throttled_scan_requests`, `dropped_samples` and `suspected_service_kill` are the fields that turn an
unexplained coverage gap into a known one. Recording that data was lost is far more valuable than a
clean-looking file that quietly omits it.

## 7. Write procedure (crash-safe)

1. Stream to a temporary name `.RFMapper_OBS04_2026-09-14.zip.part`.
2. Query observations cursor-paged (`selectForExport`), writing CSV and JSON incrementally. Constant
   memory regardless of row count.
3. Compute SHA-256 for each entry as it is written.
4. Write `checksum.txt` last.
5. Close, then rename to the final name.

A crash leaves a `.part` file, which is not a valid package and is ignored. A package that exists is
complete, and `checksum.txt` proves it.

## 8. Import validation order

Cheapest and most decisive checks first, so a bad package fails fast:

| # | Check | Failure code |
|---|---|---|
| 1 | Zip opens; required entries present | `MALFORMED_PACKAGE`, `MISSING_ENTRY` |
| 2 | `manifest.json` parses; `schema_version` major readable | `UNREADABLE_SCHEMA_VERSION` |
| 3 | `package_type == OBSERVATIONS` | `WRONG_PACKAGE_TYPE` |
| 4 | `checksum.txt` verifies every entry | `CHECKSUM_MISMATCH` |
| 5 | Package SHA-256 not already imported | `ALREADY_IMPORTED` (warning; proceed to dedup) |
| 6 | `observer_id` present, consistent between manifest and `observer.json` | `OBSERVER_MISMATCH` |
| 7 | `observer_id` is enrolled in the Master | `UNKNOWN_OBSERVER` (blocking; requires enrollment) |
| 8 | CSV header exactly matches the expected columns | `CSV_HEADER_MISMATCH` |
| 9 | Per row: required fields, enums, formats, invariants, `row_checksum` | per-row `INVALID` with a reason |
| 10 | Row count and `counts_by_sensor_type` match the manifest | `MANIFEST_COUNT_MISMATCH` |
| 11 | `observer_id` on every row matches the package | `ROW_OBSERVER_MISMATCH` |
| 12 | Timestamps within `date_range`, not in the future beyond tolerance | `TIMESTAMP_OUT_OF_RANGE` |
| 13 | CSV and JSON agree row-for-row on `observation_id` | `CSV_JSON_MISMATCH` |
| 14 | `GROUND_TRUTH` rows carry a valid `survey_session_id` | `INVALID_GROUND_TRUTH_CLAIM` |
| 15 | Dedup: `observation_id` against existing rows | counted as duplicate |

Check 7 is intentionally blocking: an unknown observer means the Master would be accepting data of
unverified provenance into its immutable raw layer. The administrator enrolls the observer first.

Check 14 is the import-side half of the ground-truth safeguard from deliverable 11.

Nothing is written to the database during validation. The result is a preview:

```
Observer: OBS-04
Date: 2026-09-14
Observations: 18,391
Duplicates: 291
New: 18,100
Invalid: 0

[IMPORT]
```

Only on `[IMPORT]` does a single transaction insert the new rows and the `raw_import_batch` record.

## 9. Idempotency

Guaranteed by `observation_id` being the primary key with `OnConflictStrategy.IGNORE`. Importing the
same package twice inserts nothing the second time; overlapping packages insert only the genuinely
new rows. This is a property of the storage layer, not of a check that could be raced.
