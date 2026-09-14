# Positioning Lab deliverables 5 & 6 — Benchmark methodology and error metrics

Implementation: `rfmapper_lab/benchmark/`. Run with
`python -m rfmapper_lab.cli benchmark --dataset <path>`.

## 1. Principle

No accuracy claim may be made without a number from a held-out test split. A more complex algorithm
is adopted only when it measurably beats the incumbent on the *same* held-out data, and the margin
must exceed the bootstrap confidence interval.

## 2. Harness

Every candidate implements the same interface, so the comparison is apples to apples:

```
for each algorithm A in registry:
    fingerprints = build(TRAINING, A.fingerprint_engine)
    for each held-out sample in TEST:
        estimate = A.estimate(vector(sample), fingerprints, reference_model)
        record(truth=sample.survey_point, estimate=estimate, cpu_time=...)
report(table, per_algorithm_metrics, bootstrap_CIs)
```

Rules that keep the comparison honest:

1. **Identical inputs.** Same TRAINING fingerprints, same TEST vectors, same reference model, same
   random seed. Enforced by a dataset content hash recorded in the report — a benchmark run against a
   different dataset cannot be compared by accident.
2. **TEST is touched once.** Tuning happens on VALIDATION. A hyperparameter chosen by looking at TEST
   makes TEST a training set.
3. **Split by survey session** (see deliverable 11 §5), never by sample.
4. **Report distributions, not means.** Median, P90, P95, plus the full CDF.
5. **Bootstrap confidence intervals** (2000 resamples over *sessions*) on every headline metric. A
   1-metre improvement with a ±3-metre CI is not an improvement.
6. **Record cost.** Median CPU time per estimate. A 5% accuracy gain for 50× the cost is usually a
   bad trade for a nightly batch over a full day of history.

## 3. Metrics

### Classification

| Metric | Definition | Why |
|---|---|---|
| Building accuracy | Fraction with correct `building_id` | Tier 2 is the tier that gets used daily |
| Zone accuracy | Fraction with correct `zone_id` | The primary product of the system |
| Zone top-2 accuracy | True zone in the top 2 candidates | Distinguishes "confused between two adjacent zones" from "lost" |
| Adjacent-error rate | Wrong zone but adjacent to truth | An adjacent-zone error is operationally mild; a cross-site error is serious |
| Confusion matrix | Per-zone | Identifies which specific zones are indiscriminable |

### Positional error

Error is Euclidean distance in the site frame between estimate and truth, over samples where the
algorithm emitted coordinates.

| Metric | Why |
|---|---|
| Median error | The typical case |
| P90, P95 error | The tail is what destroys operational trust |
| Max error | Worst case |
| Mean error | Reported **only** alongside the above, never alone |
| **Coverage** | Fraction of samples for which coordinates were emitted at all |
| Error CDF | The complete picture |

**Coverage must always be reported next to error.** An algorithm that answers 10% of the time with
3-metre error is not better than one that answers 95% of the time with 7-metre error, and reporting
only median error would say it was. The harness refuses to print an error figure without its
coverage.

Following the specification's own example, the report says:

```
Median error: 6.2 m    P90: 11.7 m    Coverage: 78%
```

and never `Accuracy ~ 6 m`.

### Uncertainty calibration

This is the metric that determines whether `horizontal_uncertainty_m` can be believed.

| Metric | Definition | Target |
|---|---|---|
| P68 containment | Fraction of samples where error ≤ reported $\sigma_h$ | ≈ 0.68 |
| P95 containment | Fraction where error ≤ 1.96 $\sigma_h$ | ≈ 0.95 |
| Calibration curve | Predicted vs observed error quantiles | Diagonal |
| Over/under-confidence ratio | Mean(error) / Mean($\sigma_h$) | ≈ 1.0 |

Under-reported uncertainty is the most damaging failure this system can have: a small circle on a map
is read as a fact. An algorithm with lower median error but badly calibrated uncertainty is
**rejected** in favour of one whose error bars are honest.

### Movement and transitions

Evaluated on the walk-test route, not on static surveys.

| Metric | Definition |
|---|---|
| Transition-detection latency | Median seconds between true and committed transition |
| False transition rate | Committed transitions per hour with no corresponding true transition |
| Missed transition rate | True transitions never committed |
| Oscillation rate | A→B→A commits within $T_{osc}$ (default 120 s) |
| Lost-device rate | Fraction of time a present device is in `LOST` |
| Topology violation rate | Committed transitions between non-adjacent zones |

Latency and false-transition rate trade directly against each other via the hysteresis thresholds.
The benchmark sweeps $(T_{min}, C_{min}, N_{min})$ and reports the curve, so the administrator
chooses the operating point explicitly rather than inheriting a developer's guess.

### Data quality (reported per run, not per algorithm)

Observations per observer per hour; fresh-vs-cached ratio; duplicate rate; clock-jump count;
unattributed-identifier share; calibration density per zone; sources per fingerprint; zones with no
ground truth.

## 4. Report format

`algorithm_report.json` plus a text table:

```
Dataset: site-2026-09  sessions=48  test_samples=1180  content_sha256=4a9f...
Splits: TRAIN=29 sessions  VAL=9  TEST=10   (split by session, seed=20260914)

algorithm                  zone_acc  bldg_acc  median_err  P90_err  coverage  P68_cont  cpu_ms
zone_nn_v1                    0.71      0.94       8.4 m   19.2 m      0.83      0.52     0.4
zone_wknn_v1 (k=5)            0.78      0.96       6.2 m   11.7 m      0.83      0.66     0.7
zone_cosine_v1                0.74      0.95       7.1 m   14.8 m      0.83      0.61     0.6
zone_bayes_v1                 0.81      0.97       6.0 m   11.1 m      0.79      0.71     1.9
zone_bayes_v1 + rtt           0.81      0.97       3.1 m    6.4 m      0.22      0.74     3.4

Bootstrap 95% CI on zone_acc: bayes_v1 [0.77, 0.85]  wknn_v1 [0.74, 0.82]  -> overlapping
Selected: zone_wknn_v1  (bayes_v1 not significantly better at 2.7x cost)
```

The last two lines are the point of the whole exercise. Overlapping intervals mean "not proven
better", and the cheaper, more interpretable algorithm wins by default.

Note also the RTT row: far better error, but 22% coverage. It is the right method *when it applies*,
which is why it is a conditional strategy rather than the default.

## 5. Regression gate

The selected configuration's metrics are committed as a baseline
(`positioning-lab/benchmarks/baseline.json`). A change that degrades zone accuracy, median error,
P90 error or P68 containment beyond the CI fails the check. Improvements update the baseline with the
run that produced them.

## 6. Synthetic validation

Before site data exists, the harness runs against the synthetic site simulator
(`rfmapper_lab/simulator/`), which generates a multi-building site with log-distance propagation plus
wall attenuation, per-observer RSSI bias, randomized-MAC churn, scan-cadence effects and clock skew.

What synthetic data is for, and what it is not for:

- **Valid:** proving the pipeline is correct end to end; proving an algorithm is implemented as
  specified; catching regressions; exercising the degenerate cases (collinear RTT anchors, a zone
  with no ground truth, an observer with a large offset).
- **Invalid:** any accuracy claim about the real site. The simulator generates data from the very
  propagation model the specification tells us not to trust indoors, so good synthetic numbers prove
  only internal consistency.

Every report generated from simulated data is stamped `dataset_kind: SYNTHETIC` and the text table
prints a warning banner. See
[`15-assumptions-requiring-validation.md`](15-assumptions-requiring-validation.md).
