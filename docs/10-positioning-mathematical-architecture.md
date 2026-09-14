# Positioning Lab deliverable 1 — Mathematical architecture

Implementation: [`../positioning-lab`](../positioning-lab), package `rfmapper_lab`.

## 1. Pipeline

```
RAW observation packages ─┐
                          ├─► PHASE 1  parse + normalize + global dedup
REFERENCE site model ─────┘              │
                                         ▼
                            PHASE 2  data-quality analysis  ──► quality_report.json
                                         │
                                         ▼
                            PHASE 3  ground-truth ingestion (sample_kind=GROUND_TRUTH only)
                                         │
                                         ▼
                            PHASE 4  statistical fingerprints per survey point
                                         │
                            PHASE 5  zone classifier  ◄── live observation vectors
                                         │
                            PHASE 6  multi-observer fusion (time windows)
                                         │
                            PHASE 7  approximate position (fingerprint centroid / RTT multilateration)
                                         │
                            PHASE 8  uncertainty + confidence
                                         │
                            PHASE 9  temporal filtering, hysteresis, movement
                                         │
                                         ▼
                            PHASE 11 DERIVED_<date>.zip  ──► Master import
```

Every phase is a pure function of its inputs plus a versioned parameter set. No phase mutates RAW.
Re-running the pipeline over the same inputs with the same versions must produce byte-identical
output — enforced by a determinism test.

## 2. Notation

- Observers $o \in O$, each with known position $\mathbf{p}_o$ (when surveyed) and optional RSSI
  offset $\delta_o$ dB.
- Radio sources $s \in S$ (APs by BSSID, BLE anchors by identifier).
- A **live vector** for target device $d$ at time $t$ within fusion window $\pm w$:
  $$\mathbf{v}_{d,t} = \{(o, s, \tilde r_{o,s}) \mid |t_{obs} - t| \le w\}$$
  where $\tilde r = r + \delta_o$ is the normalized RSSI and $r$ the raw RSSI.
- A **fingerprint** at surveyed location $\ell$ is a set of per-source distributions
  $$F_\ell = \{(s, \mu_s, \tilde\mu_s, \sigma_s, q^{10}_s, q^{90}_s, \pi_s, n_s)\}$$
  with $\mu$ median, $\tilde\mu$ mean, $\sigma$ standard deviation, $q$ percentiles,
  $\pi_s$ **visibility probability** (fraction of survey samples in which $s$ was seen), $n_s$ sample
  count.

Distributions, not point values. A source seen at −60 dBm with $\sigma = 2$ carries far more
information than one seen at −60 dBm with $\sigma = 11$, and a single-value fingerprint throws that
away.

## 3. Daily workflow

1. **Ingest** packages `OBS07_<date>`, `OBS04_<date>`, … Validate each against the schema; reject a
   package with an unreadable `schema_version` major.
2. **Global dedup** on `observation_id`. First occurrence wins; collisions with differing content are
   reported as `DUPLICATE_ID_CONTENT_MISMATCH` (a genuine anomaly, not a routine duplicate).
3. **Normalize**: identifiers, timestamps to UTC epoch ms, RSSI to $\tilde r$ (raw always preserved).
4. **Quality analysis** → `quality_report.json`.
5. **Fingerprints** rebuilt from `GROUND_TRUTH` samples only.
6. **Infer** zone → position → uncertainty → movement for each managed device.
7. **Export** `DERIVED_<date>.zip`.

## 4. Time synchronization

Collector wall clocks differ. Each observation carries `clock_elapsed_realtime_ms` (monotonic) and
`clock_boot_utc`.

- **Wall-clock jump detection:** within one session, the residual
  $\epsilon = (t_{wall} - t^{0}_{wall}) - (t_{mono} - t^{0}_{mono})$ should be ~0. A step change
  means the wall clock was adjusted mid-session; affected rows are flagged `CLOCK_JUMP` and
  down-weighted rather than discarded.
- **Inter-observer offset:** estimated from co-observations — when two observers record the *same*
  identifier in a genuinely simultaneous burst, the median difference in reported timestamps across
  many such bursts estimates the relative offset. This is only reported as an advisory flag; it is
  never applied automatically, because a real offset and a real propagation difference are not
  distinguishable from timestamps alone.
- **Fusion windows** $w \in \{2, 5, 10\}$ s, configurable per sensor type. Wi-Fi scans need the wider
  window (scan cadence is measured in seconds); BLE advertisements support the narrow one. Two
  observations are **never** treated as simultaneous merely because they are adjacent in a file.

## 5. Signal normalization

$\tilde r_{o,s} = r_{o,s} + \delta_o$, with $\delta_o = 0$ unless an empirically measured offset
exists in `ref_observer_calibration`. Measurement procedure is in
[`11-ground-truth-and-calibration-procedure.md`](11-ground-truth-and-calibration-procedure.md).

Raw and normalized values are both retained. Every derived record references the calibration set that
was in force, so an estimate is reproducible even after a calibration is revised.

## 6. Candidate algorithms

All implement a common interface and are selected by benchmark, not by preference.

### Zone classification

