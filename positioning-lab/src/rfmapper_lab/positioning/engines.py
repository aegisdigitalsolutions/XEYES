"""Position estimation strategies, per ``docs/10-positioning-mathematical-architecture.md`` §6.

The strategies are tried in order of the precision they can justify, and each one declines when its
preconditions are not met. Declining is the important part: the fallback chain ends at
``pos_zone_only_v1``, which emits no coordinates at all, and that is a valid and preferred result.
A confident zone beats a fabricated point.

**RSSI is never converted to distance for positioning.** A calibrated log-distance model exists in
the parameter set as optional weak supporting evidence and is disabled by default. Free-space
propagation indoors is not assumed anywhere in this system, so the only source of genuine ranging
is RTT.
"""

from __future__ import annotations

import math
from typing import Sequence

import numpy as np
from scipy.optimize import least_squares

from ..models import (
    FingerprintPoint,
    LiveVector,
    Measurement,
    Placement,
    PrecisionTier,
    ReferenceModel,
    SensorType,
    ZoneResult,
)
from ..params import ParameterSet
from ..registry import positioning_engine

#: Order of attempt: the deepest tier the evidence can justify wins, and each strategy refuses
#: rather than degrades.
STRATEGY_ORDER: tuple[str, ...] = (
    "pos_rtt_multilateration_v1",
    "pos_wknn_centroid_v1",
    "pos_zone_centroid_v1",
    "pos_zone_only_v1",
)


