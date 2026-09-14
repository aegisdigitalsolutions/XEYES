"""Phase 9 — confidence as a product of named, inspectable factors.

.. math:: C = \\operatorname{clip}\\left(\\prod_i f_i^{\\alpha_i}, 0, 1\\right)

Confidence is never a constant and never a tuned single number. Every factor is recorded on the
estimate, so "confidence 0.87" can always be answered with *which* eight numbers produced it
(``docs/10-positioning-mathematical-architecture.md`` §9). A confidence that cannot be explained
cannot be defended, and an operator who cannot see why a number is low cannot act on it.

The factor worth singling out is ``margin``: the gap between the best and second-best zone
candidate. Absolute similarity to a fingerprint says how well the vector matched; the gap says
whether the site can tell those two zones apart at all, which is the question that actually governs
whether the answer is usable.
"""

from __future__ import annotations

import math
from typing import Mapping

from ..models import LiveVector, Placement, PrecisionTier, ReferenceModel, SensorType, ZoneResult
from ..params import ParameterSet

#: Exponents: how much each factor is allowed to move the product. Placeholders to be tuned against
#: walk-test ground truth; listed in ``docs/15-assumptions-requiring-validation.md``.
EXPONENTS: dict[str, float] = {
    "observer_count": 1.0,
    "freshness": 1.0,
    "margin": 1.0,
    "sensor_agreement": 0.75,
    "rtt_quality": 0.75,
    "continuity": 0.5,
    "topology": 0.75,
    "calibration_density": 0.5,
}


def confidence_factors(
    vector: LiveVector,
    zones: ZoneResult,
    placement: Placement,
    model: ReferenceModel,
    params: ParameterSet,
    frame_weights: Mapping[str, float],
    previous_zone_id: str | None = None,
    fingerprints_in_zone: int = 0,
) -> dict[str, float]:
    """Compute every factor. Each is in [0, 1] and each has a single, stateable meaning."""
    factors: dict[str, float] = {}

    observers = len(vector.observer_ids)
    # Two observers is the first point at which a claim is corroborated rather than asserted — but
    # only if their evidence carried weight. Three observers whose readings were all stale, all
    # uncalibrated and all from the weakest sensor corroborate nothing, and counting them as three
    # would let the count factor launder exactly the evidence the fusion weights discounted.
    strength = min(1.0, max(frame_weights.values(), default=0.0))
    factors["observer_count"] = min(1.0, 0.45 + 0.275 * min(observers, 3)) * max(0.5, strength)

    freshest = min((m.age_ms for m in vector.measurements), default=0)
    decay = 0.5 ** (freshest / max(1, params.fusion.freshness_half_life_ms))
    cached_share = sum(1 for m in vector.measurements if m.freshness == "CACHED") / max(
        1, len(vector.measurements)
    )
    factors["freshness"] = max(0.05, decay * (1.0 - 0.5 * cached_share))

    # Scaled so a decisive margin saturates rather than requiring an impossible 1.0.
    factors["margin"] = max(0.1, min(1.0, 0.35 + 1.3 * zones.margin))

    sensors = {m.sensor_type for m in vector.measurements}
    informative = sensors & {
        SensorType.WIFI_SCAN,
        SensorType.BLE,
        SensorType.RTT,
        SensorType.WIFI_ASSOCIATION,
    }
    factors["sensor_agreement"] = min(1.0, 0.6 + 0.2 * len(informative))

    if placement.method == "pos_rtt_multilateration_v1":
        residual = placement.residual_m or 0.0
        anchors = placement.detail.get("anchors", 3.0)
        factors["rtt_quality"] = max(
            0.2,
            min(1.0, (1.0 / (1.0 + residual / max(0.5, params.positioning.max_rtt_residual_m)))
                * min(1.0, anchors / 4.0 + 0.5)),
        )
    else:
        # Not a penalty: a method that never claimed ranged precision is not deficient for lacking
        # RTT evidence, so the factor is neutral.
        factors["rtt_quality"] = 1.0

    best_zone = zones.best.zone_id if zones.best else None
    if previous_zone_id is None or best_zone is None:
        factors["continuity"] = 0.85
    elif previous_zone_id == best_zone:
        factors["continuity"] = 1.0
    else:
        status = model.adjacency(previous_zone_id, best_zone)
        factors["continuity"] = {
            "ADJACENT": 0.9,
            "RESTRICTED": 0.7,
            "UNKNOWN_EDGE": 0.6,
            "NON_ADJACENT": 0.3,
        }.get(status.value, 0.6)

    if previous_zone_id is not None and best_zone is not None:
        status = model.adjacency(previous_zone_id, best_zone)
        factors["topology"] = 0.35 if status.value == "NON_ADJACENT" else 1.0
    else:
        factors["topology"] = 1.0

    # Local calibration density: a zone with one surveyed point is a weaker basis for a claim than
    # one with four, whatever the match looked like.
    factors["calibration_density"] = min(1.0, 0.45 + 0.2 * min(fingerprints_in_zone, 3))

    if placement.precision_tier is PrecisionTier.ZONE and not placement.supporting_fingerprint_ids:
        # A zone asserted from anchor visibility alone, with no fingerprint behind it.
        factors["calibration_density"] = min(factors["calibration_density"], 0.5)

    return {name: round(_clip(value), 4) for name, value in sorted(factors.items())}


def combine_factors(factors: Mapping[str, float], exponents: Mapping[str, float] = EXPONENTS) -> float:
    r"""The weighted product :math:`\prod_i f_i^{\alpha_i}`, clipped to [0, 1]."""
    total = 1.0
    for name, value in factors.items():
        exponent = exponents.get(name, 1.0)
        total *= max(_MIN_FACTOR, _clip(value)) ** exponent
    return round(_clip(total), 4)


_MIN_FACTOR = 1e-4


def _clip(value: float) -> float:
    if not math.isfinite(value):
        return 0.0
    return max(0.0, min(1.0, float(value)))
