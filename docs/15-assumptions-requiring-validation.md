# Positioning Lab deliverable 10 — Assumptions requiring validation with real site data

Every item here is currently a **guess**. None may be cited as a system property until it has a
measurement from the actual site. The parameter defaults in the code are placeholders chosen to be
defensible, not correct.

Status legend: `UNVALIDATED` · `IN PROGRESS` · `VALIDATED (<date>, <evidence>)`

## A. Radio environment

| # | Assumption | How to validate | Impact if wrong | Status |
|---|---|---|---|---|
| A1 | Enough APs are visible per location to discriminate zones (target ≥ 4 with $\pi>0.8$) | Count sources per survey point in the first sweep | Zone classification is impossible; would need added BLE anchors | `UNVALIDATED` |
| A2 | RSSI at a fixed point is stable enough over hours that a fingerprint survives the day ($\sigma \lesssim 5$ dB) | The 12-hour static test from the Milestone 1 protocol | Fingerprints decay within hours; would need continuous recalibration | `UNVALIDATED` |
| A3 | Adjacent zones are distinguishable in signal space | Leave-one-session-out self-consistency check (deliverable 11 §6) | Some zones must be merged; the zone model needs redesign | `UNVALIDATED` |
| A4 | AP infrastructure is stable over weeks | Daily drift detection over a month | Frequent resurveys; fingerprints have a short shelf life | `UNVALIDATED` |
| A5 | Buildings are RF-separable (building classification is the easy tier) | Building accuracy on the TEST split | If buildings are not separable, nothing finer will be | `UNVALIDATED` |
| A6 | Occupancy/machinery does not dominate the fingerprint | Survey the same points busy and empty | Condition-tagged fingerprints, or accept much larger uncertainty | `UNVALIDATED` |
| A7 | Inter-building paths produce a distinguishable transition signature | Walk-test analysis at the B7/B9 path | Transition detection will be late and noisy | `UNVALIDATED` |

**A2 is the load-bearing assumption of the entire fingerprint approach.** If a point's RSSI
distribution shifts materially between morning and afternoon, a once-surveyed fingerprint is not a
reference, and the architecture would need continuous reference measurement from fixed observers
instead. It is the first thing the static test should answer.

## B. Hardware and platform

| # | Assumption | How to validate | Impact if wrong | Status |
|---|---|---|---|---|
| B1 | Inter-device RSSI differences are a consistent offset correctable by a scalar $\delta_o$ | Co-located calibration, per-source spread analysis (deliverable 11 §4) | Per-device *per-band* or per-source correction, or fingerprints must be built per device model | `UNVALIDATED` |
| B2 | Wi-Fi scan cadence under throttling is sufficient (target ≥ 1 fresh scan / 30 s in foreground) | Measure `result_freshness` and `scan_result_age_ms` in the bench test | Sample rate too low for movement; must lean on BLE | `UNVALIDATED` |
| B3 | A foreground service survives a full working day on the target OEM phones | The 12-hour static test on each OEM | Need mains-powered fixed observers | `UNVALIDATED` |
| B4 | Clock skew between observers is small relative to the fusion window (< 1 s) | Co-observation offset analysis | Wider fusion windows, or explicit offset correction | `UNVALIDATED` |
| B5 | Any RTT hardware exists on site at all | Check `FEATURE_WIFI_RTT` on the observer phones and `is80211mcResponder` on the APs | Tier 4 precision is unreachable; the system tops out at approximate position | `UNVALIDATED` |
| B6 | BLE advertisement rate is manageable (< ~50/s aggregate) | Bench test counters | Back-pressure drops data; need filters | `UNVALIDATED` |
| B7 | 24 h dense collection fits in the assumed storage budget (~200 MB) | Measure database growth in the static test | Retention policy and on-device compression needed | `UNVALIDATED` |

**B5 determines whether precision Tier 4 exists on this site at all.** It is a five-minute check and
should be done before any RTT work is scheduled.

## C. Managed devices and identifiers

| # | Assumption | How to validate | Impact if wrong | Status |
|---|---|---|---|---|
| C1 | Enrolled devices emit observable, stable identifiers | Inspect what each enrolled device actually advertises | Devices are unobservable; enrollment needs dedicated BLE tags | `UNVALIDATED` |
| C2 | Enrolled devices advertise often enough for zone-level tracking (≥ 1 / 30 s) | Measure per-device observation intervals | Long `LOST` periods; coarser time resolution | `UNVALIDATED` |
| C3 | MAC randomization does not prevent tracking enrolled devices | Observe each enrolled device's identifier churn over hours | Must enroll tags with stable service UUIDs (the recommended procurement path) | `UNVALIDATED` |
| C4 | Cross-platform BLE joins via service UUID work in practice | Observe one tag from both Android and iOS | iOS observations cannot be fused with Android ones | `UNVALIDATED` |

## D. Algorithms and parameters

All parameter defaults below are placeholders. Each needs a tuning run on the VALIDATION split.

