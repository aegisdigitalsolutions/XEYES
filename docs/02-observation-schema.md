# Deliverable B — Canonical observation schema

Machine-readable contract: [`../schema/observation.schema.json`](../schema/observation.schema.json)
(JSON Schema draft 2020-12). Current `schema_version` is **`1.0.0`**.

The Kotlin model (`core-model/.../Observation.kt`) and the Python model
(`positioning-lab/src/rfmapper_lab/model.py`) are both derived from that file and are checked against
it by tests in each language. If the three ever disagree, the JSON Schema wins.

## 1. Field reference

| Field | Type | Null? | Notes |
|---|---|---|---|
| `observation_id` | UUID v4 string | no | Generated on the collecting device. The only deduplication key. |
| `schema_version` | semver string | no | `1.0.0`. |
| `timestamp_utc` | ISO-8601 UTC, ms, `Z` | no | Device wall clock at measurement. |
| `observer_id` | string ≤64 | no | e.g. `OBS-04`. Mandatory everywhere. |
| `observer_device_type` | enum | no | `ANDROID_PHONE`, `ANDROID_TABLET`, `IOS_PHONE`, `IOS_TABLET`, `FIXED_OBSERVER`, `OTHER`. |
| `sensor_type` | enum | no | See §2. |
| `target_device_id` | string ≤64 | yes | Only set by an explicit enrollment match. See §5. |
| `radio_identifier` | string ≤256 | no | Normalized. See §4. |
| `identifier_type` | enum | no | See §3. |
| `ssid` | string ≤64 | yes | |
| `bssid` | MAC string | yes | Lowercase, colon-separated. |
| `ble_service_uuid` | string | yes | First advertised UUID; full list in `metadata.ble_service_uuids`. |
| `manufacturer_data` | uppercase hex | yes | Company id (4 hex digits) then payload. |
| `rssi` | int dBm | yes | **Raw, uncalibrated.** |
| `tx_power` | int dBm | yes | |
| `frequency` | int MHz | yes | |
| `channel` | int | yes | Derived from `frequency` by the collector. |
| `rtt_distance_mm` | int | yes | Genuine RTT only. |
| `rtt_stddev_mm` | int | yes | Genuine RTT only. |
| `latitude` | double | yes | |
| `longitude` | double | yes | |
| `horizontal_accuracy` | double m | yes | Of the GNSS fix. |
| `building_id` | string ≤64 | yes | **Observer** context. |
| `zone_id` | string ≤64 | yes | **Observer** context. |
| `x_coordinate` | double m | yes | **Observer** site-local X. |
| `y_coordinate` | double m | yes | **Observer** site-local Y. |
| `confidence` | double 0–1 | yes | Collection-time sample quality, not position confidence. |
| `metadata` | map string→string | no (may be `{}`) | Reserved keys in §6. |

### The single most important clarification

`building_id`, `zone_id`, `x_coordinate`, `y_coordinate`, `latitude`, `longitude` on an
**Observation** describe **where the observer was**, never where the observed device is. A RAW record
is a statement of the form *"observer OBS-04, standing at B7_CENTER, saw identifier X at −61 dBm"*.
Any claim about where the target is lives exclusively in a `PositionEstimate` in the DERIVED layer.

Getting this wrong would silently turn an observation into an unvalidated location claim, which is
exactly the failure mode both specifications forbid. The field comments in the JSON Schema, the Kotlin
KDoc and the Python docstrings all repeat it.

### Cross-field invariants (enforced, not documented-only)

1. `rtt_distance_mm` or `rtt_stddev_mm` present ⇒ `sensor_type == RTT`.
2. `sensor_type == GPS` ⇒ `latitude` and `longitude` both present.
3. `x_coordinate` or `y_coordinate` present ⇒ `building_id` present.
4. `confidence` ∈ [0,1] when present.
5. `observation_id` matches the UUID v4 pattern; upper-case hex is rejected rather than coerced, so
   that two encodings of one id can never both exist.

## 2. `sensor_type`