| Id | Method | Description |
|---|---|---|
| `zone_nn_v1` | Nearest neighbour | Minimize signal-space distance to each fingerprint; take that fingerprint's zone. The RADAR baseline. |
| `zone_wknn_v1` | Weighted KNN | $k$ nearest fingerprints, weight $1/(\text{distance}+\epsilon)$, vote on zone. |
| `zone_cosine_v1` | Cosine similarity | Treat the shared-source RSSI vector as a direction; robust to a uniform gain offset, which is exactly the uncalibrated-chipset failure mode. |
| `zone_bayes_v1` | Naive Bayes | $P(\ell \mid \mathbf v) \propto P(\ell)\prod_s P(\tilde r_s \mid \ell)$ with $P(\cdot\mid\ell)$ Gaussian from $(\mu_s,\sigma_s)$, plus an explicit visibility term for sources *absent* from the live vector. |
| `zone_anchor_v1` | Zone-anchor evidence | Visibility/association/duration evidence from `ZONE_ANCHOR` infrastructure; coarse, and never produces coordinates. |

**Signal-space distance** over the union of sources, with an explicit penalty for non-overlap:

$$D(\mathbf v, F_\ell) = \sqrt{\frac{1}{|S_\cap|}\sum_{s \in S_\cap}\!\left(\tilde r_s - \mu_{s,\ell}\right)^2} \;+\; \lambda \!\!\sum_{s \in S_\triangle}\!\! \pi_{s,\ell}$$

where $S_\cap$ are sources in both, $S_\triangle$ the symmetric difference, and $\lambda$ the
mismatch penalty. The second term is the part naive implementations omit: **the absence of an AP that
a location reliably sees is strong evidence against that location.** Missing sources are handled by
visibility probability rather than by substituting a floor RSSI, which would invent a measurement.

The Bayesian matcher's absent-source term is explicit:
$P(\text{absent} \mid \ell, s) = 1 - \pi_{s,\ell}$, floored at a small $\epsilon$ so one unexpected
absence cannot zero out an otherwise strong match.

### Position estimation

| Id | Method | When |
|---|---|---|
| `pos_wknn_centroid_v1` | Uncertainty-weighted centroid of the $k$ best fingerprint coordinates | Default when ≥2 fingerprints in the winning zone have coordinates |
| `pos_rtt_multilateration_v1` | Robust least squares on RTT ranges | ≥3 RTT anchors with known positions and compatible geometry |
| `pos_zone_centroid_v1` | Zone polygon centroid, uncertainty = zone radius | Fallback; explicitly coarse |
| `pos_zone_only_v1` | No coordinates at all | When evidence supports only a zone. **This is a valid, preferred result.** |

RTT multilateration minimizes $\sum_i \left(\|\mathbf x - \mathbf a_i\| - d_i\right)^2 / \sigma_i^2$
with a soft-L1 loss (`scipy.optimize.least_squares`), weighting each anchor by its reported
`rtt_stddev_mm`. Geometry is checked before trusting the result: if the anchors are near-collinear
(condition number of the Jacobian above a threshold), the along-baseline direction is unconstrained
and the method declines rather than returning a confident wrong answer.

**RSSI-to-distance is not used as a positioning method.** A calibrated log-distance model
$\tilde r = r_0 - 10n\log_{10}(d/d_0)$ is available as *weak supporting evidence only*, with
site-measured $n$, and it is disabled by default. Free-space propagation indoors is not assumed
anywhere.

### Multi-observer fusion (Phase 6)

Fuse, never argmax. For each candidate location $\ell$, combine per-observer evidence with weights

$$W_{o} = w^{\text{fresh}}_{o} \cdot w^{\text{cal}}_{o} \cdot w^{\text{sensor}}_{o} \cdot w^{\text{count}}_{o}$$

- $w^{\text{fresh}}$: exponential decay in measurement age, plus a hard multiplier for
  `result_freshness=CACHED` — a 30-minute-old cached scan must not weigh as much as a fresh one.
- $w^{\text{cal}}$: lower when the observer has no measured calibration offset.
- $w^{\text{sensor}}$: `RTT` > `BLE` ≈ `WIFI_SCAN` > `WIFI_ASSOCIATION` > `ZONE_ANCHOR` > `MANUAL`.
- $w^{\text{count}}$: $\sqrt{n}$ in the number of independent samples in the window, capped.

The posterior over locations is $P(\ell) \propto P_0(\ell) \prod_o L_o(\ell)^{W_o}$, with $P_0$ from
the previous state and site topology. Selecting the strongest-RSSI observer is explicitly not a
method in this system.

## 7. Hierarchical output

Level 1 site presence → 2 building → 3 zone → 4 approximate X/Y → 5 higher precision. The engine emits
the deepest level the evidence supports and stops there. `x`/`y` stay null when only a zone is
supported; a confident zone beats a fabricated coordinate.

## 8. Uncertainty (Phase 8)

$\sigma_h$ is computed, not assigned:

