# RFMapper Positioning & Fusion Lab

Offline statistical inference over exported wireless observations. Reads `RAW` observation packages
and the `REFERENCE` site model, writes a `DERIVED` package the Master imports. Reads files, computes,
writes files — no server, no database, no network.

```
RAW observation packages ─┐
                          ├─► rfmapper_lab ─► DERIVED_<date>.zip ─► Master
REFERENCE site model ─────┘
```

The design principle, and the reason most of the code is about what *not* to claim:

> **MEASURE FIRST. MODEL SECOND. VALIDATE THIRD. INCREASE COMPLEXITY ONLY WHEN THE DATA JUSTIFIES IT.**

Three invariants follow from it and are enforced rather than documented:

- **`RAW` is never mutated.** Every phase is a pure function of its inputs plus a versioned parameter
  set. The Lab has no write path to anything but its own output directory.
- **No coordinate without an error bar.** An estimate that cannot carry an uncertainty is demoted to
  its zone, not emitted bare.
- **No accuracy figure that did not come from a held-out split.** A package produced without a
  benchmark run says so in `algorithm_report.json`, in plain words, on the record itself.

## Install and run

```bash
cd positioning-lab
pip install -e '.[dev]'
pytest

# The nightly job: packages plus a site model in, one DERIVED package out.
rfmapper-lab run \
  --raw ../contract \
  --site-model ../contract/site_model.json \
  --devices ../contract/devices.json \
  --out /tmp/derived

# Synthetic site, end to end, nothing required but the package itself.
rfmapper-lab demo --out /tmp/rfmapper-demo
```

