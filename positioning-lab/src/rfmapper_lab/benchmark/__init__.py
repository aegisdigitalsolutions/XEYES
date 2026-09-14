"""Deliverables 5 and 6 — benchmark methodology and error metrics.

The rule this package exists to enforce: no accuracy claim without a number from a held-out test
split, and no more complex algorithm adopted until it measurably beats the incumbent on the same
held-out data by a margin wider than the bootstrap interval
(``docs/12-benchmark-methodology-and-error-metrics.md``).
"""

from __future__ import annotations

from .baseline import Baseline, GateResult, check
from .harness import (
    BenchmarkReport,
    Candidate,
    CandidateResult,
    default_candidates,
    run_benchmark,
    select,
    with_movement,
)
from .metrics import (
    CalibrationMetrics,
    ClassificationMetrics,
    Interval,
    PositionalMetrics,
    Record,
    bootstrap,
    calibration_metrics,
    classification_metrics,
    positional_metrics,
)
from .movement import (
    MovementMetrics,
    SweepPoint,
    TruthPoint,
    movement_metrics,
    operating_point,
    sweep_hysteresis,
    true_transitions,
    truth_points,
)
from .report import format_report
from .splits import SessionSplit, held_out_samples, session_ids, split_sessions

__all__ = [
    "Baseline",
    "BenchmarkReport",
    "CalibrationMetrics",
    "Candidate",
    "CandidateResult",
    "ClassificationMetrics",
    "GateResult",
    "Interval",
    "MovementMetrics",
    "PositionalMetrics",
    "Record",
    "SessionSplit",
    "SweepPoint",
    "TruthPoint",
    "bootstrap",
    "calibration_metrics",
    "check",
    "classification_metrics",
    "default_candidates",
    "format_report",
    "held_out_samples",
    "movement_metrics",
    "operating_point",
    "positional_metrics",
    "run_benchmark",
    "select",
    "session_ids",
    "split_sessions",
    "sweep_hysteresis",
    "true_transitions",
    "truth_points",
    "with_movement",
]
