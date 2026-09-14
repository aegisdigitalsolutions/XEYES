# Deliverable 0 — Index of first engineering deliverables

The master prompt requires deliverables A–J before production code, and the Positioning & Fusion
Engine brief requires its own ten-item first deliverable. This index maps both lists to documents in
this repository.

## Master prompt §28 — "First task for the AI development team"

| Req. | Deliverable | Document |
|---|---|---|
| A | Repository / module architecture | [`01-repository-architecture.md`](01-repository-architecture.md) |
| B | Canonical Observation JSON schema | [`02-observation-schema.md`](02-observation-schema.md) + [`../schema/observation.schema.json`](../schema/observation.schema.json) |
| C | CSV column specification | [`03-csv-column-specification.md`](03-csv-column-specification.md) |
| D | Room entity / DAO design | [`04-room-entity-dao-design.md`](04-room-entity-dao-design.md) |
| E | Android permission matrix by version | [`05-android-permission-matrix.md`](05-android-permission-matrix.md) |
| F | iOS capability / limitation matrix | [`06-ios-capability-matrix.md`](06-ios-capability-matrix.md) |
| G | Libraries / dependencies and licenses | [`07-dependency-license-inventory.md`](07-dependency-license-inventory.md) |
| H | Open-source projects worth studying + licenses | [`08-open-source-reference-inventory.md`](08-open-source-reference-inventory.md) |
| I | Milestone 1 implementation plan | [`09-milestone-1-implementation-plan.md`](09-milestone-1-implementation-plan.md) |
| J | Then implement the Android Collector | [`../android/collector-app`](../android/collector-app) |

## Positioning & Fusion Engine §28 — first deliverable

| Req. | Deliverable | Document |
|---|---|---|
| 1 | Mathematical architecture | [`10-positioning-mathematical-architecture.md`](10-positioning-mathematical-architecture.md) |
| 2 | Required input schema | [`02-observation-schema.md`](02-observation-schema.md), [`03-csv-column-specification.md`](03-csv-column-specification.md) |
| 3 | Ground-truth / calibration procedure | [`11-ground-truth-and-calibration-procedure.md`](11-ground-truth-and-calibration-procedure.md) |
| 4 | Candidate algorithms | [`10-positioning-mathematical-architecture.md §6`](10-positioning-mathematical-architecture.md) |
| 5 | Benchmark methodology | [`12-benchmark-methodology-and-error-metrics.md`](12-benchmark-methodology-and-error-metrics.md) |
| 6 | Error metrics | [`12-benchmark-methodology-and-error-metrics.md §3`](12-benchmark-methodology-and-error-metrics.md) |
| 7 | Daily processing pipeline | [`10-positioning-mathematical-architecture.md §3`](10-positioning-mathematical-architecture.md) |
| 8 | Derived-output schema | [`13-derived-output-schema.md`](13-derived-output-schema.md) |
| 9 | Algorithm-versioning strategy | [`14-algorithm-versioning-strategy.md`](14-algorithm-versioning-strategy.md) |
| 10 | Assumptions requiring site validation | [`15-assumptions-requiring-validation.md`](15-assumptions-requiring-validation.md) |

## Supporting documents

| Document | Purpose |
|---|---|
| [`16-export-package-specification.md`](16-export-package-specification.md) | The observation package format shared by Collector, Master and Lab |
| [`17-identity-and-attribution-policy.md`](17-identity-and-attribution-policy.md) | The rules that stop randomized identifiers becoming people |
| [`18-site-model-specification.md`](18-site-model-specification.md) | Buildings, zones, anchors, survey points, site topology graph |
| [`19-association-only-deployment.md`](19-association-only-deployment.md) | The no-survey deployment: APs on towers, association reports, tower-level answers |

## Standing principles

These are enforced, not aspirational. Each has a test or a review gate.

1. **MEASURE FIRST. MODEL SECOND. VALIDATE THIRD.** Complexity is added only when held-out
   validation data shows a measurable improvement. Enforced by
   [`12-benchmark-methodology-and-error-metrics.md`](12-benchmark-methodology-and-error-metrics.md).
2. **RAW is immutable.** No code path updates or deletes a row in the raw observation table.
   Enforced by `ObservationDao` exposing no update/delete for raw rows, and by
   `RawImmutabilityTest`.
3. **No fabricated precision.** A `PositionEstimate` may carry null `x`/`y`; it may never carry a
   null `horizontal_uncertainty_m` when `x`/`y` are present. Enforced by the model's own
   constructor invariants in both Kotlin and Python.
4. **Every observation carries an `observer_id`.** Enforced by schema validation on export *and*
   on import.
5. **Algorithms are replaceable.** Every derived record stores the `algorithm_version` that produced
   it. Enforced by [`14-algorithm-versioning-strategy.md`](14-algorithm-versioning-strategy.md).
