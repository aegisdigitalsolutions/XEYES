"""Command line entry point: ``rfmapper-lab`` or ``python -m rfmapper_lab.cli``.

Five verbs, each corresponding to something an administrator actually does.

``run``        the nightly job: packages plus a site model in, one ``DERIVED_<date>.zip`` out.
``reprocess``  re-run history under different engines or a different site model, per ``docs/14`` §5.
``benchmark``  score the candidates against held-out ground truth and print the table from ``docs/12``.
``validate``   read the inputs and report what is wrong with them, computing nothing.
``demo``       simulate a site, write packages, run everything, and say what it produced.

Three properties hold across all of them. Nothing here writes to RAW or to the Master's database;
the Lab's only outputs are files in a directory it was told to write to. Every run is deterministic
for a fixed input and parameter set. And no command prints an accuracy figure that did not come
from a held-out split — ``run`` in particular writes an ``algorithm_report.json`` that states in
plain words that no accuracy was measured, rather than leaving the absence to be inferred.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path
from typing import Sequence

from .benchmark import (
    Baseline,
    check,
    default_candidates,
    format_report,
    movement_metrics,
    operating_point,
    run_benchmark,
    sweep_hysteresis,
    truth_points,
    with_movement,
)
from .export import write_derived_package
from .jsonio import dumps
from .models import DatasetKind
from .params import DEFAULTS, ParameterSet
from .parsing import (
    ReferenceModelError,
    load_dataset,
    load_packages,
    load_reference_model,
    parse_reference_model,
)
from .parsing.dataset import Dataset
from .pipeline import PipelineConfig, run_pipeline
from .positioning import EmpiricalErrorModel, STRATEGY_ORDER
from .registry import POSITIONING_ENGINES, ZONE_CLASSIFIERS
from .timeutil import day_bounds, format_ms, parse_ms
from .version import LAB_VERSION, PIPELINE_VERSION

#: Only one movement engine exists. The flag is accepted anyway so a pinned reprocess command
#: recorded today keeps working when a second one arrives, and so the pin is visible in the shell
#: history rather than implicit.
MOVEMENT_ENGINES = ("movement_hysteresis_v1",)

DEFAULT_BASELINE = Path("positioning-lab/benchmarks/baseline.json")


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if not getattr(args, "handler", None):
        parser.print_help()
        return 2
    try:
        return int(args.handler(args))
    except (ReferenceModelError, FileNotFoundError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


# -- argument parsing -----------------------------------------------------------------------------


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rfmapper-lab",
        description="RFMapper Positioning & Fusion Lab: offline inference over exported observations.",
    )
    parser.add_argument("--version", action="version", version=f"rfmapper-lab {LAB_VERSION}")
    subparsers = parser.add_subparsers(dest="command")

    run = subparsers.add_parser("run", help="process packages into one derived package")
    _add_input_arguments(run)
    _add_engine_arguments(run)
    run.add_argument("--out", type=Path, required=True, help="directory to write DERIVED_<date>.zip")
    run.add_argument(
        "--empirical",
        type=Path,
        help="benchmark-derived per-method P68 errors; without it every estimate is flagged "
        "UNVALIDATED_UNCERTAINTY",
    )
    run.add_argument("--computed-at", help="UTC instant stamped on the output; defaults to the "
                                           "last observation, so a rerun is reproducible")
    run.set_defaults(handler=_run)

    reprocess = subparsers.add_parser(
        "reprocess", help="re-run a date range under pinned engines (docs/14 §5)"
    )
    _add_input_arguments(reprocess)
    _add_engine_arguments(reprocess)
    reprocess.add_argument("--from", dest="date_from", required=True, help="first UTC day, YYYY-MM-DD")
    reprocess.add_argument("--to", dest="date_to", required=True, help="last UTC day, YYYY-MM-DD")
    reprocess.add_argument(
        "--reference",
        required=True,
        help="reference_model_id the run must use. Required, not defaulted: reproducing an old "
        "result needs the old site model, while re-judging old data needs the new one, and the "
        "two are different questions",
    )
    reprocess.add_argument("--out", type=Path, required=True)
    reprocess.add_argument("--empirical", type=Path)
    reprocess.set_defaults(handler=_reprocess)

    benchmark = subparsers.add_parser("benchmark", help="score candidates on a held-out split")
    _add_input_arguments(benchmark)
    benchmark.add_argument("--out", type=Path, help="directory for algorithm_report.json")
    benchmark.add_argument(
        "--evaluate-on",
        choices=("test", "validation", "train"),
        default="test",
        help="tune against validation; test is read once, at the end",
    )
    benchmark.add_argument("--resamples", type=int, default=2000)
    benchmark.add_argument("--split-seed", type=int, default=DEFAULTS.random_seed)
    benchmark.add_argument("--baseline", type=Path, help="regression gate against this baseline")
    benchmark.add_argument(
        "--update-baseline",
        type=Path,
        help="write the selected candidate's metrics as the new baseline",
    )
    benchmark.set_defaults(handler=_benchmark)

    validate = subparsers.add_parser("validate", help="check inputs and compute nothing")
    _add_input_arguments(validate)
    validate.set_defaults(handler=_validate)

    demo = subparsers.add_parser("demo", help="synthetic site, end to end")
    demo.add_argument("--out", type=Path, required=True)
    demo.add_argument("--seed", type=int, default=DEFAULTS.random_seed)
    demo.add_argument("--buildings", type=int, default=3)
    demo.add_argument("--zones-per-building", type=int, default=3)
    demo.add_argument("--survey-sessions", type=int, default=5)
    demo.add_argument("--walk-steps", type=int, default=60)
    demo.add_argument("--skip-benchmark", action="store_true")
    demo.add_argument(
        "--resamples", type=int, default=400, help="bootstrap resamples; lower is faster"
    )
    demo.set_defaults(handler=_demo)

    return parser


def _add_input_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--raw",
        type=Path,
        nargs="+",
        required=True,
        help="observation packages, or directories containing them",
    )
    parser.add_argument("--site-model", type=Path, required=True, help="site_model.json")
    parser.add_argument("--devices", type=Path, help="managed device list, if kept separately")
    parser.add_argument("--params", type=Path, help="parameter set JSON; defaults to the built-in set")


def _add_engine_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--zone-engine", default="zone_bayes_v1", help=f"one of {ZONE_CLASSIFIERS.ids()}")
    parser.add_argument(
        "--positioning-engine",
        action="append",
        dest="positioning_engines",
        help="strategy, repeatable; order is the fallback order. Defaults to the full hierarchy",
    )
    parser.add_argument("--movement-engine", default=MOVEMENT_ENGINES[0])
    parser.add_argument("--algorithm-version", default=PIPELINE_VERSION)
    parser.add_argument(
        "--dataset-kind",
        choices=[kind.value for kind in DatasetKind],
        default=DatasetKind.REAL.value,
    )


# -- commands -------------------------------------------------------------------------------------


def _run(args: argparse.Namespace) -> int:
    dataset, problems = _load(args)
    _print_ingest(dataset, problems)
    if not dataset.observations:
        print("no usable observations: nothing to compute", file=sys.stderr)
        return 1

    config = _config(args, dataset)
    result = run_pipeline(dataset, config)
    package = write_derived_package(result, args.out)

    print(f"\nWrote {package.path}")
    print(f"  sha256 {package.package_sha256}")
    for name, count in sorted(package.counts.items()):
        print(f"  {name}: {count}")
    print(f"  tiers: {result.tier_breakdown()}")
    print(f"  methods: {result.method_breakdown()}")
    if not config.empirical.validated:
        print(
            "\nUncertainty is unvalidated: every coordinate carries UNVALIDATED_UNCERTAINTY. Run "
            "`rfmapper-lab benchmark` against survey ground truth before quoting any accuracy "
            "figure from these estimates."
        )
    return 0


def _reprocess(args: argparse.Namespace) -> int:
    dataset, problems = _load(args)
    _print_ingest(dataset, problems)

    if dataset.reference.reference_model_id != args.reference:
        print(
            f"error: --reference is {args.reference!r} but the site model declares "
            f"{dataset.reference.reference_model_id!r}. Reproducing a historical run requires the "
            f"site model of the day; re-judging the same data under today's model is a different "
            f"question and has to be asked explicitly.",
            file=sys.stderr,
        )
        return 1

    start, _ = day_bounds(args.date_from)
    _, end = day_bounds(args.date_to)
    windowed = dataset.within(start, end)
    print(
        f"Reprocessing {args.date_from}..{args.date_to}: "
        f"{len(windowed.observations)} of {len(dataset.observations)} rows in range"
    )
    if not windowed.observations:
        print("no observations in that range", file=sys.stderr)
        return 1

    config = _config(args, windowed)
    result = run_pipeline(windowed, config)
    package = write_derived_package(result, args.out)

    print(f"\nWrote {package.path}")
    print(f"  sha256 {package.package_sha256}")
    print(f"  algorithm_version {config.algorithm_version}")
    print(f"  zone engine {config.zone_engine}, strategies {list(config.strategies)}")
    print(
        "  this is a new generation; no existing generation was read or modified"
    )
    return 0


def _benchmark(args: argparse.Namespace) -> int:
    dataset, problems = _load(args)
    _print_ingest(dataset, problems)

    params = _params(args)
    if args.split_seed != params.random_seed:
        params = replace(params, random_seed=args.split_seed)

    report = run_benchmark(
        dataset,
        candidates=default_candidates(params),
        dataset_kind=_dataset_kind(dataset),
        resamples=args.resamples,
        evaluate_on=args.evaluate_on,
        generated_at_utc=format_ms(_last_timestamp(dataset)),
    )
    print()
    print(format_report(report))

    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        path = args.out / "algorithm_report.json"
        path.write_text(dumps(report.as_dict()), encoding="utf-8")
        print(f"\nWrote {path}")

    status = 0
    if args.baseline:
        gate = check(report, Baseline.load(args.baseline))
        print()
        print(gate.describe())
        status = 0 if gate.passed and gate.comparable else 1

    if args.update_baseline:
        baseline = Baseline.from_report(report)
        if baseline is None:
            print("no candidate selected; baseline not updated", file=sys.stderr)
            return 1
        baseline.write(args.update_baseline)
        print(f"\nBaseline updated: {args.update_baseline}")

    return status


def _validate(args: argparse.Namespace) -> int:
    dataset, problems = _load(args)
    _print_ingest(dataset, problems)

    model = dataset.reference
    print(
        f"\nSite model {model.reference_model_id}: {len(model.zones)} zones, "
        f"{len(model.buildings)} buildings, {len(model.infrastructure)} infrastructure nodes, "
        f"{len(model.observers)} observers, {len(model.survey_points)} survey points, "
        f"{len(model.devices)} managed devices"
    )

    surveyed = {
        observation.survey_point_id
        for observation in dataset.ground_truth()
        if observation.survey_point_id
    }
    uncalibrated = sorted(set(model.survey_points) - surveyed)
    if uncalibrated:
        print(
            f"Survey points with no ground-truth capture ({len(uncalibrated)}): "
            + ", ".join(uncalibrated[:10])
            + ("..." if len(uncalibrated) > 10 else "")
        )

    zones_without = sorted(
        {
            zone_id
            for zone_id in model.zones
            if not any(
                point.zone_id == zone_id
                for point_id, point in model.survey_points.items()
                if point_id in surveyed
            )
        }
    )
    if zones_without:
        print(
            f"Zones with no ground truth ({len(zones_without)}): "
            + ", ".join(zones_without[:10])
            + ("..." if len(zones_without) > 10 else "")
        )

    blocking = [issue for issue in problems if issue.problem.blocking]
    if blocking:
        print(f"\n{len(blocking)} blocking package problem(s); those packages contributed no rows.")
        return 1
    print("\nInputs are readable. Nothing was computed.")
    return 0


def _demo(args: argparse.Namespace) -> int:
    from .simulator import SimulationSpec, simulate, write_packages

    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    raw_dir = out / "raw"
    derived_dir = out / "derived"

    spec = SimulationSpec(
        seed=args.seed,
        buildings=args.buildings,
        zones_per_building=args.zones_per_building,
        survey_sessions=args.survey_sessions,
        survey_samples_per_session=10,
        walk_steps=args.walk_steps,
    )
    site = simulate(spec)

    site_model_path = out / "site_model.json"
    site_model_path.write_text(dumps(site.reference_document), encoding="utf-8")

    written = write_packages(site.observations, raw_dir)
    print(f"Simulated {spec.buildings} buildings, {len(site.observations)} observations")
    print(f"  site model: {site_model_path}")
    print(f"  packages:   {len(written)} in {raw_dir}")

    # Read back through the real package reader rather than reusing the in-memory rows: the point
    # of the demo is to exercise Phase 1, and handing the pipeline the objects it would have
    # produced anyway would skip every check that matters here.
    packages = load_packages([package.path for package in written])
    model = parse_reference_model(site.reference_document)
    dataset = load_dataset(packages, model)
    _print_ingest(dataset, tuple(dataset.issues))

    config = PipelineConfig(
        dataset_kind=DatasetKind.SYNTHETIC,
        computed_at_ms=_last_timestamp(dataset),
    )
    result = run_pipeline(dataset, config)
    print(f"\nPipeline: {result.counts()}")
    print(f"  tiers:   {result.tier_breakdown()}")
    print(f"  methods: {result.method_breakdown()}")

    algorithm_report = None
    if not args.skip_benchmark:
        report = run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=args.resamples,
            generated_at_utc=format_ms(_last_timestamp(dataset)),
        )
        truth = truth_points(site.truth)
        sweep = sweep_hysteresis(dataset, truth, config)
        chosen = operating_point(sweep)
        report = with_movement(
            report,
            {
                "baseline": movement_metrics(result, truth).as_dict(),
                "sweep": [point.as_dict() for point in sweep],
                "operating_point": chosen.as_dict() if chosen else None,
            },
        )
        print()
        print(format_report(report))
        algorithm_report = report.as_dict()
        (out / "algorithm_report.json").write_text(
            dumps(algorithm_report), encoding="utf-8"
        )

    package = write_derived_package(result, derived_dir, algorithm_report=algorithm_report)
    print(f"\nWrote {package.path}")
    print(f"  sha256 {package.package_sha256}")
    print(
        "\nThis is synthetic data. The numbers above describe the simulator's own propagation "
        "model and say nothing about any real site."
    )
    return 0


# -- shared helpers -------------------------------------------------------------------------------


def _load(args: argparse.Namespace) -> tuple[Dataset, tuple]:
    model = load_reference_model(args.site_model, args.devices)
    paths = _package_paths(args.raw)
    if not paths:
        raise FileNotFoundError(f"no .zip packages found under {', '.join(str(p) for p in args.raw)}")
    packages = load_packages(paths)
    dataset = load_dataset(packages, model)
    return dataset, dataset.issues


def _package_paths(inputs: Sequence[Path]) -> list[Path]:
    paths: list[Path] = []
    for entry in inputs:
        if entry.is_dir():
            paths.extend(sorted(entry.glob("*.zip")))
        elif entry.exists():
            paths.append(entry)
        else:
            raise FileNotFoundError(f"{entry} does not exist")
    return sorted(set(paths))


def _print_ingest(dataset: Dataset, problems: Sequence) -> None:
    stats = dataset.stats
    print(
        f"Ingest: {stats.packages} packages, {stats.rows_accepted} rows accepted, "
        f"{stats.duplicates} duplicates, {stats.invalid} invalid"
    )
    if stats.duplicate_id_content_mismatch:
        print(
            f"  {stats.duplicate_id_content_mismatch} rows shared an observation_id with "
            f"different content: two measurements were given the same identity"
        )
    if stats.rejected_packages:
        print(f"  rejected packages: {', '.join(stats.rejected_packages)}")

    counted: dict[str, int] = {}
    for issue in problems:
        counted[issue.problem.value] = counted.get(issue.problem.value, 0) + 1
    for code, count in sorted(counted.items()):
        print(f"  {code}: {count}")


def _params(args: argparse.Namespace) -> ParameterSet:
    path = getattr(args, "params", None)
    if not path:
        return DEFAULTS
    return ParameterSet.from_dict(json.loads(Path(path).read_text(encoding="utf-8")))


def _config(args: argparse.Namespace, dataset: Dataset) -> PipelineConfig:
    if args.zone_engine not in ZONE_CLASSIFIERS:
        raise KeyError(
            f"unknown zone engine {args.zone_engine!r}; registered: {ZONE_CLASSIFIERS.ids()}"
        )
    strategies = tuple(args.positioning_engines or STRATEGY_ORDER)
    for strategy in strategies:
        if strategy not in POSITIONING_ENGINES:
            raise KeyError(
                f"unknown positioning engine {strategy!r}; registered: {POSITIONING_ENGINES.ids()}"
            )
    if args.movement_engine not in MOVEMENT_ENGINES:
        raise KeyError(
            f"unknown movement engine {args.movement_engine!r}; registered: {MOVEMENT_ENGINES}"
        )

    empirical = (
        EmpiricalErrorModel.load(args.empirical)
        if getattr(args, "empirical", None)
        else EmpiricalErrorModel.unvalidated()
    )
    computed_at = getattr(args, "computed_at", None)
    return PipelineConfig(
        zone_engine=args.zone_engine,
        strategies=strategies,
        algorithm_version=args.algorithm_version,
        params=_params(args),
        empirical=empirical,
        dataset_kind=DatasetKind(args.dataset_kind),
        computed_at_ms=parse_ms(computed_at) if computed_at else _last_timestamp(dataset),
    )


def _dataset_kind(dataset: Dataset) -> DatasetKind:
    """Synthetic when the site model says so, so a simulated run cannot be reported as real."""
    if "synthetic" in dataset.reference.reference_model_id.lower():
        return DatasetKind.SYNTHETIC
    return DatasetKind.REAL


def _last_timestamp(dataset: Dataset) -> int:
    """The run's clock, taken from the data rather than from the wall.

    ``computed_at`` goes into the manifest and into every estimate id, so reading it from the
    system clock would make two runs over identical inputs produce different bytes and quietly
    break the reproducibility the whole design rests on.
    """
    span = dataset.time_range()
    return span[1] if span else 0


if __name__ == "__main__":
    raise SystemExit(main())
