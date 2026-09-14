# Deliverable C — CSV column specification

`observations.csv` is the **human-inspectable** view of an export package. `observations.json` is the
**canonical** interchange format. When the two disagree, JSON wins and the Master's import reports a
`CSV_JSON_MISMATCH` error rather than picking a side.

The single implementation is `core-model/.../ObservationCsvCodec.kt`; the Lab's reader
(`rfmapper_lab/parsing/csv_reader.py`) is tested against fixtures produced by it.

## 1. Dialect

| Property | Value |
|---|---|
| Encoding | UTF-8, **no BOM** |
| Line terminator | `\n` (LF) |
| Field separator | `,` |
| Quoting | RFC 4180: quote a field iff it contains `,`, `"`, `\n` or `\r`; escape `"` as `""` |
| Header | Required, exactly the row in §2, in that order |
| Null | Empty field (zero characters). An empty *quoted* string `""` also decodes to null — CSV cannot distinguish them, which is why JSON is canonical |
| Booleans | Lowercase `true` / `false` (metadata values only) |
| Decimals | `.` separator, no thousands separator, no exponent notation. Coordinates 6 dp, other doubles up to 6 significant decimals, trailing zeros trimmed |
| Integers | No `+`, no leading zeros, `-` allowed |
| Timestamps | `YYYY-MM-DDTHH:MM:SS.sssZ` |
| Row order | Ascending `timestamp_utc`, then ascending `observation_id` as a tiebreak. Deterministic so that two exports of the same data are byte-identical |

Deterministic output matters: it makes `checksum.txt` meaningful and makes re-export diffable.

## 2. Column order

29 columns. Column order is part of the contract for `schema_version` 1.x; a **minor** version may
only append columns to the right.

| # | Column | Type | Null | Notes |
|---:|---|---|---|---|
| 1 | `observation_id` | uuid | no | |
| 2 | `schema_version` | semver | no | |
| 3 | `timestamp_utc` | iso8601 | no | |
| 4 | `observer_id` | string | no | |
| 5 | `observer_device_type` | enum | no | |
| 6 | `sensor_type` | enum | no | |
| 7 | `target_device_id` | string | yes | |
| 8 | `radio_identifier` | string | no | |
| 9 | `identifier_type` | enum | no | |
| 10 | `ssid` | string | yes | Quoted when it contains a separator; may contain any UTF-8 |
| 11 | `bssid` | mac | yes | `aa:bb:cc:dd:ee:ff` |
| 12 | `ble_service_uuid` | uuid | yes | |
| 13 | `manufacturer_data` | hex | yes | Uppercase, unseparated |
| 14 | `rssi` | int | yes | dBm |
| 15 | `tx_power` | int | yes | dBm |
| 16 | `frequency` | int | yes | MHz |
| 17 | `channel` | int | yes | |
| 18 | `rtt_distance_mm` | int | yes | |
| 19 | `rtt_stddev_mm` | int | yes | |
| 20 | `latitude` | double | yes | 6 dp |
| 21 | `longitude` | double | yes | 6 dp |
| 22 | `horizontal_accuracy` | double | yes | metres |
| 23 | `building_id` | string | yes | **observer** context |
| 24 | `zone_id` | string | yes | **observer** context |
| 25 | `x_coordinate` | double | yes | **observer** site-local metres |
| 26 | `y_coordinate` | double | yes | **observer** site-local metres |
| 27 | `confidence` | double | yes | 0–1 collection-time sample quality |
| 28 | `metadata_json` | json | no | Compact JSON object, keys sorted; `{}` when empty |
| 29 | `row_checksum` | hex | no | See §3 |

### Header row (verbatim)

```
observation_id,schema_version,timestamp_utc,observer_id,observer_device_type,sensor_type,target_device_id,radio_identifier,identifier_type,ssid,bssid,ble_service_uuid,manufacturer_data,rssi,tx_power,frequency,channel,rtt_distance_mm,rtt_stddev_mm,latitude,longitude,horizontal_accuracy,building_id,zone_id,x_coordinate,y_coordinate,confidence,metadata_json,row_checksum
```

## 3. `metadata_json` and `row_checksum`

**`metadata_json`** flattens the extensible map into one column so the column count stays fixed.
Serialization is compact (no spaces) with **keys sorted lexicographically**, which keeps the row
deterministic and keeps `row_checksum` stable. It will normally require quoting, because it contains
commas and quotes:

