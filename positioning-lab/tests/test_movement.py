"""Phases 9, 10 and 11: temporal filtering, hysteresis and topology.

The hysteresis machine exists to stop one noisy estimate from committing a zone change, so most of
these tests are about what does *not* happen. The remainder cover the two places where the design
deliberately refuses to be tidy: a device that really did jump must still be able to say so, and a
transition the site graph forbids is recorded rather than rewritten.
"""

from __future__ import annotations

from dataclasses import replace

import pytest

from rfmapper_lab.models import (
    MovementState,
    PositionEstimate,
    PrecisionTier,
    TopologyStatus,
    ZoneEdge,
    ZoneEventType,
)
from rfmapper_lab.movement import MovementEngineV1, smooth_track
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.timeutil import format_ms

BASE_MS = 1_789_286_400_000
ALGORITHM = "test-1.0.0"


def _estimate(
    index: int,
    zone_id: str | None,
    *,
    building_id: str | None = "B4",
    at_ms: int | None = None,
    confidence: float = 0.9,
    x: float | None = None,
    y: float | None = None,
    sigma: float | None = None,
    tier: PrecisionTier | None = None,
) -> PositionEstimate:
    timestamp = BASE_MS if at_ms is None else at_ms
    if tier is None:
        tier = PrecisionTier.APPROXIMATE_POSITION if x is not None else (
            PrecisionTier.ZONE if zone_id else PrecisionTier.BUILDING
        )
    return PositionEstimate(
        estimate_id=f"est-{index}",
        algorithm_version=ALGORITHM,
        device_id="DEVICE-PHONE",
        timestamp_utc=format_ms(timestamp),
        computed_at_utc=format_ms(BASE_MS),
        precision_tier=tier,
        confidence=confidence,
        method="pos_zone_only_v1" if x is None else "pos_wknn_centroid_v1",
        supporting_observer_ids=("OBS-00",),
        supporting_observation_ids=(f"obs-{index}",),
        building_id=building_id,
        zone_id=zone_id,
        x=x,
        y=y,
        horizontal_uncertainty_m=sigma if x is None else (sigma or 4.0),
    )


def _track(zones, *, cadence_ms: int = 20_000, confidence: float = 0.9):
    """One estimate per zone in sequence, at a fixed cadence."""
    return [
        _estimate(index, zone_id, at_ms=BASE_MS + index * cadence_ms, confidence=confidence)
        for index, zone_id in enumerate(zones)
    ]


@pytest.fixture()
def two_zone_site(model):
    """A copy of the simulated site whose first two zones are adjacent with a known walk time."""
    first, second = sorted(model.zones)[:2]
    sited = replace(model)
    sited.edges = (
        ZoneEdge(first, second, edge_type="ADJACENT", typical_traversal_s=30.0),
    )
    return sited, first, second