`../contract` holds the committed cross-language fixtures and is a real, if tiny, dataset; see
[Cross-language fixtures](#cross-language-fixtures) below.

## Pipeline

Phase numbering follows [`docs/10`](../docs/10-positioning-mathematical-architecture.md) §1.

| Phase | Module | What it does |
|---|---|---|
| 1 | `parsing/` | Read packages, verify checksums, cross-check CSV against JSON, normalize identifiers and time, deduplicate globally on `observation_id` |
| 2 | `quality/` | Data-quality and drift analysis → `quality_report.json` |
| 3 | `parsing/dataset.py` | Separate ground truth from ordinary rows |
| 4 | `fingerprint/` | Per-survey-point statistical fingerprints — distributions, never point values |
| 5 | `zone/` | Zone classification of a live vector |
| 6 | `fusion/` | Multi-observer fusion over time windows |
| 7 | `positioning/` | Approximate position: RTT multilateration, weighted-kNN centroid, zone centroid, zone only |
| 8 | `positioning/uncertainty.py`, `confidence.py` | Uncertainty and confidence factors |
| 9 | `movement/` | Temporal filtering, hysteresis, movement state, topology checks |
| 11 | `export/` | `DERIVED_<date>.zip` |

Two boundaries in there are deliberate and easy to erode by accident.

**Survey samples are not inference inputs.** Phases 3 and 4 consume `sample_kind=GROUND_TRUTH` rows;
phases 5 to 9 consume the ordinary ones. Feeding a survey capture back through the classifier built
from it would let a fingerprint validate itself, and the resulting accuracy figures would be
meaningless in a way that looks like success.

**The hierarchy stops where the evidence stops.** The engine emits the deepest tier the evidence
supports — site presence, building, zone, approximate position, ranged position — and no further.
Null coordinates with a confident zone is a normal and preferred outcome. `PRECISION_RANGE` is
unreachable without ranging evidence, and the Master re-checks that claim on import rather than
trusting the producer.

**A site without a survey is a supported deployment, not a broken one.** If the only evidence is
which AP a device is associated with — access points on towers, no survey, ever — then
`--zone-engine zone_anchor_v1` answers with the tower and no coordinates, and that is the ceiling
of that evidence. The default classifier compares against fingerprints, finds none, and honestly
answers `BUILDING`, which looks like a broken installation; the engine has to be chosen
deliberately. See [`docs/19`](../docs/19-association-only-deployment.md), which also covers the
hysteresis thresholds, since their defaults assume the other deployment and will otherwise record
no movement between towers at all.

**Attribution is registry-driven.** A radio identifier that is not in the device registry attributes
to nothing, and a device that is not in the registry gets no estimate at all
([`docs/17`](../docs/17-identity-and-attribution-policy.md) §4). A run without `--devices` therefore
does not produce worse results, it produces empty ones. Randomized addresses are carried as
environmental context and never merged into a managed device.

## Commands

| Command | Purpose |
|---|---|
| `run` | The nightly job. Packages plus a site model in, one `DERIVED_<date>.zip` out. |
| `reprocess` | Re-run a date range under pinned engines and a named site model ([`docs/14`](../docs/14-algorithm-versioning-strategy.md) §5). |
| `benchmark` | Score candidate engines on a held-out split and print the report from [`docs/12`](../docs/12-benchmark-methodology-and-error-metrics.md). |
| `validate` | Read the inputs, report what is wrong with them, compute nothing. |
| `demo` | Simulate a site, write packages, run everything, and say what it produced. |

`reprocess` requires `--reference` explicitly rather than defaulting it. Reproducing a historical
result needs the site model of the day; re-judging the same data under today's model is a different
question, and the command will not let the two be confused.

Registered engines, selectable per run and recorded in every estimate:

```
zone         zone_anchor_v1  zone_bayes_v1  zone_cosine_v1  zone_nn_v1  zone_wknn_v1
positioning  pos_rtt_multilateration_v1  pos_wknn_centroid_v1  pos_zone_centroid_v1  pos_zone_only_v1
movement     movement_hysteresis_v1
```

## Benchmarking and uncertainty

Uncertainty starts **unvalidated**. Without `--empirical`, every coordinate carries the
`UNVALIDATED_UNCERTAINTY` flag and its error bar is geometric rather than measured. To replace that
with a measured figure:

```bash
rfmapper-lab benchmark --raw <packages> --site-model <site> --out /tmp/bench
rfmapper-lab run --raw <packages> --site-model <site> --empirical /tmp/bench/algorithm_report.json --out /tmp/derived
```

The harness splits by survey session, not by sample, so a fingerprint is never scored against
measurements from the visit that built it. Metrics, the bootstrap, and the regression gate are
specified in [`docs/12`](../docs/12-benchmark-methodology-and-error-metrics.md); `--baseline` fails
the command on a regression and `--update-baseline` moves the bar deliberately.

The `demo` command's numbers describe the simulator's own propagation model and say nothing about any
real site. That is stated in its output too, because a plausible number from synthetic data is the
easiest thing in this system to quote by mistake.

## Reproducibility

Two runs over the same inputs with the same versions produce byte-identical output. The determinism
test enforces it, and several small decisions exist only to keep it true:

- **The clock comes from the data.** `computed_at` defaults to the last observation's timestamp, not
  the wall clock. It goes into the manifest and into every record id, so reading the system clock
  would make two identical runs differ. `--computed-at` overrides it explicitly.
- **Record ids are content-derived.** UUIDv5 over the algorithm version, the device and the instant,
  so the same conclusion gets the same id in every run.
- **Canonical JSON.** Sorted keys, explicit nulls, no exponent notation, `NaN` refused rather than
  written.
- **Fixed zip metadata.** Fixed entry order and a fixed member timestamp, so `checksum.txt`
  distinguishes a real change from a re-run.
- **No machine measurement reaches the package.** Median CPU time is reported by the benchmark and
  used to prefer the cheaper of two statistically indistinguishable engines, but only where one is
  cheaper by a wide margin; inside the noise band the tie goes on name. A timing is not a
  conclusion, so it neither selects an algorithm nor enters a package checksum.
- **Every output cites its inputs.** `algorithm_version`, `engine_versions`,
  `parameter_set_sha256`, `random_seed`, `source_dataset_ids`, `reference_model_id`. The Master
  refuses a derived package citing data it has never imported, which is what keeps an estimate on the
  map traceable back to raw evidence.

Parameters live in `params.py` as one versioned set, hashed into every record. A tuning change is
therefore visible as a different `parameter_set_sha256` rather than as an unexplained shift in
yesterday's numbers.

## Cross-language fixtures

The Collector, the Master and the Lab never share a process, so no test can check their file
contracts by calling one from the other. What can be checked is the artefact: one side writes a file,
the file is committed under [`../contract`](../contract), and the other side's suite reads it.

| Direction | Producer | Consumer |
|---|---|---|
| `site_model.json`, `devices.json`, `RFMapper_OBSC*.zip` | Kotlin `ContractFixtureTest` | `tests/test_fixtures.py` |
| `DERIVED_2026-05-04.zip` | `tests/test_fixtures.py` | Kotlin `ContractFixtureTest` |

Neither side shares code with the other; both work from the specifications in `docs/`. The fixtures
are one small synthetic site — fabricated geometry, fabricated devices, fabricated radio — and
include the rows most likely to be mishandled silently: an SSID containing a comma, a quote and a
non-ASCII character, a hidden SSID, a cached scan, a ranging pair, a GNSS fix at full precision, and
a randomized address that must stay anonymous.

To change a format deliberately, regenerate both halves and read the diffs:

```bash
cd ../android && ./gradlew :data-room:testDebugUnitTest -Drfmapper.contract.write=true
cd ../positioning-lab && pytest tests/test_fixtures.py --rfmapper-write-contract
cd ../android && ./gradlew :data-room:testDebugUnitTest   # the Master still imports it
```

Zip fixtures are compared by entry name, order and decompressed payload rather than by archive
bytes, since compressed output belongs to whichever zlib the toolchain bundles and pinning it would
fail on an interpreter or JDK upgrade while nothing about the contract had moved.

## Layout

```
src/rfmapper_lab/
  models.py        Canonical model: observations, fingerprints, estimates, the reference model
  params.py        The versioned parameter set, hashed into every record
  version.py       Independently versioned engines and the composite pipeline version
  jsonio.py        Canonical JSON, sha256, content-derived ids
  timeutil.py      The one instant format, and UTC day bounds
  parsing/         Phase 1: package reader, CSV codec, normalization, dataset assembly
  quality/         Phase 2: data-quality report and drift detection
  fingerprint/     Phase 4: statistical fingerprints and their promotion checks
  zone/            Phase 5: zone classifiers
  fusion/          Phase 6: multi-observer time-window fusion
  positioning/     Phase 7-8: placement strategies, uncertainty, confidence
  movement/        Phase 9: hysteresis, movement state, topology
  export/          Phase 11: the derived package writer
  benchmark/       Splits, metrics, bootstrap, report, regression gate
  simulator/       Synthetic site and packages, for the demo and the tests
  cli.py           run / reprocess / benchmark / validate / demo
tests/             Contracts, parsing, inference, fusion, movement, pipeline, benchmark, CLI, fixtures
```

## Reference documents

- [`docs/10`](../docs/10-positioning-mathematical-architecture.md) — mathematical architecture
- [`docs/11`](../docs/11-ground-truth-and-calibration-procedure.md) — ground truth and calibration
- [`docs/12`](../docs/12-benchmark-methodology-and-error-metrics.md) — benchmark methodology and metrics
- [`docs/13`](../docs/13-derived-output-schema.md) — derived output schema
- [`docs/14`](../docs/14-algorithm-versioning-strategy.md) — algorithm versioning
- [`docs/15`](../docs/15-assumptions-requiring-validation.md) — assumptions still requiring validation
- [`docs/16`](../docs/16-export-package-specification.md) — export package specification
- [`docs/17`](../docs/17-identity-and-attribution-policy.md) — identity and attribution policy
- [`docs/18`](../docs/18-site-model-specification.md) — site model specification
- [`docs/19`](../docs/19-association-only-deployment.md) — the no-survey, association-only deployment