| # | Parameter | Default | Basis | Status |
|---|---|---|---|---|
| D1 | Fusion window $w$ | 5 s | Guess from expected scan cadence | `UNVALIDATED` |
| D2 | WKNN $k$ | 5 | Convention | `UNVALIDATED` |
| D3 | Source-mismatch penalty $\lambda$ | 6 dB-equivalent | Guess | `UNVALIDATED` |
| D4 | Hysteresis $T_{min}$ | 45 s | Guess; trades latency vs false transitions | `UNVALIDATED` |
| D5 | Hysteresis $C_{min}$ | 0.6 | Guess | `UNVALIDATED` |
| D6 | Hysteresis $N_{min}$ | 3 observations | Guess | `UNVALIDATED` |
| D7 | $T_{lost}$ | 300 s | Guess | `UNVALIDATED` |
| D8 | Freshness decay half-life | 30 s | Guess | `UNVALIDATED` |
| D9 | CACHED-sample weight multiplier | 0.3 | Guess | `UNVALIDATED` |
| D10 | Survey duration | 60 s | From the specification | `UNVALIDATED` |
| D11 | Sessions required to promote a fingerprint | 3 | Judgement | `UNVALIDATED` |
| D12 | Log-distance exponent $n$ (if RSSI ranging is ever enabled) | **none** | Deliberately absent; must be site-measured | `UNVALIDATED` |
| D13 | Confidence factors are neutral for an association-only vector (`observer_count`, `calibration_density`) | neutral | Reasoned, not measured: a device holds one association at a time, so corroboration is unobtainable rather than absent, and no fingerprint stands behind the zone | `UNVALIDATED` |
| D14 | Hysteresis $C_{min}$ for an association-only site | **none fit for purpose** | The 0.6 of D5 was chosen for multi-observer fingerprinting and this evidence does not reach it; see [`19`](19-association-only-deployment.md) §4 | `UNVALIDATED` |

D12 is intentionally left with no default. Shipping a default path-loss exponent would invite its use,
and an uncalibrated indoor path-loss model is exactly the fabricated-precision failure both
specifications prohibit. The feature stays disabled until $n$ is measured on site.

D13 corrects a category error rather than tuning a number: the two factors were measuring
properties that association evidence cannot exhibit, in the way `rtt_quality` is already neutral
for a method that never claimed a range. That the correction is the right *shape* does not make the
resulting confidences calibrated, which is why it is listed here. D14 is the consequence — the
resulting values still sit below D5, so such a site records which tower each device is on and no
movement between towers until $C_{min}$ is set from a walk test. The run reports the shortfall and
the highest confidence reached instead of failing silently.

## E. Achievable accuracy — explicitly unknown

| # | Assumption | Status |
|---|---|---|
| E1 | Building classification ≳ 95% | `UNVALIDATED` |
| E2 | Zone classification ≳ 75% | `UNVALIDATED` |
| E3 | Median horizontal error ≲ 10 m where coordinates are emitted | `UNVALIDATED` |
| E4 | P90 horizontal error ≲ 20 m | `UNVALIDATED` |
| E5 | Transition-detection latency ≲ 60 s | `UNVALIDATED` |
| E6 | False transition rate ≲ 1/hour/device | `UNVALIDATED` |
| E7 | Tower attribution correctness on an association-only site | `UNVALIDATED` |

E3 and E4 do not apply to an association-only deployment at all: it emits no coordinates, so there
is no horizontal error to measure. E7 is its equivalent and is a classification question — how often
the named tower is the right one — measurable from a walk test recording which tower a device was
genuinely nearest, without a survey.

**These are targets, not claims.** The numbers in the specification's illustrative examples
("Confidence 87%", "±8 m", "median 6.2 m") are *format* examples showing how to report results, not
predictions about this site. Nothing in this repository may present them as expected performance.

## F. Operational

| # | Assumption | Status |
|---|---|---|
| F1 | Daily manual export/import is sustainable for the operators | `UNVALIDATED` |
| F2 | An administrator will actually review and promote calibration candidates | `UNVALIDATED` |
| F3 | Quarterly resurvey is achievable with available staff | `UNVALIDATED` |
| F4 | Observer phones stay at their declared locations | `UNVALIDATED` — mitigated by the `OBSERVER_RELOCATED` drift flag |

F2 matters more than it looks. The entire ground-truth safeguard rests on a human making promotion
decisions. If that review never happens in practice, the system will slowly stop improving — which is
the correct failure mode (it degrades to stale-but-honest rather than self-corrupting), but it should
be a conscious choice rather than a surprise.

## Validation-first sequence

The cheapest assumptions that would most change the design, in order:

1. **B5** — does any RTT hardware exist? (minutes)
2. **A1** — how many APs are visible per location? (one survey sweep)
3. **B2** — what is the real scan cadence? (one bench test)
4. **A2** — are fingerprints stable over a day? (one overnight static test)
5. **B1** — is inter-device RSSI a simple offset? (one co-located calibration)
6. **A3** — are adjacent zones separable? (first full survey sweep + self-consistency check)
7. **C1/C2/C3** — are the enrolled devices actually observable? (one bench test with the real devices)

Every one of these is answered by the Milestone 1 field protocol
([`09-milestone-1-implementation-plan.md §4`](09-milestone-1-implementation-plan.md)). That is why
Milestone 1 is a *collection* milestone and contains no positioning mathematics: the mathematics
cannot be designed responsibly until these seven answers exist.