class TestZoneCommitment:
    def test_the_first_zone_is_an_entry_committed_immediately(self, two_zone_site):
        """Withholding it would leave the device nowhere while hysteresis accumulated, and there
        is no previous zone for it to oscillate against.
        """
        sited, first, _ = two_zone_site
        result = MovementEngineV1().process(
            "DEVICE-PHONE", _track([first]), sited, DEFAULTS, ALGORITHM
        )

        assert [t.event_type for t in result.transitions] == [ZoneEventType.RF_ZONE_ENTER]
        assert result.transitions[0].destination_zone_id == first
        assert result.transitions[0].origin_zone_id is None

    def test_one_estimate_can_never_commit_a_zone_change(self, two_zone_site):
        sited, first, second = two_zone_site
        track = _track([first, first, first, second, first, first])

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = [t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION]

        assert committed == []

    def test_a_persistent_candidate_does_commit(self, two_zone_site):
        """All three gates have to be satisfied: duration, confidence and supporting count."""
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 3 + [second] * 6, cadence_ms=dwell // 2)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = [t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION]

        assert len(committed) == 1
        assert committed[0].origin_zone_id == first
        assert committed[0].destination_zone_id == second
        assert committed[0].topology_status is TopologyStatus.ADJACENT

    def test_a_low_confidence_candidate_never_commits_however_long_it_persists(self, two_zone_site):
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        below = DEFAULTS.movement.min_transition_confidence - 0.2
        track = [
            *_track([first] * 3, cadence_ms=dwell),
            *[
                _estimate(
                    index + 3,
                    second,
                    at_ms=BASE_MS + (index + 3) * dwell,
                    confidence=below,
                )
                for index in range(8)
            ],
        ]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = [t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION]

        assert committed == []

    def test_a_committed_transition_cites_the_candidate_window_not_the_final_estimate(
        self, two_zone_site
    ):
        """Provenance has to point at the evidence that justified the commit, or the Master cannot
        trace the claim back to raw rows.
        """
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 2 + [second] * 6, cadence_ms=dwell // 2)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = next(
            t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION
        )

        assert len(committed.supporting_estimate_ids) >= DEFAULTS.movement.min_supporting_observations
        assert committed.transition_start_utc < committed.transition_confirmed_utc

    def test_the_transition_id_is_derived_so_a_rerun_reproduces_it(self, two_zone_site):
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 2 + [second] * 6, cadence_ms=dwell // 2)

        engine = MovementEngineV1()
        first_run = engine.process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        second_run = engine.process("DEVICE-PHONE", list(reversed(track)), sited, DEFAULTS, ALGORITHM)

        assert [t.transition_id for t in first_run.transitions] == [
            t.transition_id for t in second_run.transitions
        ]

    def test_a_candidate_decays_rather_than_resetting_on_every_confirming_estimate(
        self, two_zone_site
    ):
        """Zeroing the candidate whenever the current zone reappears would make a slow, noisy
        transition undetectable — exactly the case a walking device produces.
        """
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        step = dwell // 4
        # Alternating, with the candidate appearing twice as often as the incumbent.
        pattern = [first, second, second, first, second, second, second, second, second, second]
        track = _track(pattern, cadence_ms=step)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = [t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION]

        assert len(committed) == 1


class TestTopology:
    def test_a_forbidden_transition_is_recorded_rather_than_rewritten(self, model):
        """The site graph is a model, and overwhelming sensor evidence may mean the model is what
        is wrong — a new door, a relocated access point. Rewriting the measurement to fit the map
        would destroy the evidence that the map needs updating.
        """
        first, second = sorted(model.zones)[:2]
        sited = replace(model)
        sited.edges = (ZoneEdge(first, second, edge_type="IMPOSSIBLE"),)
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 2 + [second] * 6, cadence_ms=dwell // 2)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = next(
            t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION
        )

        assert committed.destination_zone_id == second
        assert committed.topology_status is TopologyStatus.NON_ADJACENT
        assert "TOPOLOGY_VIOLATION" in committed.quality_flags
        assert result.topology_violations == 1
        # The movement state says uncertain, which is the honest reading: something is wrong, and
        # the engine does not get to decide whether it is the device or the map.
        transition_states = [
            m.state for m in result.movements if m.state is MovementState.UNCERTAIN
        ]
        assert transition_states

    def test_an_unauthored_edge_is_not_a_contradiction(self, model):
        """A missing door is far more likely than a teleporting device, so the flag is advisory."""
        first, second = sorted(model.zones)[:2]
        sited = replace(model)
        sited.edges = ()
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 2 + [second] * 6, cadence_ms=dwell // 2)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = next(
            t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION
        )

        assert committed.topology_status is TopologyStatus.UNKNOWN_EDGE
        assert "UNKNOWN_TOPOLOGY_EDGE" in committed.quality_flags
        assert result.topology_violations == 0

    def test_a_traversal_faster_than_the_site_graph_allows_is_flagged(self, model):
        """Adjacency is not plausibility: a transition four seconds after the previous zone is
        implausible even between connected zones.
        """
        first, second = sorted(model.zones)[:2]
        sited = replace(model)
        sited.edges = (
            ZoneEdge(first, second, edge_type="ADJACENT", typical_traversal_s=600.0),
        )
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        track = _track([first] * 2 + [second] * 6, cadence_ms=dwell // 2)

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = next(
            t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION
        )

        assert "IMPLAUSIBLE_TRAVERSAL_TIME" in committed.quality_flags

    def test_oscillation_is_counted_rather_than_suppressed(self, model):
        """The count is a benchmark metric; the thresholds are what should be tuned to reduce it."""
        first, second = sorted(model.zones)[:2]
        sited = replace(model)
        sited.edges = (ZoneEdge(first, second, edge_type="ADJACENT"),)
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        # Long enough dwells that each flip clears hysteresis, inside the oscillation window.
        pattern = [first] * 4 + [second] * 4 + [first] * 4 + [second] * 4
        track = _track(pattern, cadence_ms=dwell)

        loose = replace(DEFAULTS, movement=replace(DEFAULTS.movement, oscillation_window_ms=10**9))
        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, loose, ALGORITHM)

        assert result.oscillations >= 1


