# Identity and attribution policy

This document specifies the rules that keep the system inside its authorized scope. They are
implemented as code and tests, not as guidance.

## 1. Scope statement

RFMapper monitors a **closed set** of administrator-enrolled devices and administrator-controlled
infrastructure within a privately administered environment. Its objective is **not** universal
identification of arbitrary people or devices.

Unknown radio observations may be collected as **environmental RF observations** where platform
permissions permit. The software must not automatically claim that an unknown or randomized
identifier belongs to a particular person or physical device.

## 2. The three classes of radio identifier

| Class | Definition | Permitted use |
|---|---|---|
| **ENROLLED** | Explicitly listed on a `ManagedDevice` by an administrator | Attribution, positioning, movement history |
| **INFRASTRUCTURE** | Explicitly listed on an `InfrastructureNode` | Reference signal source for fingerprints and zone evidence |
| **ENVIRONMENTAL** | Everything else | Statistical RF context only. Never attributed, never positioned, never named |

Classification is a lookup against the REFERENCE registry. There is no fourth class, and there is no
heuristic promotion path between them.

## 3. Attribution rules

`target_device_id` is set **only** when:

1. The normalized `radio_identifier` appears in `ref_device_identifier` for that device, **or**
2. An administrator-authored attribution rule matches (§4), **or**
3. An administrator manually attributes a specific observation (recorded with
   `metadata.attribution_source=MANUAL` and the operator's identity).

In every other case `target_device_id` is `null`, and the row is an environmental observation.

### What is explicitly forbidden

| Forbidden inference | Why |
|---|---|
| Merging a randomized MAC into a managed device because it appeared where that device was | Co-location is not identity. Two devices in one room would be merged into one. |
| Linking randomized MACs to each other by timing, RSSI or advertisement similarity | This is de-anonymization of unenrolled devices — outside authorized scope regardless of accuracy. |
| Treating a recurring unknown identifier as "probably the same device" | It may be, but the system has no authority to assert it. |
| Inferring a person from a device | The system tracks enrolled devices, not people. No field anywhere in the schema names a person. |
| Attributing on SSID alone | SSIDs are neither unique nor authenticated. |

The first row is the one that would be easiest to implement and hardest to defend. A randomized MAC
that consistently appears alongside an enrolled device is *evidence* of a relationship, and a human
administrator may act on it by creating an explicit enrollment. The software does not.

## 4. Administrator attribution rules

Rules are explicit REFERENCE data, authored by a human, auditable, and reversible:

| Rule type | Matches on | Typical use |
|---|---|---|
| `EXACT_IDENTIFIER` | Exact normalized identifier | Fixed-MAC devices, BLE tags |
| `SERVICE_UUID` | `ble_service_uuid` | **Recommended.** A tag advertising a dedicated service UUID is reliably attributable across platforms and survives MAC randomization |
| `MANUFACTURER_DATA_PREFIX` | Prefix of `manufacturer_data` | iBeacon major/minor, vendor-specific payloads |
| `MAC_PREFIX` | OUI prefix | **Discouraged.** Identifies a vendor, not a device. Permitted only with `status=INFRASTRUCTURE` |
| `SSID_ASSOCIATION` | `WIFI_ASSOCIATION` to a controlled SSID | Only combined with another rule; never alone |

Each rule records who created it, when, and why. Each carries a `confidence_class`
(`DEFINITIVE` | `STRONG` | `WEAK`), and observations attributed by a `WEAK` rule are marked
`metadata.attribution_confidence=WEAK` so the Lab can down-weight or exclude them.

Guidance for the administrator: prefer `SERVICE_UUID`. It is the only rule type that works across
Android and iOS, survives MAC randomization, and is unambiguous.

## 5. Re-evaluation on import

The Collector may stamp `target_device_id` from its local enrolled-device snapshot, which can be
stale. On import, the Master **re-evaluates attribution against its own current registry** and:

- records its own verdict in `target_device_id`,
- preserves the Collector's claim in `metadata.collector_claimed_device_id` when the two differ,
- raises an `ATTRIBUTION_DISAGREEMENT` quality flag.

The raw measurement fields are never altered — only the Master's attribution verdict is authoritative,
and the disagreement is visible rather than silently resolved.

## 6. Unknown identifiers: what *is* allowed

Environmental observations are genuinely useful and are retained:

- As **reference signal sources** for fingerprints when they turn out to be stable APs (an
  administrator may then enroll them as `InfrastructureNode`s).
- As **environment statistics**: device density per zone, interference and drift detection.
- As **drift evidence**: a previously stable unknown AP disappearing is a real RF-environment change.

All of these are aggregate or infrastructure uses. None involves naming, tracking or attributing an
unenrolled device.

## 7. UI obligations

| Requirement | Reason |
|---|---|
| Unattributed observations display as the identifier only, never as a device or person | The UI must not imply an attribution the data layer refused to make |
| `BLE_MAC_RANDOM` identifiers are visually marked as randomized | A random MAC is not a durable identity, and the operator must see that |
| A position estimate is shown only for a `ManagedDevice` | Positioning is defined only for enrolled devices |
| Uncertainty is always displayed with a position | A bare point on a map reads as a fact |
| Zone-only estimates render as a zone highlight, never as a point | Drawing a point where only a zone is known is fabricated precision |
| Confidence is shown with its contributing factors available on demand | "87%" must be explainable |
| Attribution provenance is inspectable per observation | An operator must be able to ask "why is this attributed to DEVICE-03?" |

## 8. Retention

| Data | Default retention |
|---|---|
| Raw observations of ENROLLED identifiers | Administrator-configured; no default deletion |
| Raw observations of INFRASTRUCTURE identifiers | Same |
| Raw observations of ENVIRONMENTAL identifiers | **90 days by default**, then purged by `RetentionDao` |
| Derived estimates | Deletable per `algorithm_version` |
| Fingerprints | Versioned, retained (`RETIRED`, not deleted) |

Environmental data has a shorter default retention because its legitimate purposes — fingerprint
reference, drift detection, density statistics — are all served by recent data. Indefinitely retaining
observations of unenrolled devices would accumulate a dataset with no authorized use, which is worth
avoiding on its own terms.

## 9. Enforcement in code

| Rule | Enforcement |
|---|---|
| No automatic randomized-MAC merging | `AttributionEngine` accepts only registry lookups and explicit rules. No identifier-similarity code path exists. |
| Attribution is registry-driven | `ref_device_identifier` composite PK: one identifier maps to at most one device |
| Environmental rows stay unattributed | `AttributionEngineTest` asserts null for unenrolled identifiers, including ones repeatedly co-observed with an enrolled device |
| Position estimates only for managed devices | `PositionEstimate.device_id` is validated against the managed-device registry on construction |
| No coordinate without uncertainty | Constructor assertion in Kotlin and Python |
| Tier 4 requires RTT | Invariant 4 in [`13-derived-output-schema.md`](13-derived-output-schema.md), asserted on construction |
| No person fields | No schema in `schema/` contains a personal-identity field, by design |
