"""The comparison harness, per ``docs/12-benchmark-methodology-and-error-metrics.md`` §2.

Every candidate is handed identical inputs: the same TRAIN fingerprints, the same TEST vectors, the
same reference model, the same seed. That is not a convention here — the candidates share one
precomputed fingerprint set and one list of held-out samples, so there is no code path by which two
candidates could see different data. The dataset's content hash goes into the report, so a run
against different data cannot be compared with this one by accident.

The candidates also share the pipeline's own inference function rather than a copy of it. A harness
that reassembled zone classification, placement, uncertainty and confidence itself would be
measuring the harness.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, replace
from typing import Sequence

from ..fingerprint import FingerprintSet, build_fingerprints
from ..models import DatasetKind, ReferenceModel
from ..params import DEFAULTS, ParameterSet
from ..parsing.dataset import Dataset
from ..pipeline import PipelineConfig, infer
from ..positioning import EmpiricalErrorModel, STRATEGY_ORDER
from ..registry import ZONE_CLASSIFIERS
from ..version import PIPELINE_VERSION, engine_versions
from .metrics import (
    BOOTSTRAP_RESAMPLES,
    CalibrationMetrics,
    ClassificationMetrics,
    Interval,
    PositionalMetrics,
    Record,
    bootstrap,
    calibration_metrics,
    classification_metrics,
    median_cpu_ms,
    median_error_of,
    p68_containment_of,
    p90_error_of,
    positional_metrics,
    zone_accuracy_of,
)
from .splits import HeldOutSample, SessionSplit, held_out_samples, session_ids, split_sessions, training_observations

#: The RTT-only variant. It is in the default set because it makes the coverage/error trade-off
#: visible: far better error where it applies, and it applies to a minority of windows. Without a
#: row like it in the table, someone will eventually read "3.1 m" as the system's accuracy.
RTT_ONLY_STRATEGIES = ("pos_rtt_multilateration_v1",)


@dataclass(frozen=True, slots=True)
class Candidate:
    """One configuration under test. The label is what appears in the report."""

    label: str
    zone_engine: str
    strategies: tuple[str, ...] = STRATEGY_ORDER
    params: ParameterSet = DEFAULTS

    def config(self, dataset_kind: DatasetKind, empirical: EmpiricalErrorModel) -> PipelineConfig:
        return PipelineConfig(
            zone_engine=self.zone_engine,
            strategies=self.strategies,
            params=self.params,
            empirical=empirical,
            dataset_kind=dataset_kind,
            # The benchmark scores single held-out captures, so there is no track to smooth and no
            # previous zone to carry. Enabling the temporal filter here would credit an algorithm
            # for continuity that the static survey cannot exhibit.
            enable_temporal_filter=False,
        )


@dataclass(frozen=True, slots=True)
class CandidateResult:
    candidate: Candidate
    classification: ClassificationMetrics
    positional: PositionalMetrics
    calibration: CalibrationMetrics
    cpu_ms: float | None
    intervals: dict[str, Interval] = field(default_factory=dict)
    method_counts: dict[str, int] = field(default_factory=dict)
    records: tuple[Record, ...] = ()

    def as_dict(self) -> dict[str, object]:
        return {
            "label": self.candidate.label,
            "zone_engine": self.candidate.zone_engine,
            "strategies": list(self.candidate.strategies),
            "parameter_set_sha256": self.candidate.params.sha256(),
            "classification": self.classification.as_dict(),
            "positional": self.positional.as_dict(),
            "uncertainty_calibration": self.calibration.as_dict(),
            "median_cpu_ms": None if self.cpu_ms is None else round(self.cpu_ms, 4),
            "confidence_intervals": {
                name: interval.as_dict() for name, interval in sorted(self.intervals.items())
            },
            "method_counts": dict(sorted(self.method_counts.items())),
        }


@dataclass(frozen=True, slots=True)
class BenchmarkReport:
    dataset_content_sha256: str
    reference_model_id: str
    dataset_kind: DatasetKind
    split: SessionSplit
    results: tuple[CandidateResult, ...]
    selected: str | None
    selection_reason: str
    data_quality: dict[str, object] = field(default_factory=dict)
    engine_versions: dict[str, str] = field(default_factory=dict)
    algorithm_version: str = PIPELINE_VERSION
    generated_at_utc: str = ""
    movement: dict[str, object] = field(default_factory=dict)

    def as_dict(self) -> dict[str, object]:
        return {
            "algorithm_version": self.algorithm_version,
            "engine_versions": dict(sorted(self.engine_versions.items())),
            "generated_at_utc": self.generated_at_utc,
            "dataset_kind": self.dataset_kind.value,
            "dataset_content_sha256": self.dataset_content_sha256,
            "reference_model_id": self.reference_model_id,
            "split": self.split.as_dict(),
            "data_quality": self.data_quality,
            "candidates": [result.as_dict() for result in self.results],
            "selected": self.selected,
            "selection_reason": self.selection_reason,
            "movement": self.movement,
        }

    def result_for(self, label: str) -> CandidateResult | None:
        for result in self.results:
            if result.candidate.label == label:
                return result
        return None


def default_candidates(params: ParameterSet = DEFAULTS) -> tuple[Candidate, ...]:
    """Every registered zone classifier, plus the RTT-only variant.

    Registered rather than listed: a new classifier is compared against the incumbents the moment
    it exists, without anyone remembering to add it here.
    """
    candidates = [
        Candidate(label=engine_id, zone_engine=engine_id, params=params)
        for engine_id in ZONE_CLASSIFIERS.ids()
    ]
    if "zone_bayes_v1" in ZONE_CLASSIFIERS:
        candidates.append(
            Candidate(
                label="zone_bayes_v1 + rtt",
                zone_engine="zone_bayes_v1",
                strategies=RTT_ONLY_STRATEGIES,
                params=params,
            )
        )
    return tuple(candidates)


def run_benchmark(
    dataset: Dataset,
    candidates: Sequence[Candidate] | None = None,
    split: SessionSplit | None = None,
    dataset_kind: DatasetKind = DatasetKind.REAL,
    empirical: EmpiricalErrorModel | None = None,
    generated_at_utc: str = "",
    resamples: int = BOOTSTRAP_RESAMPLES,
    evaluate_on: str = "test",
) -> BenchmarkReport:
    """Score every candidate on identical held-out data and pick a winner.

    ``evaluate_on`` exists so tuning can be done against VALIDATION without touching TEST. It
    defaults to TEST because that is the run whose numbers may be published, and the default should
    be the honest one.
    """
    params = candidates[0].params if candidates else DEFAULTS
    model = dataset.reference
    ground_truth = dataset.ground_truth()
    sessions = session_ids(ground_truth)
    split = split or split_sessions(sessions, seed=params.random_seed)
    empirical = empirical or EmpiricalErrorModel.unvalidated()
    candidates = tuple(candidates or default_candidates(params))

    evaluation_sessions = {
        "test": split.test,
        "validation": split.validation,
        "train": split.train,
    }[evaluate_on]

    # Built once, from TRAIN only, and shared by every candidate. All candidates currently use the
    # same fingerprint engine; when a second one exists this is the line that has to grow a loop,
    # and the sharing above is what keeps the comparison honest until then.
    training = training_observations(ground_truth, split.train)
    fingerprints = build_fingerprints(training, model, params)
    samples = held_out_samples(ground_truth, evaluation_sessions, model, params)

    results = []
    for candidate in candidates:
        results.append(
            _score(
                candidate=candidate,
                samples=samples,
                fingerprints=fingerprints,
                model=model,
                dataset=dataset,
                dataset_kind=dataset_kind,
                empirical=empirical,
                resamples=resamples,
            )
        )

    selected, reason = select(tuple(results))

    from .metrics import data_quality

    return BenchmarkReport(
        dataset_content_sha256=dataset.content_sha256,
        reference_model_id=model.reference_model_id,
        dataset_kind=dataset_kind,
        split=split,
        results=tuple(results),
        selected=selected,
        selection_reason=reason,
        data_quality=data_quality([r for result in results for r in result.records], model)
        if results
        else {},
        engine_versions=engine_versions(),
        generated_at_utc=generated_at_utc,
    )


def _score(
    candidate: Candidate,
    samples: Sequence[HeldOutSample],
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    dataset: Dataset,
    dataset_kind: DatasetKind,
    empirical: EmpiricalErrorModel,
    resamples: int,
) -> CandidateResult:
    config = candidate.config(dataset_kind, empirical)
    classifier = ZONE_CLASSIFIERS.get(candidate.zone_engine)
    per_zone_counts = {zone: len(points) for zone, points in fingerprints.by_zone().items()}

    records: list[Record] = []
    method_counts: dict[str, int] = {}

    for sample in samples:
        started = time.perf_counter()
        inference = infer(
            vector=sample.vector,
            classifier=classifier,
            fingerprints=fingerprints,
            model=model,
            config=config,
            computed_at_utc="",
            source_dataset_ids=dataset.source_dataset_ids,
            # No previous zone: a static held-out capture has no track behind it, and supplying one
            # would let the continuity prior smuggle in the answer.
            previous_zone_id=None,
            per_zone_counts=per_zone_counts,
        )
        elapsed_ms = (time.perf_counter() - started) * 1000.0

        if inference.estimate is not None:
            method_counts[inference.estimate.method] = (
                method_counts.get(inference.estimate.method, 0) + 1
            )

        records.append(
            Record(
                session_id=sample.session_id,
                survey_point_id=sample.survey_point_id,
                truth_zone_id=sample.zone_id,
                truth_building_id=sample.building_id,
                truth_x=sample.x,
                truth_y=sample.y,
                estimate=inference.estimate,
                ranked_zone_ids=tuple(
                    candidate_zone.zone_id for candidate_zone in inference.zones.candidates
                ),
                cpu_ms=elapsed_ms,
            )
        )

    seed = candidate.params.random_seed
    intervals = {}
    for name, metric in (
        ("zone_accuracy", zone_accuracy_of),
        ("median_error_m", median_error_of),
        ("p90_error_m", p90_error_of),
        ("p68_containment", p68_containment_of),
    ):
        interval = bootstrap(records, metric, seed=seed, resamples=resamples)
        if interval is not None:
            intervals[name] = interval

    return CandidateResult(
        candidate=candidate,
        classification=classification_metrics(records, model),
        positional=positional_metrics(records),
        calibration=calibration_metrics(records),
        cpu_ms=median_cpu_ms(records),
        intervals=intervals,
        method_counts=method_counts,
        records=tuple(records),
    )


def select(results: Sequence[CandidateResult]) -> tuple[str | None, str]:
    """Pick the winner, and say why in a sentence an administrator can check.

    Two rules, both from §4. Overlapping confidence intervals mean "not proven better", so the
    cheaper and more interpretable candidate keeps the title. And a candidate whose uncertainty is
    overconfident is rejected outright however good its error looks, because a small circle on a
    map is read as a fact.
    """
    scored = [result for result in results if result.classification.samples > 0]
    if not scored:
        return None, "no held-out samples to score against"

    honest = [
        result for result in scored if result.calibration.verdict != "OVERCONFIDENT"
    ]
    rejected = [
        result.candidate.label for result in scored if result.calibration.verdict == "OVERCONFIDENT"
    ]
    pool = honest or scored

    def key(result: CandidateResult) -> tuple[float, float]:
        interval = result.intervals.get("zone_accuracy")
        point = interval.point if interval else result.classification.zone_accuracy
        return (-point, result.cpu_ms or 0.0)

    ranked = sorted(pool, key=key)
    leader = ranked[0]
    leader_interval = leader.intervals.get("zone_accuracy")

    reason_parts = []
    if rejected and honest:
        reason_parts.append(
            "rejected as overconfident: " + ", ".join(sorted(rejected))
        )
    elif rejected:
        reason_parts.append(
            "every candidate is overconfident; selection is on accuracy alone and the reported "
            "uncertainty must not be trusted until it is recalibrated"
        )

    if leader_interval is not None and leader_interval.resamples == 0:
        # One held-out session gives one bootstrap draw, repeated. The ranking still stands as a
        # ranking, but nothing about it is proven, and saying "not significantly better" would
        # imply a test that was never run.
        reason_parts.append(
            "highest zone accuracy, but the split had too few held-out sessions to bound an "
            "interval: this ranking is unproven"
        )
        return leader.candidate.label, "; ".join(reason_parts)

    if leader_interval is not None:
        overlapping = [
            other
            for other in ranked[1:]
            if other.intervals.get("zone_accuracy")
            and leader_interval.overlaps(other.intervals["zone_accuracy"])
        ]
        if overlapping:
            # Cheapest among the statistically indistinguishable. Cost is the tiebreak because a
            # nightly batch over a full day of history pays it on every row.
            tied = sorted([leader, *overlapping], key=lambda r: (r.cpu_ms or 0.0, r.candidate.label))
            winner = tied[0]
            if winner is not leader:
                reason_parts.append(
                    f"{leader.candidate.label} not significantly better than "
                    f"{winner.candidate.label} at "
                    f"{_cost_ratio(leader, winner)} the cost; intervals overlap"
                )
                return winner.candidate.label, "; ".join(reason_parts)
            reason_parts.append(
                "highest zone accuracy and cheapest among the candidates whose intervals overlap"
            )
            return leader.candidate.label, "; ".join(reason_parts)

    reason_parts.append("highest zone accuracy, interval does not overlap the runners-up")
    return leader.candidate.label, "; ".join(reason_parts)


def _cost_ratio(expensive: CandidateResult, cheap: CandidateResult) -> str:
    if not cheap.cpu_ms or not expensive.cpu_ms:
        return "an unmeasured multiple of"
    return f"{expensive.cpu_ms / cheap.cpu_ms:.1f}x"


def with_movement(report: BenchmarkReport, movement: dict[str, object]) -> BenchmarkReport:
    return replace(report, movement=movement)
