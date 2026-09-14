"""Movement and transition metrics, per ``docs/12`` §3 (movement and transitions).

Measured on the walk-test route and never on static surveys. A static capture has no transitions to
detect, so a hysteresis setting tuned against one would look flawless and then oscillate on the
first device that actually moved.

The sweep at the end is the point of the module. Detection latency and false-transition rate trade
directly against each other through the three hysteresis thresholds, and there is no setting that
is right for every site. Reporting the curve puts the choice in front of the administrator instead
of leaving it as a developer's default.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Iterable, Sequence

from ..models import MovementState, ZoneEventType
from ..parsing.dataset import Dataset
from ..pipeline import PipelineConfig, PipelineResult, run_pipeline
from ..timeutil import parse_ms

#: Events that name a destination zone, and so can correspond to a move in the walk-test log. An
#: arrival after a spell in ``LOST`` is recorded as an enter rather than a transition, and counting
#: only transitions would score those moves as missed when the engine in fact found them.
_ARRIVALS = frozenset({ZoneEventType.RF_ZONE_TRANSITION, ZoneEventType.RF_ZONE_ENTER})


@dataclass(frozen=True, slots=True)
class TruthPoint:
    """Where a device really was, from the walk-test log."""

    device_id: str
    timestamp_ms: int
    zone_id: str

    @classmethod
    def from_dict(cls, row: dict) -> "TruthPoint":
        return cls(
            device_id=str(row["device_id"]),
            timestamp_ms=parse_ms(str(row["timestamp_utc"])),
            zone_id=str(row["zone_id"]),
        )


@dataclass(frozen=True, slots=True)
class TrueTransition:
    device_id: str
    timestamp_ms: int
    origin_zone_id: str
    destination_zone_id: str


@dataclass(frozen=True, slots=True)
class MovementMetrics:
    true_transitions: int
    committed_transitions: int
    matched: int
    median_latency_s: float | None
    p90_latency_s: float | None
    missed_rate: float
    false_per_hour: float
    oscillation_rate: float
    topology_violation_rate: float
    lost_fraction: float
    duration_hours: float

    def as_dict(self) -> dict[str, object]:
        return {
            "true_transitions": self.true_transitions,
            "committed_transitions": self.committed_transitions,
            "matched": self.matched,
            "median_latency_s": _round(self.median_latency_s),
            "p90_latency_s": _round(self.p90_latency_s),
            "missed_rate": round(self.missed_rate, 4),
            "false_per_hour": round(self.false_per_hour, 4),
            "oscillation_rate": round(self.oscillation_rate, 4),
            "topology_violation_rate": round(self.topology_violation_rate, 4),
            "lost_fraction": round(self.lost_fraction, 4),
            "duration_hours": round(self.duration_hours, 4),
        }


def true_transitions(truth: Sequence[TruthPoint]) -> tuple[TrueTransition, ...]:
    """Zone changes in the walk-test log, per device, in time order."""
    by_device: dict[str, list[TruthPoint]] = {}
    for point in truth:
        by_device.setdefault(point.device_id, []).append(point)

    transitions: list[TrueTransition] = []
    for device_id in sorted(by_device):
        ordered = sorted(by_device[device_id], key=lambda point: point.timestamp_ms)
        previous = ordered[0].zone_id
        for point in ordered[1:]:
            if point.zone_id != previous:
                transitions.append(
                    TrueTransition(
                        device_id=device_id,
                        timestamp_ms=point.timestamp_ms,
                        origin_zone_id=previous,
                        destination_zone_id=point.zone_id,
                    )
                )
                previous = point.zone_id
    return tuple(transitions)


def movement_metrics(
    result: PipelineResult,
    truth: Sequence[TruthPoint],
    match_window_ms: int = 180_000,
) -> MovementMetrics:
    """Score committed transitions against the walk-test log.

    A commit matches a true transition when it names the same destination for the same device
    within the match window *after* it happened. Matching on destination alone, ignoring direction
    of time, would let a commit that fired before the device moved count as a prompt detection.
    """
    truth_transitions = true_transitions(truth)
    committed = sorted(
        (
            transition
            for transition in result.transitions
            if transition.event_type in _ARRIVALS
        ),
        key=lambda transition: transition.transition_confirmed_utc,
    )

    unmatched = list(truth_transitions)
    latencies: list[float] = []
    matched = 0

    for transition in committed:
        confirmed_ms = parse_ms(transition.transition_confirmed_utc)
        best_index = None
        best_gap = None
        for index, candidate in enumerate(unmatched):
            if candidate.device_id != transition.device_id:
                continue
            if candidate.destination_zone_id != transition.destination_zone_id:
                continue
            gap = confirmed_ms - candidate.timestamp_ms
            if gap < 0 or gap > match_window_ms:
                continue
            if best_gap is None or gap < best_gap:
                best_gap, best_index = gap, index
        if best_index is not None and best_gap is not None:
            matched += 1
            latencies.append(best_gap / 1000.0)
            unmatched.pop(best_index)

    commits = len(committed)
    duration_hours = _duration_hours(truth)
    violations = sum(
        1 for transition in committed if transition.topology_status.value == "NON_ADJACENT"
    )
    lost = sum(
        1 for movement in result.movements if movement.state is MovementState.LOST
    )

    return MovementMetrics(
        true_transitions=len(truth_transitions),
        committed_transitions=commits,
        matched=matched,
        median_latency_s=_percentile(latencies, 50),
        p90_latency_s=_percentile(latencies, 90),
        missed_rate=(
            (len(truth_transitions) - matched) / len(truth_transitions)
            if truth_transitions
            else 0.0
        ),
        false_per_hour=((commits - matched) / duration_hours) if duration_hours > 0 else 0.0,
        oscillation_rate=(result.oscillations / commits) if commits else 0.0,
        topology_violation_rate=(violations / commits) if commits else 0.0,
        lost_fraction=(lost / len(result.movements)) if result.movements else 0.0,
        duration_hours=duration_hours,
    )


@dataclass(frozen=True, slots=True)
class SweepPoint:
    min_candidate_duration_ms: int
    min_transition_confidence: float
    min_supporting_observations: int
    metrics: MovementMetrics

    def as_dict(self) -> dict[str, object]:
        return {
            "min_candidate_duration_ms": self.min_candidate_duration_ms,
            "min_transition_confidence": self.min_transition_confidence,
            "min_supporting_observations": self.min_supporting_observations,
            **self.metrics.as_dict(),
        }


#: The grid. Deliberately small: the purpose is to show the shape of the trade-off, not to search
#: for an optimum that a single walk test could not justify anyway.
DEFAULT_DURATIONS = (15_000, 30_000, 45_000, 90_000)
DEFAULT_CONFIDENCES = (0.4, 0.6, 0.75)
DEFAULT_SUPPORTS = (2, 3)


def sweep_hysteresis(
    dataset: Dataset,
    truth: Sequence[TruthPoint],
    config: PipelineConfig,
    durations: Iterable[int] = DEFAULT_DURATIONS,
    confidences: Iterable[float] = DEFAULT_CONFIDENCES,
    supports: Iterable[int] = DEFAULT_SUPPORTS,
) -> tuple[SweepPoint, ...]:
    r"""Sweep :math:`(T_{min}, C_{min}, N_{min})` and report the whole curve.

    Every point is a full pipeline run, which is why the grid is coarse. The output is meant to be
    read as a curve — latency falls and false commits rise as the thresholds loosen — rather than
    mined for a single best cell.
    """
    points: list[SweepPoint] = []
    for duration in sorted(set(durations)):
        for confidence in sorted(set(confidences)):
            for support in sorted(set(supports)):
                movement = replace(
                    config.params.movement,
                    min_candidate_duration_ms=duration,
                    min_transition_confidence=confidence,
                    min_supporting_observations=support,
                )
                params = replace(config.params, movement=movement)
                result = run_pipeline(dataset, replace(config, params=params))
                points.append(
                    SweepPoint(
                        min_candidate_duration_ms=duration,
                        min_transition_confidence=confidence,
                        min_supporting_observations=support,
                        metrics=movement_metrics(result, truth),
                    )
                )
    return tuple(points)


def operating_point(points: Sequence[SweepPoint], max_false_per_hour: float = 1.0) -> SweepPoint | None:
    """The lowest-latency setting that stays under a false-commit budget.

    The budget is the administrator's input, not the algorithm's. A control room that investigates
    every transition can afford far fewer false commits than a dashboard reviewed once a day, and
    no default can know which one it is looking at.
    """
    affordable = [
        point
        for point in points
        if point.metrics.false_per_hour <= max_false_per_hour
        and point.metrics.median_latency_s is not None
    ]
    if not affordable:
        return None
    return min(
        affordable,
        key=lambda point: (
            point.metrics.median_latency_s or 0.0,
            point.metrics.missed_rate,
            point.min_candidate_duration_ms,
        ),
    )


def _duration_hours(truth: Sequence[TruthPoint]) -> float:
    if len(truth) < 2:
        return 0.0
    stamps = [point.timestamp_ms for point in truth]
    return max(0.0, (max(stamps) - min(stamps)) / 3_600_000.0)


def _percentile(values: Sequence[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (percentile / 100.0) * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    weight = position - low
    return ordered[low] * (1 - weight) + ordered[high] * weight


def _round(value: float | None, digits: int = 3) -> float | None:
    return None if value is None else round(float(value), digits)


def truth_points(rows: Iterable[dict]) -> tuple[TruthPoint, ...]:
    return tuple(TruthPoint.from_dict(row) for row in rows)
