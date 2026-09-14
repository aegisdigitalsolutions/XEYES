"""Error metrics, per ``docs/12-benchmark-methodology-and-error-metrics.md`` §3.

Three rules are enforced by the code rather than left to the reader's discipline.

**Coverage travels with error.** :class:`PositionalMetrics` cannot be constructed without it, and
the text report refuses to print an error figure without printing coverage beside it. An algorithm
answering 10% of the time with 3-metre error is not better than one answering 95% of the time with
7-metre error, and a median-only table says it is.

**Distributions, not means.** Median, P90, P95, max and the full CDF. The mean appears only
alongside them, because a mean over a long-tailed error distribution describes a case that never
happened.

**Declining to answer is not a free pass.** Zone accuracy is over every held-out sample, so an
algorithm that emits nothing scores zero rather than scoring nothing. ``zone_accuracy_answered`` is
reported too, but second, and never alone.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Callable, Sequence

import numpy as np

from ..models import PositionEstimate, ReferenceModel

#: Bootstrap resamples for the confidence intervals. §2.5 of the methodology.
BOOTSTRAP_RESAMPLES = 2000

#: 1.96 sigma, the two-sided 95% point of a normal. Used for the P95 containment check.
P95_SIGMA = 1.96


@dataclass(frozen=True, slots=True)
class Record:
    """One held-out sample, its truth, and what the algorithm said about it."""

    session_id: str
    survey_point_id: str
    truth_zone_id: str
    truth_building_id: str
    truth_x: float
    truth_y: float
    estimate: PositionEstimate | None
    ranked_zone_ids: tuple[str, ...] = ()
    cpu_ms: float = 0.0

    @property
    def predicted_zone_id(self) -> str | None:
        return self.estimate.zone_id if self.estimate else None

    @property
    def predicted_building_id(self) -> str | None:
        return self.estimate.building_id if self.estimate else None

    @property
    def error_m(self) -> float | None:
        """Euclidean error in the site frame, or ``None`` when no coordinates were emitted."""
        if self.estimate is None or self.estimate.x is None or self.estimate.y is None:
            return None
        return math.hypot(self.estimate.x - self.truth_x, self.estimate.y - self.truth_y)

    @property
    def uncertainty_m(self) -> float | None:
        return self.estimate.horizontal_uncertainty_m if self.estimate else None


@dataclass(frozen=True, slots=True)
class ClassificationMetrics:
    samples: int
    zone_accuracy: float
    zone_coverage: float
    zone_accuracy_answered: float
    zone_top2_accuracy: float
    building_accuracy: float
    adjacent_error_rate: float
    confusion: dict[str, dict[str, int]] = field(default_factory=dict)

    def as_dict(self) -> dict[str, object]:
        return {
            "samples": self.samples,
            "zone_accuracy": round(self.zone_accuracy, 4),
            "zone_coverage": round(self.zone_coverage, 4),
            "zone_accuracy_answered": round(self.zone_accuracy_answered, 4),
            "zone_top2_accuracy": round(self.zone_top2_accuracy, 4),
            "building_accuracy": round(self.building_accuracy, 4),
            "adjacent_error_rate": round(self.adjacent_error_rate, 4),
            "confusion": {
                truth: dict(sorted(predicted.items()))
                for truth, predicted in sorted(self.confusion.items())
            },
        }


@dataclass(frozen=True, slots=True)
class PositionalMetrics:
    """Error statistics. Coverage is a constructor argument, not an optional extra."""

    coverage: float
    located: int
    median_m: float | None
    p90_m: float | None
    p95_m: float | None
    max_m: float | None
    mean_m: float | None
    cdf: tuple[tuple[float, float], ...] = ()

    def as_dict(self) -> dict[str, object]:
        return {
            "coverage": round(self.coverage, 4),
            "located": self.located,
            "median_m": _round(self.median_m),
            "p90_m": _round(self.p90_m),
            "p95_m": _round(self.p95_m),
            "max_m": _round(self.max_m),
            "mean_m": _round(self.mean_m),
            "cdf": [[round(metres, 3), round(fraction, 4)] for metres, fraction in self.cdf],
        }

    def headline(self) -> str:
        """The specification's own phrasing: never an error without its coverage."""
        if self.median_m is None:
            return f"No coordinates emitted    Coverage: {self.coverage:.0%}"
        return (
            f"Median error: {self.median_m:.1f} m    P90: {self.p90_m:.1f} m    "
            f"Coverage: {self.coverage:.0%}"
        )


