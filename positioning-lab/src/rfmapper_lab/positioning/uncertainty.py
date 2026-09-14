"""Phase 8 — uncertainty, computed rather than assigned.

The central claim of ``docs/10-positioning-mathematical-architecture.md`` §8: a geometric spread
says how *consistent* the inputs were, not how *accurate* the method is. Only held-out ground truth
can supply the second, so the reported value is

.. math:: \\sigma_h = \\sqrt{\\sigma^2_{geometric} + \\sigma^2_{empirical}}

and the empirical term comes from the benchmark harness. Until a site has been validated there is
no empirical term to add, and the estimate carries ``UNVALIDATED_UNCERTAINTY`` — not silently, and
not by quietly substituting a default that would look like a measurement.

Under-reported uncertainty is the most damaging failure this system can have. A small circle on a
map is read as a fact, so the bias here is deliberately towards reporting a wider interval than the
geometry alone suggests.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

from ..params import UncertaintyParams
from ..version import UNCERTAINTY_ENGINE_VERSION


@dataclass(frozen=True, slots=True)
class EmpiricalErrorModel:
    """Per-method P68 error measured on a held-out test split.

    ``dataset_kind`` travels with it because an empirical term measured on synthetic data is not
    evidence about a real site, and the flag on the resulting estimate has to say which it was.
    """

    p68_by_method: dict[str, float] = field(default_factory=dict)
    dataset_kind: str = "NONE"
    source_report_id: str | None = None
    version: str = UNCERTAINTY_ENGINE_VERSION

    @property
    def validated(self) -> bool:
        return bool(self.p68_by_method) and self.dataset_kind == "REAL"

    def p68_for(self, method: str) -> float | None:
        return self.p68_by_method.get(method)

    @classmethod
    def unvalidated(cls) -> "EmpiricalErrorModel":
        return cls()

    @classmethod
    def load(cls, path: Path) -> "EmpiricalErrorModel":
        document = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            p68_by_method={
                str(key): float(value)
                for key, value in (document.get("p68_by_method") or {}).items()
            },
            dataset_kind=str(document.get("dataset_kind") or "NONE"),
            source_report_id=document.get("report_id"),
        )

    def as_dict(self) -> dict[str, object]:
        return {
            "p68_by_method": dict(sorted(self.p68_by_method.items())),
            "dataset_kind": self.dataset_kind,
            "report_id": self.source_report_id,
            "version": self.version,
        }


def resolve_uncertainty(
    method: str,
    sigma_geometric_m: float | None,
    empirical: EmpiricalErrorModel,
    params: UncertaintyParams,
) -> tuple[float | None, tuple[str, ...]]:
    """Combine the geometric and empirical terms, and say what the result rests on.

    Returns ``(None, flags)`` for a method that emits no coordinates — the zone *is* the answer, so
    there is no horizontal uncertainty to report, and inventing one would imply a point estimate
    that was never made.
    """
    if sigma_geometric_m is None:
        return None, ()

    flags: list[str] = []
    empirical_term = empirical.p68_for(method)

    if empirical_term is None:
        # No held-out measurement for this method on this site yet. The geometric term is reported
        # as-is and flagged, rather than being padded by a guess that would be indistinguishable
        # from a real number.
        empirical_term = 0.0
        flags.append("UNVALIDATED_UNCERTAINTY")
    elif empirical.dataset_kind != "REAL":
        flags.append("UNCERTAINTY_FROM_SYNTHETIC_BENCHMARK")

    combined = math.sqrt(max(0.0, sigma_geometric_m) ** 2 + empirical_term**2)

    if combined < params.min_uncertainty_m:
        # A sub-metre claim from RSSI fingerprinting is not credible at any consistency level, so
        # the floor applies regardless of how tightly the inputs agreed.
        combined = params.min_uncertainty_m
        flags.append("UNCERTAINTY_FLOORED")
    if combined > params.max_uncertainty_m:
        combined = params.max_uncertainty_m
        flags.append("UNCERTAINTY_CAPPED")

    return round(combined, 3), tuple(flags)


def containment(errors: list[float], sigmas: list[float], multiplier: float = 1.0) -> float | None:
    """Fraction of samples whose error falls inside ``multiplier * sigma``.

    The metric that decides whether ``horizontal_uncertainty_m`` can be believed: P68 containment
    should land near 0.68 and P95 near 0.95. Systematically lower means the error bars are lies.
    """
    pairs = [
        (error, sigma)
        for error, sigma in zip(errors, sigmas)
        if sigma is not None and sigma > 0 and error is not None
    ]
    if not pairs:
        return None
    inside = sum(1 for error, sigma in pairs if error <= multiplier * sigma)
    return inside / len(pairs)