| Value | Produced by | Meaningful fields |
|---|---|---|
| `WIFI_SCAN` | Android `WifiManager` scan results | `ssid`, `bssid`, `rssi`, `frequency`, `channel`, `metadata.capabilities`, `metadata.result_freshness` |
| `WIFI_ASSOCIATION` | The observer's own connection state | `ssid`, `bssid`, `rssi`, `frequency`, `metadata.link_speed_mbps` |
| `BLE` | Android `BluetoothLeScanner`, iOS `CoreBluetooth` | `radio_identifier`, `rssi`, `tx_power`, `ble_service_uuid`, `manufacturer_data`, `metadata.ble_device_name` |
| `RTT` | Android `WifiRttManager` | `bssid`, `rtt_distance_mm`, `rtt_stddev_mm`, `rssi`, `metadata.rtt_num_successful` |
| `GPS` | `LocationProvider` | `latitude`, `longitude`, `horizontal_accuracy` |
| `ZONE_ANCHOR` | Master, when an administrator declares an infrastructure sighting | `radio_identifier`, `building_id`, `zone_id` |
| `MANUAL` | Administrator entry, e.g. "device physically seen here" | any |
| `IMPORT` | Rows synthesised from an external dataset | any; `metadata.import_source` required |

`ZONE_ANCHOR`, `MANUAL` and `IMPORT` records are still RAW and still immutable, but the Lab treats
them as a distinct evidence class with its own weight — a human assertion is not a radio measurement.

## 3. `identifier_type`

`WIFI_BSSID`, `WIFI_SSID`, `BLE_MAC_PUBLIC`, `BLE_MAC_RANDOM`, `BLE_SERVICE_UUID`, `BLE_IBEACON`,
`GNSS_FIX`, `OBSERVER_SELF`, `OTHER`.

`BLE_MAC_RANDOM` is assigned when the address is locally-administered (bit 0x02 of the first octet
set), which is how Android and iOS present privacy-rotating addresses. Randomized addresses are
recorded, but §5 forbids attributing them.

## 4. Identifier normalization

Deduplication and fingerprinting both depend on byte-identical identifiers, so normalization is
defined once and implemented once (`RadioIdentifierNormalizer`):

- MAC addresses: lowercase, colon-separated, zero-padded octets. `AA-BB-C-01-02-03` →
  `aa:bb:0c:01:02:03`.
- UUIDs: lowercase, hyphenated 8-4-4-4-12. 16-bit Bluetooth short UUIDs are expanded to the full
  Bluetooth base UUID so `0x180F` and its long form collapse to one identifier.
- Hex payloads (`manufacturer_data`): uppercase, no separators.
- SSIDs: preserved byte-for-byte, never trimmed or case-folded (trailing spaces are legitimate and
  distinguishing). A hidden network scans as an empty SSID, which is stored as `null`, not `""`.

## 5. Attribution rule

`target_device_id` is populated **only** when a `ManagedDevice` explicitly lists the observed
identifier in `known_wifi_identifiers`, `known_ble_identifiers` or `known_service_uuids`, or when an
administrator-authored attribution rule matches. In every other case it is `null` and the row is an
environmental RF observation.

The Collector may stamp `target_device_id` for identifiers in its local enrolled-device snapshot, but
the Master **re-evaluates attribution on import** against its own registry and records its own
verdict, because the Collector's snapshot may be stale. See
[`17-identity-and-attribution-policy.md`](17-identity-and-attribution-policy.md).

## 6. Reserved metadata keys

`metadata` is an open string map so the contract can grow without a schema major. Consumers **must
preserve unknown keys verbatim**. The keys below are reserved; producers must use these names rather
than inventing synonyms.

### Provenance (Collector sets these on every observation)
| Key | Example | Meaning |
|---|---|---|
| `session_id` | UUID | Collection session this sample belongs to |
| `platform` | `android` | |
| `os_version` | `34` | API level or iOS version |
| `device_model` | `Pixel 7a` | |
| `app_version` | `1.0.0` | |
| `installation_id` | UUID | Distinguishes reinstalls of the same observer id |