@dataclass(frozen=True, slots=True)
class CalibrationMetrics:
    """Whether ``horizontal_uncertainty_m`` can be believed.

    Under-reported uncertainty is the most damaging failure this system can have, because a small
    circle on a map is read as a fact. An algorithm with lower median error and badly calibrated
    error bars loses to one whose bars are honest (§3, uncertainty calibration).
    """

    evaluated: int
    p68_containment: float | None
    p95_containment: float | None
    error_to_sigma_ratio: float | None
    curve: tuple[tuple[float, float], ...] = ()

    def as_dict(self) -> dict[str, object]:
        return {
            "evaluated": self.evaluated,
            "p68_containment": _round(self.p68_containment, 4),
            "p95_containment": _round(self.p95_containment, 4),
            "error_to_sigma_ratio": _round(self.error_to_sigma_ratio, 4),
            "calibration_curve": [
                [round(predicted, 3), round(observed, 3)] for predicted, observed in self.curve
            ],
        }

    @property
    def verdict(self) -> str:
        if self.p68_containment is None:
            return "UNMEASURED"
        if self.p68_containment < 0.55:
            return "OVERCONFIDENT"
        if self.p68_containment > 0.85:
            return "UNDERCONFIDENT"
        return "CALIBRATED"


@dataclass(frozen=True, slots=True)
class Interval:
    """A bootstrap confidence interval over sessions."""

    point: float
    low: float
    high: float
    resamples: int

    def overlaps(self, other: "Interval") -> bool:
        return self.low <= other.high and other.low <= self.high

    def as_dict(self) -> dict[str, object]:
        return {
            "point": round(self.point, 4),
            "low": round(self.low, 4),
            "high": round(self.high, 4),
            "resamples": self.resamples,
        }

    def __str__(self) -> str:
        return f"[{self.low:.3f}, {self.high:.3f}]"


def classification_metrics(
    records: Sequence[Record],
    model: ReferenceModel,
) -> ClassificationMetrics:
    if not records:
        return ClassificationMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, {})

    answered = [record for record in records if record.predicted_zone_id]
    correct = sum(1 for record in answered if record.predicted_zone_id == record.truth_zone_id)
    top2 = sum(
        1 for record in records if record.truth_zone_id in record.ranked_zone_ids[:2]
    )
    building_correct = sum(
        1 for record in records if record.predicted_building_id == record.truth_building_id
    )
    adjacent = sum(
        1
        for record in answered
        if record.predicted_zone_id != record.truth_zone_id
        and model.adjacency(record.truth_zone_id, record.predicted_zone_id or "").value
        in ("ADJACENT", "RESTRICTED")
    )

    confusion: dict[str, dict[str, int]] = {}
    for record in records:
        predicted = record.predicted_zone_id or "NONE"
        row = confusion.setdefault(record.truth_zone_id, {})
        row[predicted] = row.get(predicted, 0) + 1

    return ClassificationMetrics(
        samples=len(records),
        zone_accuracy=correct / len(records),
        zone_coverage=len(answered) / len(records),
        zone_accuracy_answered=correct / len(answered) if answered else 0.0,
        zone_top2_accuracy=top2 / len(records),
        building_accuracy=building_correct / len(records),
        adjacent_error_rate=adjacent / len(records),
        confusion=confusion,
    )


def positional_metrics(records: Sequence[Record]) -> PositionalMetrics:
    errors = sorted(
        record.error_m for record in records if record.error_m is not None
    )
    coverage = len(errors) / len(records) if records else 0.0
    if not errors:
        return PositionalMetrics(coverage, 0, None, None, None, None, None, ())

    array = np.array(errors, dtype=float)
    return PositionalMetrics(
        coverage=coverage,
        located=len(errors),
        median_m=float(np.median(array)),
        p90_m=float(np.percentile(array, 90)),
        p95_m=float(np.percentile(array, 95)),
        max_m=float(array.max()),
        mean_m=float(array.mean()),
        cdf=_cdf(errors),
    )


