"""Phase 9 — temporal filtering.

Baselines first, deliberately: a rolling median over coordinates and an EMA over confidence
(``docs/10-positioning-mathematical-architecture.md`` §10). A Kalman filter and an HMM zone model
are registered candidates and stay **unimplemented** until the benchmark shows the baseline is the
binding constraint. Implementing a Kalman filter before measuring whether the median is the problem
would add tuning parameters, hide the error behind a model, and make the regression untraceable.

The median rather than the mean, because a single wild estimate — one cached scan from an observer
in the wrong building — should be rejected rather than averaged in.

Filtering is applied to the emitted coordinate, not to the provenance. An estimate still refers to
its own instant and still cites the observations from its own window; the coordinate is stabilized
across the window's neighbours and the row is flagged so the smoothing is visible rather than
implicit.
"""

from __future__ import annotations

import statistics
from dataclasses import replace
from typing import Sequence

from ..models import PositionEstimate
from ..params import MovementParams
from ..version import MOVEMENT_ENGINE_VERSION

FILTERED_FLAG = "TEMPORAL_MEDIAN_FILTERED"


class TemporalMedianFilterV1:
    id = "temporal_median_v1"
    version = MOVEMENT_ENGINE_VERSION

    def smooth(
        self, estimates: Sequence[PositionEstimate], params: MovementParams
    ) -> tuple[PositionEstimate, ...]:
        return smooth_track(estimates, params)


def smooth_track(
    estimates: Sequence[PositionEstimate], params: MovementParams
) -> tuple[PositionEstimate, ...]:
    """Rolling median on coordinates, EMA on confidence, for one device's ordered track.

    Only coordinate-bearing estimates are smoothed, and only against other coordinate-bearing
    estimates of the same zone. Averaging a position across a zone boundary would place a device in
    a doorway it was never observed in — the classic artefact of smoothing a categorical decision as
    if it were continuous.
    """
    if params.median_window <= 1 or not estimates:
        return tuple(estimates)

    window = params.median_window
    alpha = params.confidence_ema_alpha
    ordered = sorted(estimates, key=lambda e: (e.timestamp_utc, e.estimate_id))

    smoothed: list[PositionEstimate] = []
    ema: float | None = None

    for index, estimate in enumerate(ordered):
        ema = estimate.confidence if ema is None else alpha * estimate.confidence + (1 - alpha) * ema
        confidence = round(min(1.0, max(0.0, ema)), 4)

        if estimate.x is None:
            smoothed.append(replace(estimate, confidence=confidence))
            continue

        start = max(0, index - window + 1)
        neighbours = [
            other
            for other in ordered[start : index + 1]
            if other.x is not None and other.zone_id == estimate.zone_id
        ]
        if len(neighbours) < 2:
            smoothed.append(replace(estimate, confidence=confidence))
            continue

        x = statistics.median([float(other.x) for other in neighbours])
        y = statistics.median([float(other.y) for other in neighbours])

        # Smoothing across a scatter of positions does not make the result more certain than its
        # own inputs, so the uncertainty is widened by how far the filter moved the point.
        shift = ((x - float(estimate.x)) ** 2 + (y - float(estimate.y)) ** 2) ** 0.5
        sigma = estimate.horizontal_uncertainty_m
        widened = round(max(sigma or 0.0, ((sigma or 0.0) ** 2 + shift**2) ** 0.5), 3)

        flags = estimate.quality_flags
        if FILTERED_FLAG not in flags and shift > 1e-6:
            flags = (*flags, FILTERED_FLAG)

        smoothed.append(
            replace(
                estimate,
                x=round(x, 3),
                y=round(y, 3),
                horizontal_uncertainty_m=widened,
                confidence=confidence,
                quality_flags=flags,
            )
        )

    return tuple(smoothed)