class TestPresenceAndLoss:
    def test_a_zoneless_estimate_does_not_decay_a_candidate(self, two_zone_site):
        """A building-level claim carries no information about zone membership, so it must neither
        confirm the incumbent nor erode the candidate.
        """
        sited, first, second = two_zone_site
        dwell = DEFAULTS.movement.min_candidate_duration_ms
        step = dwell // 3
        pattern = [first, second, None, second, None, second, second]
        track = [
            _estimate(index, zone_id, at_ms=BASE_MS + index * step)
            for index, zone_id in enumerate(pattern)
        ]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        committed = [t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_TRANSITION]

        assert committed, "presence-only rows must not prevent a genuine transition"
        assert any(m.state is MovementState.UNCERTAIN for m in result.movements)

    def test_a_long_silence_is_a_loss_with_an_exit(self, two_zone_site):
        sited, first, _ = two_zone_site
        gap = DEFAULTS.movement.lost_after_ms * 2
        track = [
            _estimate(0, first, at_ms=BASE_MS),
            _estimate(1, first, at_ms=BASE_MS + gap),
        ]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)

        assert any(t.event_type is ZoneEventType.RF_ZONE_EXIT for t in result.transitions)
        assert any(m.state is MovementState.LOST for m in result.movements)
        exit_event = next(t for t in result.transitions if t.event_type is ZoneEventType.RF_ZONE_EXIT)
        assert "DEVICE_LOST" in exit_event.quality_flags

    def test_the_loss_is_timed_from_the_threshold_not_from_the_next_sighting(self, two_zone_site):
        """The device went missing when it stopped being seen, not when it came back."""
        sited, first, _ = two_zone_site
        gap = DEFAULTS.movement.lost_after_ms * 3
        track = [_estimate(0, first, at_ms=BASE_MS), _estimate(1, first, at_ms=BASE_MS + gap)]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        lost = next(m for m in result.movements if m.state is MovementState.LOST)

        assert lost.timestamp_utc == format_ms(BASE_MS + DEFAULTS.movement.lost_after_ms)

    def test_a_device_that_returns_is_marked_reappeared(self, two_zone_site):
        sited, first, _ = two_zone_site
        gap = DEFAULTS.movement.lost_after_ms * 2
        track = [_estimate(0, first, at_ms=BASE_MS), _estimate(1, first, at_ms=BASE_MS + gap)]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)

        assert any(m.state is MovementState.REAPPEARED for m in result.movements)

    def test_motion_without_coordinates_is_reported_as_stationary(self, two_zone_site):
        """Reading motion out of RSSI noise would be fabrication. The device stayed in the zone,
        and that is all the evidence supports.
        """
        sited, first, _ = two_zone_site
        result = MovementEngineV1().process(
            "DEVICE-PHONE", _track([first] * 4), sited, DEFAULTS, ALGORITHM
        )

        within_zone = [m for m in result.movements if m.confirmed_destination_zone_id == first]
        assert any(m.state is MovementState.STATIONARY for m in within_zone)
        assert all(m.state is not MovementState.MOVING for m in within_zone)

    def test_a_displacement_inside_the_error_bar_is_not_motion(self, two_zone_site):
        sited, first, _ = two_zone_site
        track = [
            _estimate(0, first, at_ms=BASE_MS, x=10.0, y=10.0, sigma=6.0),
            _estimate(1, first, at_ms=BASE_MS + 20_000, x=13.0, y=10.0, sigma=6.0),
        ]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        assert result.movements[-1].state is MovementState.STATIONARY

    def test_a_displacement_that_clears_the_error_bar_is_motion(self, two_zone_site):
        sited, first, _ = two_zone_site
        track = [
            _estimate(0, first, at_ms=BASE_MS, x=10.0, y=10.0, sigma=2.0),
            _estimate(1, first, at_ms=BASE_MS + 20_000, x=30.0, y=10.0, sigma=2.0),
        ]

        result = MovementEngineV1().process("DEVICE-PHONE", track, sited, DEFAULTS, ALGORITHM)
        assert result.movements[-1].state is MovementState.MOVING


