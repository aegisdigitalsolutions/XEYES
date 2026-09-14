"""The regression gate, per ``docs/12-benchmark-methodology-and-error-metrics.md`` §5.

The selected configuration's metrics are committed as ``positioning-lab/benchmarks/baseline.json``.
A change that degrades zone accuracy, median error, P90 error or P68 containment *beyond the
confidence interval* fails. Inside the interval it passes, because a benchmark that fails on noise
gets disabled within a month and then nothing is checked at all.

Two asymmetries are deliberate. A baseline recorded against a different dataset is not comparable,
so the gate refuses to judge rather than passing quietly. And P68 containment is scored on distance
from 0.68 in either direction: an algorithm that became *less* confident than its errors justify has
also regressed, because uncertainty that is too wide makes a usable estimate look unusable.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .harness import BenchmarkReport, CandidateResult

#: Metrics the gate checks, and whether a larger number is better.
GATED: tuple[tuple[str, bool], ...] = (
    ("zone_accuracy", True),
    ("median_error_m", False),
    ("p90_error_m", False),
)

#: Containment is judged by proximity to this, not by magnitude.
TARGET_CONTAINMENT = 0.68


@dataclass(frozen=True, slots=True)
class Baseline:
    label: str
    dataset_content_sha256: str
    reference_model_id: str
    algorithm_version: str
    metrics: dict[str, float]
    intervals: dict[str, dict[str, float]]
    generated_at_utc: str = ""

    @classmethod
    def from_report(cls, report: BenchmarkReport) -> "Baseline | None":
        result = report.result_for(report.selected or "")
        if result is None:
            return None
        return cls(
            label=result.candidate.label,
            dataset_content_sha256=report.dataset_content_sha256,
            reference_model_id=report.reference_model_id,
            algorithm_version=report.algorithm_version,
            metrics=_metrics_of(result),
            intervals={
                name: {"low": interval.low, "high": interval.high}
                for name, interval in sorted(result.intervals.items())
            },
            generated_at_utc=report.generated_at_utc,
        )

    def as_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "dataset_content_sha256": self.dataset_content_sha256,
            "reference_model_id": self.reference_model_id,
            "algorithm_version": self.algorithm_version,
            "generated_at_utc": self.generated_at_utc,
            "metrics": {name: round(value, 6) for name, value in sorted(self.metrics.items())},
            "intervals": self.intervals,
        }

    @classmethod
    def load(cls, path: Path) -> "Baseline":
        document = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            label=document["label"],
            dataset_content_sha256=document["dataset_content_sha256"],
            reference_model_id=document.get("reference_model_id", ""),
            algorithm_version=document.get("algorithm_version", ""),
            metrics=dict(document.get("metrics", {})),
            intervals=dict(document.get("intervals", {})),
            generated_at_utc=document.get("generated_at_utc", ""),
        )

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(self.as_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )


@dataclass(frozen=True, slots=True)
class GateResult:
    passed: bool
    comparable: bool
    findings: tuple[str, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "comparable": self.comparable,
            "findings": list(self.findings),
        }

    def describe(self) -> str:
        head = (
            "PASS"
            if self.passed and self.comparable
            else ("INCOMPARABLE" if not self.comparable else "FAIL")
        )
        return "\n".join([f"Regression gate: {head}", *(f"  - {f}" for f in self.findings)])


def check(report: BenchmarkReport, baseline: Baseline) -> GateResult:
    """Compare a fresh report against the committed baseline."""
    if report.dataset_content_sha256 != baseline.dataset_content_sha256:
        return GateResult(
            passed=False,
            comparable=False,
            findings=(
                "dataset content hash differs from the baseline "
                f"({report.dataset_content_sha256[:8]} vs {baseline.dataset_content_sha256[:8]}); "
                "these numbers are not comparable and the gate declines to judge them",
            ),
        )

    result = report.result_for(baseline.label) or report.result_for(report.selected or "")
    if result is None:
        return GateResult(
            passed=False,
            comparable=False,
            findings=(f"no candidate named '{baseline.label}' in this report",),
        )

    current = _metrics_of(result)
    findings: list[str] = []
    passed = True

    for name, higher_is_better in GATED:
        now = current.get(name)
        before = baseline.metrics.get(name)
        if now is None or before is None:
            findings.append(f"{name}: not measured on both sides, skipped")
            continue

        tolerance = _tolerance(baseline.intervals.get(name), before)
        degraded = (before - now) > tolerance if higher_is_better else (now - before) > tolerance
        direction = "fell" if higher_is_better else "rose"
        if degraded:
            passed = False
            findings.append(
                f"{name} {direction} from {before:.3f} to {now:.3f}, beyond the "
                f"{tolerance:.3f} interval half-width"
            )
        else:
            findings.append(f"{name}: {before:.3f} -> {now:.3f} (within interval)")

    now_containment = current.get("p68_containment")
    before_containment = baseline.metrics.get("p68_containment")
    if now_containment is not None and before_containment is not None:
        tolerance = _tolerance(baseline.intervals.get("p68_containment"), before_containment)
        before_gap = abs(before_containment - TARGET_CONTAINMENT)
        now_gap = abs(now_containment - TARGET_CONTAINMENT)
        if (now_gap - before_gap) > tolerance:
            passed = False
            findings.append(
                f"p68_containment moved away from {TARGET_CONTAINMENT}: "
                f"{before_containment:.3f} -> {now_containment:.3f}"
            )
        else:
            findings.append(
                f"p68_containment: {before_containment:.3f} -> {now_containment:.3f} "
                f"(target {TARGET_CONTAINMENT})"
            )

    return GateResult(passed=passed, comparable=True, findings=tuple(findings))


def _metrics_of(result: CandidateResult) -> dict[str, float]:
    metrics: dict[str, float] = {
        "zone_accuracy": result.classification.zone_accuracy,
        "zone_top2_accuracy": result.classification.zone_top2_accuracy,
        "building_accuracy": result.classification.building_accuracy,
        "coverage": result.positional.coverage,
    }
    if result.positional.median_m is not None:
        metrics["median_error_m"] = result.positional.median_m
    if result.positional.p90_m is not None:
        metrics["p90_error_m"] = result.positional.p90_m
    if result.calibration.p68_containment is not None:
        metrics["p68_containment"] = result.calibration.p68_containment
    return metrics


def _tolerance(interval: dict[str, float] | None, point: float) -> float:
    """Half the baseline's confidence interval, with a floor.

    The floor matters when a small TEST split produces a degenerate interval: a zero-width interval
    would make the gate fail on the last decimal place of a rounding change, which is noise being
    reported as a regression.
    """
    if not interval:
        return max(0.02, abs(point) * 0.05)
    width = float(interval.get("high", point)) - float(interval.get("low", point))
    return max(0.01, width / 2.0)
