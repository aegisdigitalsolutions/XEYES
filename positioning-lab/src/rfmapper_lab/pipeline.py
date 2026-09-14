"""The daily pipeline: RAW + REFERENCE in, DERIVED out.

Every phase is a pure function of its inputs plus the versioned parameter set, and no phase mutates
RAW (``docs/10-positioning-mathematical-architecture.md`` §1). Re-running over the same inputs with
the same versions produces byte-identical output, which the determinism test enforces.

Two boundaries are deliberate and worth stating.

*Survey samples are not inference inputs.* Phases 3 and 4 consume ``sample_kind=GROUND_TRUTH``
rows; Phases 5 to 9 consume the ordinary ones. Feeding a survey capture back through the classifier
that was built from it would let a fingerprint validate itself, and the resulting accuracy figures
would be meaningless in a way that is easy to mistake for success.

*The hierarchy stops where the evidence stops.* The engine emits the deepest tier the evidence
supports — site presence, building, zone, approximate position, ranged position — and goes no
further. Null coordinates with a confident zone is a normal, preferred outcome.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Sequence

from .fingerprint import FingerprintSet, build_fingerprints
from .fusion import build_vectors, frame_weights, fuse_zones
from .jsonio import derive_id
from .models import (
    DatasetKind,
    LiveVector,
    MovementEstimate,
    Placement,
    PositionEstimate,
    PrecisionTier,
    QualityFlag,
    ReferenceModel,
    ZoneResult,
    ZoneTransition,
)
from .movement import MovementEngineV1, smooth_track
from .params import DEFAULTS, ParameterSet
from .parsing.dataset import Dataset
from .positioning import (
    EmpiricalErrorModel,
    STRATEGY_ORDER,
    combine_factors,
    confidence_factors,
    place,
    resolve_uncertainty,
)
from .quality import QualityReport, build_quality_report
from .registry import ZONE_CLASSIFIERS
from .timeutil import format_ms
from .version import LAB_VERSION, PIPELINE_VERSION, engine_versions

#: Prior weight for a zone given the device's previously estimated zone. Continuity as a nudge, not
#: as a constraint: a device that really did jump must still be able to say so, so no zone is ever
#: given zero prior.
PRIOR_SAME = 1.0
PRIOR_ADJACENT = 0.6
PRIOR_RESTRICTED = 0.3
PRIOR_UNKNOWN = 0.2
PRIOR_NON_ADJACENT = 0.05


@dataclass(frozen=True, slots=True)
class PipelineConfig:
    zone_engine: str = "zone_bayes_v1"
    strategies: tuple[str, ...] = STRATEGY_ORDER
    algorithm_version: str = PIPELINE_VERSION
    params: ParameterSet = DEFAULTS
    empirical: EmpiricalErrorModel = field(default_factory=EmpiricalErrorModel.unvalidated)
    dataset_kind: DatasetKind = DatasetKind.REAL
    enable_temporal_filter: bool = True
    computed_at_ms: int = 0

    def with_engine(self, zone_engine: str) -> "PipelineConfig":
        return PipelineConfig(
            zone_engine=zone_engine,
            strategies=self.strategies,
            algorithm_version=self.algorithm_version,
            params=self.params,
            empirical=self.empirical,
            dataset_kind=self.dataset_kind,
            enable_temporal_filter=self.enable_temporal_filter,
            computed_at_ms=self.computed_at_ms,
        )


@dataclass(frozen=True, slots=True)
class PipelineResult:
    estimates: tuple[PositionEstimate, ...]
    transitions: tuple[ZoneTransition, ...]
    movements: tuple[MovementEstimate, ...]
    quality: QualityReport
    fingerprints: FingerprintSet
    config: PipelineConfig
    dataset: Dataset
    oscillations: int = 0
    topology_violations: int = 0

    @property
    def flags(self) -> tuple[QualityFlag, ...]:
        return self.quality.flags

    def counts(self) -> dict[str, int]:
        return {
            "position_estimates": len(self.estimates),
            "zone_transitions": len(self.transitions),
            "movement_estimates": len(self.movements),
            "quality_flags": len(self.flags),
        }

    def tier_breakdown(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for estimate in self.estimates:
            counts[estimate.precision_tier.value] = counts.get(estimate.precision_tier.value, 0) + 1
        return dict(sorted(counts.items()))

    def method_breakdown(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for estimate in self.estimates:
            counts[estimate.method] = counts.get(estimate.method, 0) + 1
        return dict(sorted(counts.items()))


def run_pipeline(dataset: Dataset, config: PipelineConfig = PipelineConfig()) -> PipelineResult:
    """Run every phase over one dataset."""
    params = config.params
    model = dataset.reference
    computed_at_utc = format_ms(config.computed_at_ms)

    fingerprints = build_fingerprints(dataset.observations, model, params)
    quality = build_quality_report(
        dataset=dataset,
        fingerprints=fingerprints,
        params=params,
        generated_at_ms=config.computed_at_ms,
        dataset_kind=config.dataset_kind,
    )

    classifier = ZONE_CLASSIFIERS.get(config.zone_engine)
    vectors = build_vectors(dataset.ordinary(), model, params)
    per_zone_counts = {zone: len(points) for zone, points in fingerprints.by_zone().items()}

    by_device: dict[str, list[LiveVector]] = {}
    for vector in vectors:
        by_device.setdefault(vector.device_id, []).append(vector)

    estimates: list[PositionEstimate] = []
    transitions: list[ZoneTransition] = []
    movements: list[MovementEstimate] = []
    oscillations = 0
    violations = 0
    movement_engine = MovementEngineV1()

    for device_id in sorted(by_device):
        track: list[PositionEstimate] = []
        previous_zone: str | None = None

        for vector in sorted(by_device[device_id], key=lambda v: v.timestamp_ms):
            estimate = estimate_for_vector(
                vector=vector,
                classifier=classifier,
                fingerprints=fingerprints,
                model=model,
                config=config,
                computed_at_utc=computed_at_utc,
                source_dataset_ids=dataset.source_dataset_ids,
                previous_zone_id=previous_zone,
                per_zone_counts=per_zone_counts,
            )
            if estimate is None:
                continue
            track.append(estimate)
            previous_zone = estimate.zone_id or previous_zone

        if config.enable_temporal_filter:
            track = list(smooth_track(track, params.movement))

        result = movement_engine.process(
            device_id=device_id,
            estimates=track,
            model=model,
            params=params,
            algorithm_version=config.algorithm_version,
        )
        estimates.extend(track)
        transitions.extend(result.transitions)
        movements.extend(result.movements)
        oscillations += result.oscillations
        violations += result.topology_violations

    return PipelineResult(
        estimates=tuple(sorted(estimates, key=lambda e: (e.timestamp_utc, e.estimate_id))),
        transitions=tuple(
            sorted(transitions, key=lambda t: (t.transition_confirmed_utc, t.transition_id))
        ),
        movements=tuple(sorted(movements, key=lambda m: (m.timestamp_utc, m.movement_id))),
        quality=quality,
        fingerprints=fingerprints,
        config=config,
        dataset=dataset,
        oscillations=oscillations,
        topology_violations=violations,
    )


@dataclass(frozen=True, slots=True)
class Inference:
    """One live vector's full inference, not only the row that leaves the building.

    The benchmark needs the ranked zone list to measure top-2 accuracy and the placement to
    attribute a method, and neither survives onto :class:`PositionEstimate`. Returning them here
    keeps the harness measuring the pipeline rather than a reimplementation of it.
    """

    estimate: PositionEstimate | None
    zones: ZoneResult
    placement: Placement | None


def estimate_for_vector(
    vector: LiveVector,
    classifier,
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    config: PipelineConfig,
    computed_at_utc: str,
    source_dataset_ids: Sequence[str],
    previous_zone_id: str | None,
    per_zone_counts: Mapping[str, int],
) -> PositionEstimate | None:
    return infer(
        vector=vector,
        classifier=classifier,
        fingerprints=fingerprints,
        model=model,
        config=config,
        computed_at_utc=computed_at_utc,
        source_dataset_ids=source_dataset_ids,
        previous_zone_id=previous_zone_id,
        per_zone_counts=per_zone_counts,
    ).estimate


def infer(
    vector: LiveVector,
    classifier,
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    config: PipelineConfig,
    computed_at_utc: str,
    source_dataset_ids: Sequence[str],
    previous_zone_id: str | None,
    per_zone_counts: Mapping[str, int],
) -> Inference:
    """Phases 5 to 8 for one live vector.

    Exposed separately because the benchmark harness evaluates exactly this function against
    held-out survey samples. If the harness reimplemented the assembly, it would be measuring its
    own copy of the pipeline rather than the pipeline.
    """
    params = config.params

    zones = fuse_zones(
        vector=vector,
        classifier=classifier,
        fingerprints=fingerprints.points,
        model=model,
        params=params,
        prior=_prior(previous_zone_id, model),
    )
    placement = place(vector, zones, fingerprints.points, model, params, config.strategies)
    if placement is None:
        placement = _presence_only(vector, model)
        if placement is None:
            return Inference(estimate=None, zones=zones, placement=None)

    best = zones.best
    building_id, zone_id = _location_ids(placement, best, model, vector)

    uncertainty, uncertainty_flags = resolve_uncertainty(
        method=placement.method,
        sigma_geometric_m=placement.sigma_geometric_m,
        empirical=config.empirical,
        params=params.uncertainty,
    )

    factors = confidence_factors(
        vector=vector,
        zones=zones,
        placement=placement,
        model=model,
        params=params,
        frame_weights=frame_weights(vector, model, params),
        previous_zone_id=previous_zone_id,
        fingerprints_in_zone=per_zone_counts.get(zone_id or "", 0),
    )
    confidence = combine_factors(factors)

    flags = list(placement.quality_flags) + list(uncertainty_flags)
    if any(
        not fingerprints.is_promoted(fingerprint_id)
        for fingerprint_id in placement.supporting_fingerprint_ids
    ):
        # Honest and provisional beats silent: the estimate rests on a fingerprint no administrator
        # has approved, and the operator should be able to see that on the record itself.
        flags.append("CANDIDATE_FINGERPRINT")
    if len(vector.observer_ids) == 1:
        flags.append("SINGLE_OBSERVER")
    if config.dataset_kind is not DatasetKind.REAL:
        flags.append(f"DATASET_{config.dataset_kind.value}")

    tier = placement.precision_tier
    x, y = placement.x, placement.y
    if x is not None and uncertainty is None:
        # Cannot happen by construction, and the check stays anyway: the one outcome this system
        # must never produce is a coordinate without an error bar, so it is demoted to its zone
        # rather than emitted bare.
        tier = PrecisionTier.ZONE if zone_id else PrecisionTier.BUILDING
        x = y = None
        flags.append("UNCERTAINTY_UNAVAILABLE")

    if tier is PrecisionTier.PRECISION_RANGE and not vector.has_rtt:
        # Invariant 4 of the derived schema: tier 4 is unreachable without ranging evidence.
        tier = PrecisionTier.APPROXIMATE_POSITION
        flags.append("RANGED_CLAIM_WITHOUT_RTT")

    estimate = PositionEstimate(
        estimate_id=derive_id(
            "estimate", config.algorithm_version, vector.device_id, vector.timestamp_utc
        ),
        algorithm_version=config.algorithm_version,
        engine_versions=engine_versions() | {"zone_engine_id": classifier.id},
        parameter_set_sha256=params.sha256(),
        device_id=vector.device_id,
        timestamp_utc=vector.timestamp_utc,
        computed_at_utc=computed_at_utc,
        precision_tier=tier,
        building_id=building_id,
        zone_id=zone_id if tier.depth >= PrecisionTier.ZONE.depth else None,
        x=round(x, 3) if x is not None else None,
        y=round(y, 3) if y is not None else None,
        horizontal_uncertainty_m=uncertainty,
        confidence=confidence,
        confidence_factors=factors,
        method=placement.method,
        supporting_observer_ids=vector.observer_ids,
        supporting_observation_ids=placement.supporting_observation_ids or vector.observation_ids,
        source_dataset_ids=tuple(source_dataset_ids),
        reference_model_id=model.reference_model_id,
        calibration_set_id=model.calibration_set_id,
        quality_flags=tuple(sorted(set(flags))),
    )
    return Inference(estimate=estimate, zones=zones, placement=placement)


def _location_ids(
    placement: Placement,
    best,
    model: ReferenceModel,
    vector: LiveVector,
) -> tuple[str | None, str | None]:
    if best is not None:
        return best.building_id, best.zone_id
    building = _observer_building(vector, model)
    return building, None


def _presence_only(vector: LiveVector, model: ReferenceModel) -> Placement | None:
    """Tiers 1 and 2, for a device seen by an observer but not placeable in a zone.

    Still worth emitting. "This device was on site, in building 7, at 08:19" is a useful and
    defensible claim, and suppressing it because no fingerprint matched would discard the only
    evidence there is.
    """
    if not vector.measurements:
        return None
    building = _observer_building(vector, model)
    return Placement(
        method="pos_presence_v1",
        precision_tier=PrecisionTier.BUILDING if building else PrecisionTier.SITE_PRESENCE,
        supporting_observation_ids=vector.observation_ids,
        quality_flags=("NO_ZONE_EVIDENCE",),
    )


def _observer_building(vector: LiveVector, model: ReferenceModel) -> str | None:
    """The building an observer declares, when they all agree.

    Disagreement is left unresolved rather than voted on: two observers in different buildings
    seeing the same device means something interesting, and a majority vote would bury it.
    """
    buildings = {
        model.observers[measurement.observer_id].building_id
        for measurement in vector.measurements
        if measurement.observer_id in model.observers
        and model.observers[measurement.observer_id].building_id
    }
    return next(iter(buildings)) if len(buildings) == 1 else None


def _prior(previous_zone_id: str | None, model: ReferenceModel) -> dict[str, float] | None:
    """Continuity and topology as a prior over zones."""
    if previous_zone_id is None:
        return None
    prior: dict[str, float] = {}
    for zone_id in model.zones:
        if zone_id == previous_zone_id:
            prior[zone_id] = PRIOR_SAME
            continue
        status = model.adjacency(previous_zone_id, zone_id).value
        prior[zone_id] = {
            "ADJACENT": PRIOR_ADJACENT,
            "RESTRICTED": PRIOR_RESTRICTED,
            "UNKNOWN_EDGE": PRIOR_UNKNOWN,
            "NON_ADJACENT": PRIOR_NON_ADJACENT,
        }.get(status, PRIOR_UNKNOWN)
    return prior


def generator_block() -> dict[str, str]:
    return {"name": "rfmapper_lab", "version": LAB_VERSION}