class TestTemporalFilter:
    def test_a_single_wild_estimate_is_rejected_rather_than_averaged_in(self):
        """The reason for a median rather than a mean: one cached scan from an observer in the
        wrong building should not drag the track.
        """
        track = [
            _estimate(index, "B4-WEST", at_ms=BASE_MS + index * 10_000, x=10.0, y=10.0, sigma=3.0)
            for index in range(4)
        ]
        track.append(
            _estimate(4, "B4-WEST", at_ms=BASE_MS + 40_000, x=400.0, y=400.0, sigma=3.0)
        )

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert smoothed[-1].x == pytest.approx(10.0)
        assert smoothed[-1].y == pytest.approx(10.0)

    def test_smoothing_never_crosses_a_zone_boundary(self):
        """Averaging a position across a boundary would place a device in a doorway it was never
        observed in, which is the classic artefact of smoothing a categorical decision.
        """
        track = [
            *[
                _estimate(i, "B4-WEST", at_ms=BASE_MS + i * 10_000, x=5.0, y=5.0, sigma=3.0)
                for i in range(4)
            ],
            _estimate(4, "B4-EAST", at_ms=BASE_MS + 40_000, x=40.0, y=40.0, sigma=3.0),
        ]

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert smoothed[-1].zone_id == "B4-EAST"
        assert smoothed[-1].x == pytest.approx(40.0)

    def test_the_filter_widens_uncertainty_by_how_far_it_moved_the_point(self):
        """Smoothing across a scatter does not make the result more certain than its own inputs."""
        track = [
            _estimate(0, "B4-WEST", at_ms=BASE_MS, x=10.0, y=10.0, sigma=3.0),
            _estimate(1, "B4-WEST", at_ms=BASE_MS + 10_000, x=20.0, y=10.0, sigma=3.0),
        ]

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert smoothed[-1].x == pytest.approx(15.0)
        assert smoothed[-1].horizontal_uncertainty_m > 3.0
        assert "TEMPORAL_MEDIAN_FILTERED" in smoothed[-1].quality_flags

    def test_an_unmoved_point_is_not_flagged_as_filtered(self):
        """The flag means the coordinate was changed. Applying it regardless would make it useless
        for telling a smoothed row from a raw one.
        """
        track = [
            _estimate(index, "B4-WEST", at_ms=BASE_MS + index * 10_000, x=10.0, y=10.0, sigma=3.0)
            for index in range(3)
        ]

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert all("TEMPORAL_MEDIAN_FILTERED" not in e.quality_flags for e in smoothed)

    def test_a_zoneless_estimate_keeps_its_shape_through_the_filter(self):
        track = [
            _estimate(0, None, at_ms=BASE_MS),
            _estimate(1, None, at_ms=BASE_MS + 10_000),
        ]

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert all(e.x is None for e in smoothed)
        assert all(e.precision_tier is PrecisionTier.BUILDING for e in smoothed)

    def test_confidence_is_smoothed_but_stays_in_range(self):
        track = [
            _estimate(0, "B4-WEST", at_ms=BASE_MS, confidence=1.0),
            _estimate(1, "B4-WEST", at_ms=BASE_MS + 10_000, confidence=0.0),
            _estimate(2, "B4-WEST", at_ms=BASE_MS + 20_000, confidence=1.0),
        ]

        smoothed = smooth_track(track, DEFAULTS.movement)

        assert all(0.0 <= e.confidence <= 1.0 for e in smoothed)
        assert smoothed[1].confidence > 0.0, "one zero must not erase the track's confidence"

    def test_filtering_is_a_no_op_when_the_window_is_disabled(self):
        track = [
            _estimate(index, "B4-WEST", at_ms=BASE_MS + index * 10_000, x=float(index), y=0.0)
            for index in range(5)
        ]
        off = replace(DEFAULTS.movement, median_window=1)

        assert smooth_track(track, off) == tuple(track)