def calibration_metrics(records: Sequence[Record]) -> CalibrationMetrics:
    pairs = [
        (record.error_m, record.uncertainty_m)
        for record in records
        if record.error_m is not None and record.uncertainty_m
    ]
    if not pairs:
        return CalibrationMetrics(0, None, None, None, ())

    errors = np.array([pair[0] for pair in pairs], dtype=float)
    sigmas = np.array([pair[1] for pair in pairs], dtype=float)

    within_68 = float(np.mean(errors <= sigmas))
    within_95 = float(np.mean(errors <= P95_SIGMA * sigmas))
    ratio = float(errors.mean() / sigmas.mean()) if sigmas.mean() > 0 else None

    # Predicted-versus-observed quantiles. A well-calibrated method traces the diagonal; a method
    # that reports the same sigma regardless of conditions traces a horizontal line, which is the
    # failure the containment fraction alone can hide.
    quantiles = np.arange(0.1, 1.0, 0.1)
    curve = tuple(
        (float(np.quantile(sigmas, q)), float(np.quantile(errors, q))) for q in quantiles
    )

    return CalibrationMetrics(
        evaluated=len(pairs),
        p68_containment=within_68,
        p95_containment=within_95,
        error_to_sigma_ratio=ratio,
        curve=curve,
    )


def bootstrap(
    records: Sequence[Record],
    metric: Callable[[Sequence[Record]], float | None],
    seed: int,
    resamples: int = BOOTSTRAP_RESAMPLES,
) -> Interval | None:
    """A 95% interval by resampling **sessions**, not samples.

    Resampling samples would treat 40 captures from one session as 40 independent observations of
    the site, and the interval would come out several times narrower than the truth. Sessions are
    the unit that repeats, so sessions are the unit that gets resampled (§2.5).
    """
    if not records:
        return None
    point = metric(records)
    if point is None:
        return None

    by_session: dict[str, list[Record]] = {}
    for record in records:
        by_session.setdefault(record.session_id, []).append(record)
    sessions = sorted(by_session)
    if len(sessions) < 2:
        # One session gives one draw, repeated. An interval computed from it would be a point
        # dressed up as a range, which is worse than saying nothing.
        return Interval(point=point, low=point, high=point, resamples=0)

    rng = np.random.default_rng(seed)
    values: list[float] = []
    for _ in range(resamples):
        picked = rng.integers(0, len(sessions), size=len(sessions))
        resampled: list[Record] = []
        for index in picked:
            resampled.extend(by_session[sessions[index]])
        value = metric(resampled)
        if value is not None:
            values.append(value)

    if not values:
        return Interval(point=point, low=point, high=point, resamples=0)
    array = np.array(values, dtype=float)
    return Interval(
        point=point,
        low=float(np.percentile(array, 2.5)),
        high=float(np.percentile(array, 97.5)),
        resamples=len(values),
    )


def zone_accuracy_of(records: Sequence[Record]) -> float | None:
    if not records:
        return None
    return sum(
        1 for record in records if record.predicted_zone_id == record.truth_zone_id
    ) / len(records)


def median_error_of(records: Sequence[Record]) -> float | None:
    errors = [record.error_m for record in records if record.error_m is not None]
    if not errors:
        return None
    return float(np.median(np.array(errors, dtype=float)))


def p90_error_of(records: Sequence[Record]) -> float | None:
    errors = [record.error_m for record in records if record.error_m is not None]
    if not errors:
        return None
    return float(np.percentile(np.array(errors, dtype=float), 90))


def p68_containment_of(records: Sequence[Record]) -> float | None:
    pairs = [
        (record.error_m, record.uncertainty_m)
        for record in records
        if record.error_m is not None and record.uncertainty_m
    ]
    if not pairs:
        return None
    return sum(1 for error, sigma in pairs if error <= sigma) / len(pairs)


def median_cpu_ms(records: Sequence[Record]) -> float | None:
    values = [record.cpu_ms for record in records]
    if not values:
        return None
    return float(np.median(np.array(values, dtype=float)))


def data_quality(records: Sequence[Record], model: ReferenceModel) -> dict[str, object]:
    """Per-run facts about the evidence, reported once rather than per algorithm (§3)."""
    zones_with_truth = {record.truth_zone_id for record in records}
    return {
        "held_out_samples": len(records),
        "held_out_sessions": len(({record.session_id for record in records})),
        "zones_in_test": len(zones_with_truth),
        "zones_without_test_coverage": sorted(set(model.zones) - zones_with_truth),
        "survey_points_in_test": len({record.survey_point_id for record in records}),
    }


def _cdf(sorted_errors: Sequence[float]) -> tuple[tuple[float, float], ...]:
    total = len(sorted_errors)
    return tuple(
        (float(value), (index + 1) / total) for index, value in enumerate(sorted_errors)
    )


def _round(value: float | None, digits: int = 3) -> float | None:
    return None if value is None else round(float(value), digits)
