# Positioning Lab deliverable 9 — Algorithm versioning strategy

The architectural requirement is explicit: it must be possible to replace `PositioningEngineV1` with
`PositioningEngineV2` and recompute historical estimates from stored RAW observations.

## 1. What is versioned, independently

| Version | Scope | Example |
|---|---|---|
| `schema_version` | The data contract (observation, estimate, package) | `1.0.0` |
| `app_version` | Collector / Master binaries | `1.0.0` |
| `fingerprint_engine` | Fingerprint construction | `1.2.0` |
| `zone_engine` | Zone classification | `1.1.3` |
| `positioning_engine` | Coordinate estimation + uncertainty | `1.0.0` |
| `movement_engine` | Temporal filtering, hysteresis, movement | `0.8.2` |
| `algorithm_version` | The composite pipeline identity | `pipeline-1.0.0` |
| `reference_model_id` | The site model snapshot used | `site-2026-09-01` |
| `calibration_set_id` | The observer-offset set used | `cal-2026-09-01` |

These are deliberately separate. A fingerprint-engine improvement should not force a movement-engine
version bump, and the only way to know *which* change moved a metric is for the versions to move
independently.

`algorithm_version` is the composite handle used for filtering generations in the UI. Its full
definition is the tuple of the four engine versions plus the parameter-set hash, recorded in
`engine_versions` and `parameter_set_sha256` on every row. Two pipeline runs with the same
`algorithm_version` but different parameters are therefore distinguishable — which matters, because a
retuned hysteresis threshold changes results without changing any code.

## 2. Semantics

- **Patch** — refactor, performance, bug fix with *no* change to output on any test input. A patch
  bump that changes output is a bug; the determinism test catches it.
- **Minor** — output may change, method identity does not. New evidence source, retuned default.
- **Major** — different method. `zone_wknn_v1` → `zone_bayes_v1` is a major change, and the algorithm
  *id itself* carries the `_v1` suffix so the two coexist rather than one replacing the other in
  place.

Algorithm ids are permanent. `zone_nn_v1` keeps its name forever; an improved nearest-neighbour is
`zone_nn_v2`, registered alongside. This is what lets a benchmark compare them and a historical
estimate remain explicable.

## 3. Every derived record is self-describing

Each row in `position_estimates`, `zone_transitions` and `movement_estimates` carries
`algorithm_version`, `engine_versions`, `method`, `source_dataset_ids`, `reference_model_id`,
`calibration_set_id` and `parameter_set_sha256`.

Given a single estimate row, the exact computation that produced it can be reconstructed: which RAW
observations (`supporting_observation_ids`), which reference model, which calibration, which code
versions, which parameters. That is the whole point — an estimate that cannot be explained cannot be
defended.

## 4. Generations, not overwrites

```
der_position_estimate
  (device D17, 2026-09-14T08:24, algorithm_version=pipeline-1.0.0) -> B7-SOUTH,  8.4 m
  (device D17, 2026-09-14T08:24, algorithm_version=pipeline-1.1.0) -> B7-SOUTH,  5.1 m
```

Both rows persist. The Master's UI has an active-generation selector; the default is the newest
generation that the administrator has marked active. Deleting a generation is an explicit
administrative action (`DELETE BY algorithm_version`), never a side effect of a new run.

This also makes A/B comparison over real history free: run two pipeline versions over the same date
range and diff the generations.

## 5. Reprocessing

```bash
python -m rfmapper_lab.cli reprocess \
    --from 2026-09-01 --to 2026-09-14 \
    --raw-store ./raw \
    --reference site-2026-09-01 \
    --zone-engine zone_bayes_v1 \
    --positioning-engine pos_wknn_centroid_v1 \
    --movement-engine movement_hysteresis_v1 \
    --out ./derived
```

Guarantees:

1. Reads only RAW + REFERENCE. Never reads a previous DERIVED generation, so an error in one
   generation cannot propagate into the next.
2. Writes a new generation; touches nothing existing.
3. Deterministic: same inputs + same versions + same parameters ⇒ byte-identical output. Any RNG use
   takes an explicit seed recorded in the manifest.
4. Pinning the `reference_model_id` reproduces a historical run exactly; using the current model
   answers the different question of "what would we conclude today".

Point 4 is a real distinction. Reproducing an old result requires the *old* site model; re-judging
old data requires the *new* one. The CLI requires the choice to be explicit rather than defaulting.

## 6. Compatibility rules

| Change | Required action |
|---|---|
| New optional observation field | `schema_version` minor; Lab ignores it until used |
| New required observation field | `schema_version` major; Lab rejects older packages explicitly |
| New algorithm | New id with a `_vN` suffix; register; benchmark before adoption |
| Retuned default parameter | Engine minor bump; `parameter_set_sha256` changes |
| Site model change (new zone, moved AP) | New `reference_model_id`; affected dates reprocessed |
| New calibration measurement | New `calibration_set_id`; old estimates keep the old id |

## 7. Gate on adoption

A new algorithm becomes the default only after:

1. Benchmark on the held-out TEST split shows improvement beyond the bootstrap CI
   (deliverable 12).
2. Uncertainty calibration (P68/P95 containment) is no worse.
3. Computational cost is acceptable for a full-history reprocess.
4. The decision and its numbers are recorded in `algorithm_report.json` and in an ADR under
   `docs/adr/`.

Absent all four, the incumbent stays. "It looks better on the map" is not evidence.