@positioning_engine
class PosRttMultilaterationV1:
    r"""Robust least squares on RTT ranges.

    Minimizes :math:`\sum_i (\|x-a_i\|-d_i)^2/\sigma_i^2` with a soft-L1 loss, so one bad range
    does not drag the solution the way a plain quadratic loss would.

    The geometry check is what stops this from being dangerous. Three anchors in a line leave the
    along-baseline direction unconstrained: the solver converges, reports a small residual, and the
    answer is confidently wrong in one axis. So the Jacobian's condition number is tested and the
    method declines when the configuration cannot support a two-dimensional fix.
    """

    id = "pos_rtt_multilateration_v1"
    version = "1.0.0"

    def estimate(
        self,
        vector: LiveVector,
        zones: ZoneResult,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> Placement | None:
        ranges = _rtt_ranges(vector, model)
        if len(ranges) < params.positioning.min_rtt_anchors:
            return None

        anchors = np.array([[item.x, item.y] for item in ranges], dtype=float)
        distances = np.array([item.distance_m for item in ranges], dtype=float)
        sigmas = np.array([max(item.sigma_m, 0.1) for item in ranges], dtype=float)

        def residuals(position: np.ndarray) -> np.ndarray:
            modelled = np.linalg.norm(anchors - position, axis=1)
            return (modelled - distances) / sigmas

        start = anchors.mean(axis=0)
        solution = least_squares(residuals, start, loss="soft_l1", f_scale=1.0)
        if not solution.success:
            return None

        x, y = float(solution.x[0]), float(solution.x[1])
        condition = _condition_number(anchors, solution.x)
        if not math.isfinite(condition) or condition > params.positioning.max_geometry_condition:
            # Near-collinear anchors. Returning the solution with a wider error bar would still
            # place a point on a map, and the error is directional rather than radial, so a circle
            # would misdescribe it.
            return None

        metre_residuals = residuals(solution.x) * sigmas
        rms_residual = float(np.sqrt(np.mean(metre_residuals**2)))
        flags: list[str] = []
        if rms_residual > params.positioning.max_rtt_residual_m:
            flags.append("RTT_RESIDUAL_HIGH")

        # Floored by the empirical RTT bias: chipset bias is real, and a covariance-derived sigma
        # that ignored it would be optimistic in exactly the direction that matters.
        sigma = _covariance_sigma(anchors, solution.x, metre_residuals)
        sigma = max(sigma, rms_residual, params.positioning.rtt_bias_floor_m)

        return Placement(
            method=self.id,
            precision_tier=PrecisionTier.PRECISION_RANGE,
            x=x,
            y=y,
            sigma_geometric_m=sigma,
            supporting_observation_ids=tuple(sorted(item.observation_id for item in ranges)),
            residual_m=rms_residual,
            quality_flags=tuple(flags),
            detail={
                "anchors": float(len(ranges)),
                "geometry_condition": condition,
                "residual_m": rms_residual,
            },
        )


@positioning_engine
class PosWknnCentroidV1:
    """Uncertainty-weighted centroid of the best fingerprints in the winning zone.

    Requires at least two located fingerprints: a single one would return that survey point's own
    coordinates, which reports where somebody once stood rather than where the device is now.
    """

    id = "pos_wknn_centroid_v1"
    version = "1.0.0"

    def estimate(
        self,
        vector: LiveVector,
        zones: ZoneResult,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> Placement | None:
        best = zones.best
        if best is None:
            return None

        from ..zone.classifiers import signal_distance

        live = {
            identifier: float(measurement.rssi_normalized or 0.0)
            for identifier, measurement in vector.strongest_by_source().items()
        }
        located = [
            fingerprint
            for fingerprint in fingerprints
            if fingerprint.zone_id == best.zone_id and fingerprint.has_coordinates
        ]
        scored = sorted(
            (
                (signal_distance(live, fingerprint, params.zone), fingerprint)
                for fingerprint in located
            ),
            key=lambda item: (item[0], item[1].fingerprint_id),
        )
        usable = [item for item in scored if math.isfinite(item[0])][: params.positioning.centroid_k]
        if len(usable) < 2:
            return None

        weights = np.array([1.0 / (distance + 1.0) for distance, _ in usable], dtype=float)
        points = np.array([[f.x, f.y] for _, f in usable], dtype=float)
        total = float(weights.sum())
        centre = (points * weights[:, None]).sum(axis=0) / total

        # The weighted spatial spread of the contributors. This says how consistent the
        # fingerprints were, which is not the same as how accurate the method is — the empirical
        # term from the benchmark supplies that, and §8 adds the two in quadrature.
        offsets = np.linalg.norm(points - centre, axis=1)
        spread = float(np.sqrt((weights * offsets**2).sum() / total))

        return Placement(
            method=self.id,
            precision_tier=PrecisionTier.APPROXIMATE_POSITION,
            x=float(centre[0]),
            y=float(centre[1]),
            sigma_geometric_m=spread,
            supporting_fingerprint_ids=tuple(sorted(f.fingerprint_id for _, f in usable)),
            supporting_observation_ids=vector.observation_ids,
            detail={
                "contributing_fingerprints": float(len(usable)),
                "geometric_spread_m": spread,
                "best_signal_distance": float(usable[0][0]),
            },
        )


@positioning_engine
class PosZoneCentroidV1:
    """Zone polygon centroid with the zone's enclosing radius as uncertainty.

    Explicitly coarse, and the uncertainty says so: it is the radius of the zone, derived from real
    site geometry rather than from a constant. Used when the zone is known but no located
    fingerprint can refine a position within it.
    """

    id = "pos_zone_centroid_v1"
    version = "1.0.0"

    def estimate(
        self,
        vector: LiveVector,
        zones: ZoneResult,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> Placement | None:
        best = zones.best
        if best is None:
            return None
        zone = model.zones.get(best.zone_id)
        if zone is None:
            return None
        centre = zone.centroid
        radius = zone.radius_m
        if centre is None or radius is None or radius <= 0:
            return None

        return Placement(
            method=self.id,
            precision_tier=PrecisionTier.APPROXIMATE_POSITION,
            x=centre.x,
            y=centre.y,
            sigma_geometric_m=float(radius),
            supporting_observation_ids=vector.observation_ids,
            quality_flags=("ZONE_CENTROID_ONLY",),
            detail={"zone_radius_m": float(radius)},
        )


@positioning_engine
class PosZoneOnlyV1:
    """No coordinates. The zone is the answer.

    The terminal strategy, and a deliberately ordinary one. Most evidence in a real deployment
    supports a zone and nothing finer, and the correct output in that case is a zone — not a point
    with a large circle around it, which reads as a measurement that was never made.
    """

    id = "pos_zone_only_v1"
    version = "1.0.0"

    def estimate(
        self,
        vector: LiveVector,
        zones: ZoneResult,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> Placement | None:
        best = zones.best
        if best is None:
            return None
        return Placement(
            method=self.id,
            precision_tier=PrecisionTier.ZONE,
            supporting_observation_ids=vector.observation_ids,
            supporting_fingerprint_ids=best.fingerprint_ids,
            detail={"zone_score": float(best.score)},
        )


def place(
    vector: LiveVector,
    zones: ZoneResult,
    fingerprints: Sequence[FingerprintPoint],
    model: ReferenceModel,
    params: ParameterSet,
    strategies: Sequence[str] = STRATEGY_ORDER,
) -> Placement | None:
    """Try each strategy in order and take the first that does not decline.

    The order encodes the hierarchy in §7: emit the deepest level the evidence supports and stop
    there. There is no scoring across strategies, because comparing an RTT fix to a fingerprint
    centroid on a single number would hide the fact that they are different kinds of claim.
    """
    from ..registry import POSITIONING_ENGINES

    for strategy in strategies:
        engine = POSITIONING_ENGINES.get(strategy)
        placement = engine.estimate(vector, zones, fingerprints, model, params)
        if placement is not None:
            return placement
    return None


# -- RTT helpers ----------------------------------------------------------------------------------


class _Range:
    __slots__ = ("x", "y", "distance_m", "sigma_m", "observation_id", "node_id")

    def __init__(self, x: float, y: float, distance_m: float, sigma_m: float, observation_id: str, node_id: str):
        self.x = x
        self.y = y
        self.distance_m = distance_m
        self.sigma_m = sigma_m
        self.observation_id = observation_id
        self.node_id = node_id


def _rtt_ranges(vector: LiveVector, model: ReferenceModel) -> list[_Range]:
    """Ranges to anchors whose site-frame position is actually known.

    A range to an anchor with no coordinates is unusable — there is nothing to be 11 metres from —
    and an anchor is only located if an administrator measured it.
    """
    anchors = model.anchors_by_bssid()
    best: dict[str, _Range] = {}
    for measurement in vector.measurements:
        if measurement.sensor_type is not SensorType.RTT or measurement.rtt_distance_mm is None:
            continue
        node = anchors.get(measurement.radio_identifier)
        if node is None or node.x is None or node.y is None:
            continue
        sigma = _sigma_for(measurement)
        candidate = _Range(
            x=float(node.x),
            y=float(node.y),
            distance_m=measurement.rtt_distance_mm / 1000.0,
            sigma_m=sigma,
            observation_id=measurement.observation_id,
            node_id=node.node_id,
        )
        # One range per anchor: repeated measurements of the same anchor within a window are not
        # independent constraints, and counting them as such would shrink the reported covariance
        # without adding information.
        current = best.get(node.node_id)
        if current is None or candidate.sigma_m < current.sigma_m:
            best[node.node_id] = candidate
    return [best[node_id] for node_id in sorted(best)]


def _sigma_for(measurement: Measurement) -> float:
    """Per-anchor weight from the chipset's own reported standard deviation.

    When the chipset declines to report one, 2 metres is assumed and the resulting estimate is
    flagged downstream. That number is a placeholder awaiting the RTT characterization in
    ``docs/15-assumptions-requiring-validation.md``.
    """
    if measurement.rtt_stddev_mm is not None and measurement.rtt_stddev_mm > 0:
        return measurement.rtt_stddev_mm / 1000.0
    return 2.0


def _jacobian(anchors: np.ndarray, position: np.ndarray) -> np.ndarray:
    offsets = position - anchors
    norms = np.linalg.norm(offsets, axis=1)
    norms = np.where(norms < 1e-6, 1e-6, norms)
    return offsets / norms[:, None]


def _condition_number(anchors: np.ndarray, position: np.ndarray) -> float:
    """Condition number of the range Jacobian: how well the geometry constrains both axes."""
    jacobian = _jacobian(anchors, position)
    singular = np.linalg.svd(jacobian, compute_uv=False)
    smallest = float(singular[-1])
    if smallest <= 1e-9:
        return math.inf
    return float(singular[0] / smallest)


def _covariance_sigma(
    anchors: np.ndarray, position: np.ndarray, metre_residuals: np.ndarray
) -> float:
    r"""Position sigma from :math:`\sigma^2 (J^\top J)^{-1}`, inflated by the residual scale.

    The residuals are in metres rather than in sigma units, so the trace comes out in metres
    squared and the square root is a distance. Feeding weighted residuals in here instead would
    produce a unitless number that looks like a plausible uncertainty.
    """
    jacobian = _jacobian(anchors, position)
    degrees_of_freedom = max(1, len(anchors) - 2)
    scale = float(np.sum(metre_residuals**2) / degrees_of_freedom)
    try:
        covariance = np.linalg.inv(jacobian.T @ jacobian) * scale
    except np.linalg.LinAlgError:
        return math.inf
    trace = float(np.trace(covariance))
    if not math.isfinite(trace) or trace < 0:
        return math.inf
    return math.sqrt(trace)
