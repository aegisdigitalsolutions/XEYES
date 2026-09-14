"""The versioned parameter set.

Every tunable number in the pipeline lives here and nowhere else, and the whole set is hashed into
each derived package. Two consequences: a result can be reproduced exactly, and a quiet change to a
threshold cannot masquerade as the same algorithm version
(``docs/14-algorithm-versioning-strategy.md``).

The defaults marked *placeholder* are explicitly unvalidated. They are starting points to be tuned
against walk-test ground truth, and the assumptions they encode are listed in
``docs/15-assumptions-requiring-validation.md``.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field


@dataclass(frozen=True, slots=True)
class FusionParams:
    """Window widths and the weights that combine observers.

    Per-sensor windows because the cadences differ by an order of magnitude: a Wi-Fi scan arrives
    every few seconds, a BLE advertisement many times a second. One window for both would either
    discard most Wi-Fi evidence or fuse BLE readings that are not simultaneous.
    """

    window_ms_wifi: int = 10_000
    window_ms_ble: int = 2_000
    window_ms_rtt: int = 5_000
    window_ms_default: int = 5_000

    #: Exponential decay half-life for measurement age within a window.
    freshness_half_life_ms: int = 15_000

    #: Hard multiplier for a platform-cached scan result. A 30-minute-old cached scan must not
    #: weigh as much as a fresh one, and the platform tells us which it is.
    cached_weight: float = 0.35
    unknown_freshness_weight: float = 0.7

    #: Applied when an observer has no measured calibration offset.
    uncalibrated_weight: float = 0.8

    sensor_weights: dict[str, float] = field(
        default_factory=lambda: {
            "RTT": 1.0,
            "BLE": 0.85,
            "WIFI_SCAN": 0.8,
            "WIFI_ASSOCIATION": 0.5,
            "ZONE_ANCHOR": 0.4,
            "GPS": 0.3,
            "MANUAL": 0.2,
            "IMPORT": 0.2,
        }
    )

    #: sqrt(n) in independent samples, capped so one chatty observer cannot dominate a fusion.
    count_weight_cap: float = 2.0

    #: Beyond this per-source spread an offset is antenna-pattern difference, not a scalar offset.
    max_calibration_spread_db: float = 6.0


@dataclass(frozen=True, slots=True)
class FingerprintParams:
    #: Below this, a fingerprint is too thin to be a distribution rather than an anecdote.
    min_samples_per_point: int = 20
    min_samples_per_source: int = 3

    #: Repeat visits on separate occasions. One afternoon's RF conditions are not a fingerprint.
    recommended_sessions: int = 3

    #: Sources seen in fewer than this fraction of samples are kept but weakly weighted; they are
    #: not dropped, because a rarely-seen source is still evidence when it does appear.
    min_visibility: float = 0.05


@dataclass(frozen=True, slots=True)
class ZoneParams:
    k_neighbours: int = 5

    #: Penalty per unit of visibility probability for a source a location reliably sees but the
    #: live vector does not. This term is what naive implementations omit, and it is the reason a
    #: missing AP counts as evidence against a location rather than as no information.
    mismatch_penalty: float = 6.0

    #: Floor on the absent-source likelihood, so one unexpected absence cannot zero out an
    #: otherwise strong Bayesian match.
    absent_probability_floor: float = 0.02

    #: Floor on a fingerprint's per-source sigma. A surveyed sigma of 0 would make the Gaussian a
    #: delta function and one dB of drift would annihilate the match.
    min_sigma_db: float = 2.0

    min_shared_sources: int = 2


@dataclass(frozen=True, slots=True)
class PositioningParams:
    #: Number of fingerprints averaged by the weighted-centroid method.
    centroid_k: int = 3

    #: Minimum located anchors for multilateration. Two ranges leave a mirror ambiguity.
    min_rtt_anchors: int = 3

    #: Above this Jacobian condition number the anchors are effectively collinear: the
    #: along-baseline direction is unconstrained, and the solver declines rather than returning a
    #: confident wrong answer.
    max_geometry_condition: float = 25.0

    #: Ranged results below this residual are trusted; above it the solution is demoted.
    max_rtt_residual_m: float = 8.0

    #: Empirical floor on RTT accuracy. Chipset bias is real and a covariance-derived sigma that
    #: ignores it would be optimistic.
    rtt_bias_floor_m: float = 1.5

    #: RSSI-to-distance conversion is disabled. Free-space propagation indoors is not assumed
    #: anywhere in this system; the model exists only as optional weak supporting evidence.
    enable_rssi_ranging: bool = False
    path_loss_exponent: float = 3.0
    reference_rssi_1m: float = -40.0


@dataclass(frozen=True, slots=True)
class UncertaintyParams:
    #: Reported when no benchmark has yet measured this method's real error. Paired with an
    #: UNVALIDATED_UNCERTAINTY flag, never silently.
    default_empirical_p68_m: float = 8.0
    min_uncertainty_m: float = 1.0
    max_uncertainty_m: float = 60.0


@dataclass(frozen=True, slots=True)
class MovementParams:
    """Hysteresis thresholds. Placeholders, to be tuned against walk-test ground truth.

    These three numbers trade transition-detection latency directly against false-transition rate.
    The benchmark sweeps them and reports the curve so the operating point is chosen deliberately
    rather than inherited from a developer's guess.
    """

    min_candidate_duration_ms: int = 45_000
    min_transition_confidence: float = 0.6
    min_supporting_observations: int = 3

    lost_after_ms: int = 300_000
    stationary_radius_m: float = 3.0
    oscillation_window_ms: int = 120_000

    #: Rolling median window for coordinates and EMA factor for confidence. Baselines first:
    #: Kalman and HMM remain unimplemented until the benchmark shows these are the binding
    #: constraint.
    median_window: int = 5
    confidence_ema_alpha: float = 0.4


@dataclass(frozen=True, slots=True)
class QualityParams:
    #: A source this visible in ground truth going absent all day is a real change, not noise.
    ap_disappeared_visibility: float = 0.9
    ap_relocated_shift_db: float = 12.0
    interference_variance_ratio: float = 2.0
    observer_offset_db: float = 5.0
    clock_jump_ms: int = 2_000
    min_fingerprints_per_zone: int = 1


@dataclass(frozen=True, slots=True)
class ParameterSet:
    fusion: FusionParams = field(default_factory=FusionParams)
    fingerprint: FingerprintParams = field(default_factory=FingerprintParams)
    zone: ZoneParams = field(default_factory=ZoneParams)
    positioning: PositioningParams = field(default_factory=PositioningParams)
    uncertainty: UncertaintyParams = field(default_factory=UncertaintyParams)
    movement: MovementParams = field(default_factory=MovementParams)
    quality: QualityParams = field(default_factory=QualityParams)

    #: Fixed so a re-run reproduces byte-identical output.
    random_seed: int = 20260914

    def to_dict(self) -> dict:
        return asdict(self)

    def sha256(self) -> str:
        """Content hash over the whole set, with sorted keys so it is stable across runs."""
        payload = json.dumps(self.to_dict(), sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    @classmethod
    def from_dict(cls, data: dict) -> "ParameterSet":
        def build(kind, key):
            return kind(**data.get(key, {}))

        return cls(
            fusion=build(FusionParams, "fusion"),
            fingerprint=build(FingerprintParams, "fingerprint"),
            zone=build(ZoneParams, "zone"),
            positioning=build(PositioningParams, "positioning"),
            uncertainty=build(UncertaintyParams, "uncertainty"),
            movement=build(MovementParams, "movement"),
            quality=build(QualityParams, "quality"),
            random_seed=data.get("random_seed", 20260914),
        )


DEFAULTS = ParameterSet()