| Method | $\sigma_h$ |
|---|---|
| `pos_rtt_multilateration_v1` | From the least-squares covariance $\sigma^2 (J^\top J)^{-1}$, inflated by the residual scale, floored by the empirical RTT bias |
| `pos_wknn_centroid_v1` | Weighted spatial spread of the contributing fingerprints, plus the **empirical** P68 error of this method from the held-out test split |
| `pos_zone_centroid_v1` | Radius of the smallest circle enclosing the zone polygon |
| `pos_zone_only_v1` | Null coordinates, so no $\sigma_h$; the zone *is* the answer |

The empirical term is the one that matters. A geometric spread calculation says how *consistent* the
fingerprints were, not how *accurate* the method is. Only held-out ground truth can supply the
latter, so the reported uncertainty is $\sqrt{\sigma^2_{\text{geometric}} + \sigma^2_{\text{empirical}}}$
and the empirical component comes from the benchmark harness. Before a site has been validated, the
engine reports the geometric term and flags `UNVALIDATED_UNCERTAINTY`.

## 9. Confidence

Confidence is a bounded product of named, inspectable factors — never a constant:

$$C = \operatorname{clip}\!\left(\prod_i f_i^{\,\alpha_i},\; 0,\; 1\right)$$

with factors for observer count, measurement freshness, fingerprint-similarity margin (the *gap*
between the best and second-best candidate, which is what actually indicates discriminability),
sensor agreement, RTT quality, historical continuity, topological plausibility, and local calibration
density. Every `PositionEstimate` carries the factor breakdown in its `method` detail, so
"confidence 0.87" can always be explained.

## 10. Temporal filtering and hysteresis (Phase 9)

Baselines first: rolling median over the last $m$ estimates for coordinates, EMA for confidence.
Kalman filtering and an HMM zone model are registered as candidates but remain **disabled until the
benchmark shows the baseline is the binding constraint.**

Hysteresis state machine per device — this is what prevents B7 → B9 → B7 → B9 oscillation:

```
state = (CURRENT_ZONE, CANDIDATE_ZONE, CANDIDATE_DURATION, TRANSITION_CONFIDENCE)

per new estimate z:
  if z == CURRENT_ZONE:      decay CANDIDATE_DURATION toward 0
  elif z == CANDIDATE_ZONE:  CANDIDATE_DURATION += dt;  accumulate TRANSITION_CONFIDENCE
  else:                      CANDIDATE_ZONE = z; reset duration/confidence

  commit when CANDIDATE_DURATION >= T_min
         and  TRANSITION_CONFIDENCE >= C_min
         and  supporting_observations >= N_min
         and  topology permits CURRENT_ZONE -> CANDIDATE_ZONE
```

Defaults $T_{min} = 45$ s, $C_{min} = 0.6$, $N_{min} = 3$ — placeholders to be tuned against the
walk-test ground truth from the Milestone 1 field protocol, trading transition-detection latency
against false-transition rate. A single noisy observation can never commit a transition.

## 11. Movement and topology

States: `STATIONARY`, `MOVING`, `ZONE_TRANSITION`, `LOST`, `REAPPEARED`, `UNCERTAIN`.
`LOST` after no supporting observation for $T_{lost}$; `REAPPEARED` on the first estimate after
`LOST`; `UNCERTAIN` when evidence conflicts (for example two observers implying non-adjacent zones
with comparable weight).

The site graph (`ref_zone_edge`) supplies adjacency, traversal times and impossible transitions. A
committed transition between non-adjacent zones does not get rewritten — it is emitted with
`UNCERTAIN` and a `TOPOLOGY_VIOLATION` quality flag, because topology is a model and overwhelming
sensor evidence may mean the model is wrong (a new door, a relocated AP). Topology never silently
overrides measurement.

`direction` is the pair of spatial regions traversed, not a heading, unless RTT or a genuine
coordinate sequence supports a vector.

## 12. Drift detection (Phase 2 / daily)

Comparing today's distributions against the fingerprint baseline flags, without retraining:

| Flag | Trigger |
|---|---|
| `AP_DISAPPEARED` | A source with $\pi > 0.9$ in a ground-truth fingerprint is absent all day |
| `AP_RELOCATED` | Its RSSI distribution shifts materially at several locations at once |
| `OBSERVER_RSSI_OFFSET` | One observer's shared-source RSSI differs systematically from the others |
| `OBSERVER_RELOCATED` | An observer's own fingerprint no longer matches its declared zone |
| `NEW_INTERFERENCE` | Variance inflation without a median shift |
| `SPARSE_CALIBRATION` | A zone with observations but no nearby ground truth |
| `POSSIBLE_RF_ENVIRONMENT_CHANGE` | Several of the above together |

All flags are advisory and require administrator acknowledgement. Nothing retrains automatically.

## 13. Replaceability

```python
class ZoneClassifier(Protocol):
    version: str
    def classify(self, vector: LiveVector, model: ReferenceModel) -> ZoneResult: ...
```

Same shape for `FingerprintEngine`, `PositioningEngine`, `UncertaintyEstimator`, `MovementEngine`,
`TemporalFilter`. Implementations register in a registry keyed by version id, the pipeline takes the
ids as configuration, and every output row records which ids produced it. `reprocess` re-runs any
historical date range with new versions from RAW + REFERENCE alone.