```
"{""app_version"":""1.0.0"",""result_freshness"":""FRESH"",""session_id"":""0d6b...""}"
```

**`row_checksum`** is the first 16 hex characters of the SHA-256 of columns 1–28 joined with `\u001f`
(unit separator), using the *decoded* values, not the quoted CSV text. It gives a cheap per-row
integrity check that survives the round trip through a spreadsheet, which is the most likely way a
CSV gets accidentally corrupted in the field. An import with a bad `row_checksum` marks that row
`INVALID` with reason `ROW_CHECKSUM_MISMATCH` and imports the rest.

A truncated 64-bit digest is deliberate: it is for corruption detection, not tamper resistance.
Package-level integrity is `checksum.txt` (full SHA-256 per file).

## 4. Reading rules for consumers

1. **Match columns by header name, not by position.** Positions are specified so writers are
   deterministic, but a reader that keys on names survives a future minor version that appends
   columns.
2. **Reject unknown required columns missing**; **preserve unknown extra columns** into
   `metadata["csv_extra_<name>"]` rather than discarding them.
3. Reject an unknown `schema_version` **major**; accept unknown minors and patches.
4. Never coerce a malformed value into a default. A malformed cell makes the *row* invalid and the
   row is reported, not silently repaired.
5. Reject `NaN`, `Infinity`, `-Infinity` in any numeric column.

## 5. Worked example (3 rows, wrapped for readability)

```csv
observation_id,schema_version,timestamp_utc,observer_id,observer_device_type,sensor_type,target_device_id,radio_identifier,identifier_type,ssid,bssid,ble_service_uuid,manufacturer_data,rssi,tx_power,frequency,channel,rtt_distance_mm,rtt_stddev_mm,latitude,longitude,horizontal_accuracy,building_id,zone_id,x_coordinate,y_coordinate,confidence,metadata_json,row_checksum
4f1c9a2e-6b3d-4c58-9e77-0a1b2c3d4e5f,1.0.0,2026-09-14T08:19:04.312Z,OBS-04,ANDROID_PHONE,WIFI_SCAN,,aa:bb:cc:11:22:33,WIFI_BSSID,SITE-INFRA-7,aa:bb:cc:11:22:33,,,-61,,5180,36,,,,,,B7,B7-CENTER,24.5,11,0.9,"{""result_freshness"":""FRESH""}",9f2c40ab17de5581
7a2b0c11-2d3e-4f50-8192-a3b4c5d6e7f8,1.0.0,2026-09-14T08:19:05.004Z,OBS-04,ANDROID_PHONE,BLE,DEVICE-03,d1:e2:f3:04:15:26,BLE_MAC_PUBLIC,,,0000180f-0000-1000-8000-00805f9b34fb,004C0215A1B2,-73,4,,,,,,,,B7,B7-CENTER,24.5,11,0.85,"{""ble_device_name"":""TAG-03""}",3ab8c1d0e4f52276
b3c4d5e6-7f80-4912-a3b4-c5d6e7f80912,1.0.0,2026-09-14T08:19:06.550Z,OBS-04,ANDROID_PHONE,RTT,,aa:bb:cc:11:22:33,WIFI_BSSID,,aa:bb:cc:11:22:33,,,-58,,5180,36,11840,1320,,,,B7,B7-CENTER,24.5,11,0.95,"{""rtt_num_successful"":""7""}",c70d9e1a2b3c4d5e
```

Row 1: a Wi-Fi scan of site infrastructure, observer in B7-CENTER, unattributed.
Row 2: a BLE advertisement attributed to the enrolled `DEVICE-03`.
Row 3: a genuine RTT range of 11.84 m ± 1.32 m to the same AP.

`row_checksum` values above are illustrative placeholders; the codec computes real ones.

## 6. Other CSV files in the ecosystem

Same dialect, specified alongside their schemas:

| File | Spec |
|---|---|
| `position_estimates.csv` | [`13-derived-output-schema.md`](13-derived-output-schema.md) |
| `zone_transitions.csv` | [`13-derived-output-schema.md`](13-derived-output-schema.md) |
| `managed_devices.csv` (reference export/import) | [`18-site-model-specification.md`](18-site-model-specification.md) |
| `infrastructure_nodes.csv` | [`18-site-model-specification.md`](18-site-model-specification.md) |