### Clock and freshness (required for the Lab's fusion windows)
| Key | Example | Meaning |
|---|---|---|
| `clock_elapsed_realtime_ms` | `918273645` | Monotonic clock at measurement; lets the Lab detect wall-clock jumps |
| `clock_boot_utc` | ISO-8601 | Wall clock at boot, so monotonic values are comparable across devices |
| `result_freshness` | `FRESH` \| `CACHED` \| `UNKNOWN` | Whether the scan result came from the scan that just completed |
| `scan_result_age_ms` | `2400` | Age of the scan result at record time |

### Wi-Fi detail
`capabilities`, `channel_width_mhz`, `center_freq0_mhz`, `center_freq1_mhz`, `wifi_standard`,
`is_passpoint`, `link_speed_mbps`, `is_connected`, `scan_trigger` (`PERIODIC`/`MANUAL`/`SURVEY`).

### BLE detail
`ble_device_name`, `ble_service_uuids` (comma-separated), `ble_service_data`, `ble_advertise_flags`,
`ble_primary_phy`, `ble_secondary_phy`, `ble_is_legacy`, `ble_is_connectable`, `ble_data_status`.

### RTT detail
`rtt_status`, `rtt_num_attempted`, `rtt_num_successful`, `rtt_rssi`, `rtt_is_80211mc`.

### Survey / ground truth
| Key | Example | Meaning |
|---|---|---|
| `sample_kind` | `ORDINARY` \| `GROUND_TRUTH` | Set to `GROUND_TRUTH` **only** by Survey Mode |
| `survey_point_id` | `B7_NORTH_DOOR` | |
| `survey_session_id` | UUID | One 60-second capture |
| `survey_operator` | `admin` | |

`sample_kind=GROUND_TRUTH` is the only channel through which a measurement can become calibration
data, and even then an administrator must promote the resulting fingerprint. Ordinary observations can
never acquire this key retroactively — the Master rejects an import that claims `GROUND_TRUTH` from a
non-survey session id.

### Degradation
| Key | Meaning |
|---|---|
| `permission_degraded` | `true` when a permission was missing and fields are consequently absent |
| `missing_permissions` | Comma-separated list |
| `throttled` | `true` when a scan request was denied by platform throttling |

## 7. Versioning and compatibility

`schema_version` is semantic:

- **Patch** — documentation, new reserved metadata key. All consumers accept.
- **Minor** — new optional top-level field. Older consumers accept and must preserve unknown fields
  when re-serializing.
- **Major** — a required field changes, or a field's meaning changes. Consumers **must reject**
  unknown majors with a clear error rather than guessing.

`SchemaVersion.isReadable(v)` in `core-model` and `schema_version.is_readable(v)` in the Lab implement
exactly this rule, and the Master's import preview shows the verdict before anything is written.

## 8. Example

```json
{
  "observation_id": "4f1c9a2e-6b3d-4c58-9e77-0a1b2c3d4e5f",
  "schema_version": "1.0.0",
  "timestamp_utc": "2026-09-14T08:19:04.312Z",
  "observer_id": "OBS-04",
  "observer_device_type": "ANDROID_PHONE",
  "sensor_type": "WIFI_SCAN",
  "target_device_id": null,
  "radio_identifier": "aa:bb:cc:11:22:33",
  "identifier_type": "WIFI_BSSID",
  "ssid": "SITE-INFRA-7",
  "bssid": "aa:bb:cc:11:22:33",
  "ble_service_uuid": null,
  "manufacturer_data": null,
  "rssi": -61,
  "tx_power": null,
  "frequency": 5180,
  "channel": 36,
  "rtt_distance_mm": null,
  "rtt_stddev_mm": null,
  "latitude": null,
  "longitude": null,
  "horizontal_accuracy": null,
  "building_id": "B7",
  "zone_id": "B7-CENTER",
  "x_coordinate": 24.5,
  "y_coordinate": 11.0,
  "confidence": 0.9,
  "metadata": {
    "session_id": "0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40",
    "platform": "android",
    "os_version": "34",
    "device_model": "Pixel 7a",
    "app_version": "1.0.0",
    "result_freshness": "FRESH",
    "scan_result_age_ms": "310",
    "capabilities": "[WPA2-PSK-CCMP][ESS]",
    "sample_kind": "GROUND_TRUTH",
    "survey_point_id": "B7_CENTER",
    "survey_session_id": "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b"
  }
}
```
