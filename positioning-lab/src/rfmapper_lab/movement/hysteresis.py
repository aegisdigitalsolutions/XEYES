"""Phases 10 and 11 — the hysteresis state machine, movement states and topology.

This is the module that prevents B7 → B9 → B7 → B9 oscillation. A zone change is not committed
because one estimate said so; it is committed when a candidate zone has persisted for
:math:`T_{min}`, accumulated :math:`C_{min}` confidence, and been supported by at least
:math:`N_{min}` observations (``docs/10-positioning-mathematical-architecture.md`` §10). A single
noisy observation can never commit a transition.

The three thresholds trade transition-detection latency directly against false-transition rate.
They are placeholders here, and the benchmark sweeps them so an administrator can choose the
operating point rather than inherit a developer's guess.

Topology never silently overrides measurement. A committed transition between non-adjacent zones is
emitted as it happened, with ``NON_ADJACENT`` and a ``TOPOLOGY_VIOLATION`` flag, because the site
graph is a model and overwhelming sensor evidence may mean the model is what is wrong — a new door,
a relocated access point. Rewriting the measurement to fit the map would destroy the evidence that
the map needs updating.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Sequence

from ..jsonio import derive_id
from ..models import (
    MovementEstimate,
    MovementState,
    PositionEstimate,
    ReferenceModel,
    TopologyStatus,
    ZoneEventType,
    ZoneTransition,
)
from ..params import ParameterSet
from ..timeutil import format_ms, parse_ms
from ..version import MOVEMENT_ENGINE_VERSION, engine_versions


@dataclass(frozen=True, slots=True)
class TrackResult:
    transitions: tuple[ZoneTransition, ...]
    movements: tuple[MovementEstimate, ...]
    oscillations: int = 0
    topology_violations: int = 0


@dataclass
class _State:
    current_zone_id: str | None = None
    current_since_ms: int | None = None
    candidate_zone_id: str | None = None
    candidate_start_ms: int | None = None
    candidate_duration_ms: float = 0.0
    candidate_confidence: float = 0.0
    candidate_support: list[str] = field(default_factory=list)
    candidate_observers: set[str] = field(default_factory=set)
    last_ms: int | None = None
    last_x: float | None = None
    last_y: float | None = None
    lost: bool = False

    def reset_candidate(self) -> None:
        self.candidate_zone_id = None
        self.candidate_start_ms = None
        self.candidate_duration_ms = 0.0
        self.candidate_confidence = 0.0
        self.candidate_support = []
        self.candidate_observers = set()


class MovementEngineV1:
    """Hysteresis-gated zone commitment plus per-estimate movement state."""

    id = "movement_hysteresis_v1"
    version = MOVEMENT_ENGINE_VERSION

    def process(
        self,
        device_id: str,
        estimates: Sequence[PositionEstimate],
        model: ReferenceModel,
        params: ParameterSet,
        algorithm_version: str,
    ) -> TrackResult:
        movement = params.movement
        ordered = sorted(estimates, key=lambda e: (e.timestamp_utc, e.estimate_id))
        state = _State()
        transitions: list[ZoneTransition] = []
        movements: list[MovementEstimate] = []
        committed: list[tuple[int, str | None, str]] = []

        for estimate in ordered:
            now = parse_ms(estimate.timestamp_utc)
            gap = 0 if state.last_ms is None else now - state.last_ms

            if state.last_ms is not None and gap > movement.lost_after_ms:
                lost_at = state.last_ms + movement.lost_after_ms
                if state.current_zone_id is not None:
                    transitions.append(
                        self._exit(device_id, state, lost_at, algorithm_version)
                    )
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=lost_at,
                        state=MovementState.LOST,
                        algorithm_version=algorithm_version,
                        origin_zone_id=state.current_zone_id,
                        confidence=0.0,
                        supporting=(),
                    )
                )
                state.lost = True
                state.current_zone_id = None
                state.current_since_ms = None
                state.reset_candidate()

            zone_id = estimate.zone_id
            reappeared = state.lost
            state.lost = False

            if zone_id is None:
                # An estimate without a zone (site presence, or a building-level claim) carries no
                # information about zone membership. It must not decay a candidate, and it must not
                # be reported as if the device had stopped moving.
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=now,
                        state=MovementState.REAPPEARED if reappeared else MovementState.UNCERTAIN,
                        algorithm_version=algorithm_version,
                        origin_zone_id=state.current_zone_id,
                        confidence=estimate.confidence,
                        supporting=(estimate.estimate_id,),
                    )
                )
                state.last_ms = now
                continue

            if state.current_zone_id is None:
                # First zone seen, or the first after a loss: an entry, committed immediately.
                # Withholding it would leave the device nowhere while hysteresis accumulated, and
                # there is no previous zone for it to oscillate against.
                status = TopologyStatus.ADJACENT
                transitions.append(
                    ZoneTransition(
                        transition_id=derive_id("transition", algorithm_version, device_id, estimate.timestamp_utc, zone_id),
                        algorithm_version=algorithm_version,
                        engine_versions=engine_versions(),
                        device_id=device_id,
                        event_type=ZoneEventType.RF_ZONE_ENTER,
                        origin_zone_id=None,
                        destination_zone_id=zone_id,
                        transition_start_utc=estimate.timestamp_utc,
                        transition_confirmed_utc=estimate.timestamp_utc,
                        confidence=estimate.confidence,
                        topology_status=status,
                        supporting_observer_ids=tuple(estimate.supporting_observer_ids),
                        supporting_estimate_ids=(estimate.estimate_id,),
                        quality_flags=(),
                    )
                )
                state.current_zone_id = zone_id
                state.current_since_ms = now
                state.reset_candidate()
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=now,
                        state=MovementState.REAPPEARED if reappeared else MovementState.MOVING,
                        algorithm_version=algorithm_version,
                        confirmed_zone_id=zone_id,
                        confidence=estimate.confidence,
                        supporting=(estimate.estimate_id,),
                    )
                )
                state.last_ms, state.last_x, state.last_y = now, estimate.x, estimate.y
                continue

            if zone_id == state.current_zone_id:
                # Decay rather than reset: an intermittent candidate that keeps reappearing is
                # still weak evidence, and zeroing it on every confirming estimate would make a
                # slow transition undetectable.
                state.candidate_duration_ms = max(0.0, state.candidate_duration_ms - gap)
                if state.candidate_duration_ms == 0.0:
                    state.reset_candidate()
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=now,
                        state=self._stationary_or_moving(state, estimate, movement.stationary_radius_m),
                        algorithm_version=algorithm_version,
                        confirmed_zone_id=zone_id,
                        confidence=estimate.confidence,
                        supporting=(estimate.estimate_id,),
                    )
                )
                state.last_ms, state.last_x, state.last_y = now, estimate.x, estimate.y
                continue

            if zone_id == state.candidate_zone_id:
                state.candidate_duration_ms += gap
                state.candidate_confidence = max(state.candidate_confidence, estimate.confidence)
                state.candidate_support.append(estimate.estimate_id)
                state.candidate_observers.update(estimate.supporting_observer_ids)
            else:
                state.reset_candidate()
                state.candidate_zone_id = zone_id
                state.candidate_start_ms = now
                state.candidate_confidence = estimate.confidence
                state.candidate_support = [estimate.estimate_id]
                state.candidate_observers = set(estimate.supporting_observer_ids)

            ready = (
                state.candidate_duration_ms >= movement.min_candidate_duration_ms
                and state.candidate_confidence >= movement.min_transition_confidence
                and len(state.candidate_support) >= movement.min_supporting_observations
            )

            if ready:
                status = model.adjacency(state.current_zone_id, zone_id)
                flags: list[str] = []
                if status is TopologyStatus.NON_ADJACENT:
                    flags.append("TOPOLOGY_VIOLATION")
                if status is TopologyStatus.UNKNOWN_EDGE:
                    flags.append("UNKNOWN_TOPOLOGY_EDGE")
                if self._implausibly_fast(state, model, now):
                    flags.append("IMPLAUSIBLE_TRAVERSAL_TIME")

                start_ms = state.candidate_start_ms or now
                transitions.append(
                    ZoneTransition(
                        transition_id=derive_id(
                            "transition", algorithm_version, device_id, format_ms(start_ms), zone_id
                        ),
                        algorithm_version=algorithm_version,
                        engine_versions=engine_versions(),
                        device_id=device_id,
                        event_type=ZoneEventType.RF_ZONE_TRANSITION,
                        origin_zone_id=state.current_zone_id,
                        destination_zone_id=zone_id,
                        transition_start_utc=format_ms(start_ms),
                        transition_confirmed_utc=estimate.timestamp_utc,
                        confidence=round(min(1.0, state.candidate_confidence), 4),
                        topology_status=status,
                        supporting_observer_ids=tuple(sorted(state.candidate_observers)),
                        supporting_estimate_ids=tuple(state.candidate_support),
                        quality_flags=tuple(flags),
                    )
                )
                committed.append((now, state.current_zone_id, zone_id))
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=now,
                        state=(
                            MovementState.UNCERTAIN
                            if status is TopologyStatus.NON_ADJACENT
                            else MovementState.ZONE_TRANSITION
                        ),
                        algorithm_version=algorithm_version,
                        origin_zone_id=state.current_zone_id,
                        candidate_zone_id=zone_id,
                        confirmed_zone_id=zone_id,
                        direction=f"{state.current_zone_id}->{zone_id}",
                        confidence=round(min(1.0, state.candidate_confidence), 4),
                        supporting=tuple(state.candidate_support),
                        quality_flags=tuple(flags),
                    )
                )
                state.current_zone_id = zone_id
                state.current_since_ms = now
                state.reset_candidate()
            else:
                movements.append(
                    self._movement(
                        device_id=device_id,
                        timestamp_ms=now,
                        state=MovementState.ZONE_TRANSITION,
                        algorithm_version=algorithm_version,
                        origin_zone_id=state.current_zone_id,
                        candidate_zone_id=zone_id,
                        confidence=estimate.confidence,
                        supporting=(estimate.estimate_id,),
                    )
                )

            state.last_ms, state.last_x, state.last_y = now, estimate.x, estimate.y

        return TrackResult(
            transitions=tuple(transitions),
            movements=tuple(movements),
            oscillations=_count_oscillations(committed, params.movement.oscillation_window_ms),
            topology_violations=sum(
                1 for t in transitions if t.topology_status is TopologyStatus.NON_ADJACENT
            ),
        )

    # -- helpers ----------------------------------------------------------------------------------

    def _exit(
        self, device_id: str, state: _State, at_ms: int, algorithm_version: str
    ) -> ZoneTransition:
        return ZoneTransition(
            transition_id=derive_id("exit", algorithm_version, device_id, format_ms(at_ms)),
            algorithm_version=algorithm_version,
            engine_versions=engine_versions(),
            device_id=device_id,
            event_type=ZoneEventType.RF_ZONE_EXIT,
            origin_zone_id=state.current_zone_id,
            destination_zone_id=None,
            transition_start_utc=format_ms(state.last_ms or at_ms),
            transition_confirmed_utc=format_ms(at_ms),
            confidence=0.5,
            topology_status=TopologyStatus.UNKNOWN_EDGE,
            supporting_observer_ids=(),
            supporting_estimate_ids=tuple(state.candidate_support) or ("lost",),
            quality_flags=("DEVICE_LOST",),
        )

    def _movement(
        self,
        device_id: str,
        timestamp_ms: int,
        state: MovementState,
        algorithm_version: str,
        confidence: float,
        supporting: tuple[str, ...],
        origin_zone_id: str | None = None,
        candidate_zone_id: str | None = None,
        confirmed_zone_id: str | None = None,
        direction: str | None = None,
        quality_flags: tuple[str, ...] = (),
    ) -> MovementEstimate:
        return MovementEstimate(
            movement_id=derive_id(
                "movement", algorithm_version, device_id, format_ms(timestamp_ms), state.value
            ),
            algorithm_version=algorithm_version,
            engine_versions=engine_versions(),
            device_id=device_id,
            timestamp_utc=format_ms(timestamp_ms),
            state=state,
            origin_zone_id=origin_zone_id,
            candidate_destination_zone_id=candidate_zone_id,
            confirmed_destination_zone_id=confirmed_zone_id,
            direction=direction,
            confidence=round(max(0.0, min(1.0, confidence)), 4),
            supporting_estimate_ids=supporting,
            quality_flags=quality_flags,
        )

    def _stationary_or_moving(
        self, state: _State, estimate: PositionEstimate, radius_m: float
    ) -> MovementState:
        """Movement within a zone, judged on coordinates when there are any.

        Without coordinates the honest answer is ``STATIONARY``: the device stayed in the zone, and
        that is all the evidence supports. Reporting ``MOVING`` would be reading motion out of
        RSSI noise.
        """
        if estimate.x is None or state.last_x is None:
            return MovementState.STATIONARY
        moved = math.hypot(float(estimate.x) - state.last_x, float(estimate.y or 0) - (state.last_y or 0))
        # Only a displacement that clears the reported uncertainty counts as motion; anything
        # smaller is indistinguishable from the estimator jittering in place.
        threshold = max(radius_m, estimate.horizontal_uncertainty_m or 0.0)
        return MovementState.MOVING if moved > threshold else MovementState.STATIONARY

    def _implausibly_fast(self, state: _State, model: ReferenceModel, now: int) -> bool:
        """Whether the commit is faster than the site graph says the walk can be done.

        Adjacency alone is not plausibility: a B7 → B9 transition four seconds after a B7 estimate
        is implausible even though the zones are connected.
        """
        if state.current_zone_id is None or state.candidate_zone_id is None:
            return False
        for edge in model.edges:
            pair = {edge.from_zone_id, edge.to_zone_id}
            if pair != {state.current_zone_id, state.candidate_zone_id}:
                continue
            if edge.typical_traversal_s is None:
                return False
            elapsed_s = (now - (state.current_since_ms or now)) / 1000.0
            return elapsed_s < 0.5 * edge.typical_traversal_s
        return False


def _count_oscillations(
    committed: Sequence[tuple[int, str | None, str]], window_ms: int
) -> int:
    """A → B → A commits inside the oscillation window.

    Counted rather than suppressed: the count is a benchmark metric, and the hysteresis thresholds
    are what should be tuned to reduce it.
    """
    count = 0
    for index in range(2, len(committed)):
        first, second, third = committed[index - 2], committed[index - 1], committed[index]
        if third[2] == second[1] == first[2] and third[0] - first[0] <= window_ms:
            count += 1
    return count
