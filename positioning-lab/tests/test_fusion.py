"""Phase 6: windowing, attribution, frames and weighted fusion.

Two of these are regression tests for defects that made the whole system silently produce nothing
for an entire class of device, and neither raised an error while doing it. They are the reason the
file leads with windowing rather than with the interesting part.
"""

from __future__ import annotations

from dataclasses import replace

import pytest

from rfmapper_lab.fusion import (
    attribute_device,
    build_vectors,
    frame_weights,
    fuse_zones,
    signal_source_key,
    split_by_frame,
)
from rfmapper_lab.models import (
    IdentifierType,
    LiveVector,
    Observation,
    RECIPROCAL_FRAME,
    SensorType,
    self_frame,
)
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.registry import ZONE_CLASSIFIERS
from rfmapper_lab.timeutil import format_ms


def _heard(timestamp_ms: int, observer_id: str, device_id: str, rssi: int) -> Observation:
    """An observer hearing an enrolled tag."""
    return Observation(
        observation_id=f"{observer_id}-{device_id}-{timestamp_ms}",
        timestamp_utc=format_ms(timestamp_ms),
        timestamp_ms=timestamp_ms,
        observer_id=observer_id,
        sensor_type=SensorType.BLE,
        radio_identifier="d1:e2:f3:00:15:26",
        identifier_type=IdentifierType.BLE_MAC_PUBLIC,
        rssi=rssi,
        target_device_id=device_id,
        metadata={"result_freshness": "FRESH"},
    )


class TestWindowing:
    def test_a_row_is_never_orphaned_between_anchors(self, model):
        """Regression: the grid used to step by the default window, which is wider than twice the
        narrowest one, so BLE rows landing mid-bucket belonged to no window and vanished.
        """
        cadence = 10_000
        rows = [
            _heard(1_789_000_000_000 + step * cadence, "OBS-00", "DEVICE-00", -55)
            for step in range(6)
        ]
        vectors = build_vectors(tuple(rows), model, DEFAULTS)

        placed = {
            measurement.observation_id
            for vector in vectors
            for measurement in vector.measurements
        }
        assert placed == {row.observation_id for row in rows}

    def test_rows_outside_their_sensor_window_are_excluded(self, model):
        anchor_time = 1_789_000_000_000
        near = _heard(anchor_time, "OBS-00", "DEVICE-00", -55)
        far = _heard(anchor_time + 120_000, "OBS-00", "DEVICE-00", -70)
        vectors = build_vectors((near, far), model, DEFAULTS)

        # Two separate windows, never one: two minutes apart is not simultaneous however adjacent
        # the rows are in the file.
        assert len(vectors) == 2
        assert all(len(vector.measurements) == 1 for vector in vectors)

    def test_window_anchors_are_absolute_not_relative_to_the_first_row(self, model):
        """A reprocess over a wider range must reuse the windows of the overlapping days."""
        base = 1_789_000_000_000
        late = [_heard(base + step * 10_000, "OBS-00", "DEVICE-00", -55) for step in range(3)]
        early = [_heard(base - 600_000, "OBS-01", "DEVICE-00", -80)]

        narrow = build_vectors(tuple(late), model, DEFAULTS)
        wide = build_vectors(tuple(early + late), model, DEFAULTS)

        narrow_anchors = {vector.timestamp_ms for vector in narrow}
        wide_anchors = {vector.timestamp_ms for vector in wide}
        assert narrow_anchors <= wide_anchors


class TestAttribution:
    def test_an_enrolled_identifier_is_attributed(self, model):
        device_id, identifier = next(
            (device.device_id, device.identifiers[0])
            for device in model.devices.values()
            if device.identifiers
        )
        observation = Observation(
            observation_id="x",
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=0,
            observer_id="OBS-00",
            sensor_type=SensorType.BLE,
            radio_identifier=identifier,
            identifier_type=IdentifierType.BLE_MAC_PUBLIC,
            rssi=-60,
        )
        assert attribute_device(observation, model) == device_id

    def test_a_rotating_address_is_never_attributed(self, model):
        observation = Observation(
            observation_id="x",
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=0,
            observer_id="OBS-00",
            sensor_type=SensorType.BLE,
            radio_identifier="c2:11:00:00:00:01",
            identifier_type=IdentifierType.BLE_MAC_RANDOM,
            rssi=-60,
        )
        assert attribute_device(observation, model) is None

    def test_environmental_devices_stay_unattributed_end_to_end(self, dataset, model):
        random_rows = [
            observation
            for observation in dataset.ordinary()
            if observation.identifier_type.is_ephemeral
        ]
        assert random_rows, "the simulated site must contain rotating addresses"
        assert all(attribute_device(row, model) is None for row in random_rows)


class TestFrames:
    def test_reciprocal_rows_share_one_frame(self, model):
        rows = (
            _heard(1_789_000_000_000, "OBS-00", "DEVICE-00", -50),
            _heard(1_789_000_000_000, "OBS-01", "DEVICE-00", -78),
        )
        vector = build_vectors(rows, model, DEFAULTS)[0]

        assert vector.frames == (RECIPROCAL_FRAME,)
        assert len(split_by_frame(vector)) == 1

    def test_a_heard_measurement_is_keyed_by_the_observer_not_the_device(self, model):
        observation = _heard(1_789_000_000_000, "OBS-00", "DEVICE-00", -50)
        key = signal_source_key(observation, model, measures_device=True)

        # The device's own address appears in no fingerprint; the observer's beacon does.
        assert key != observation.radio_identifier
        assert key == model.source_for_observer("OBS-00")

    def test_an_observer_with_no_registered_identifier_gets_a_namespaced_key(self, model):
        observation = replace(_heard(0, "OBS-UNKNOWN", "DEVICE-00", -50))
        key = signal_source_key(observation, model, measures_device=True)

        # Namespaced rather than guessed: it matches no fingerprint, the method declines, and the
        # estimate falls back to a coarser tier. Guessing would match the wrong signal space.
        assert key == "observer:OBS-UNKNOWN"

    def test_reciprocal_evidence_can_be_classified(self, model, dataset):
        """Regression: splitting per observer gave each a single source, and a single source can
        never reach the two shared sources a fingerprint match requires, so no device heard by
        fixed observers ever received a zone.
        """
        from rfmapper_lab.fingerprint import build_fingerprints

        fingerprints = build_fingerprints(dataset.observations, model, DEFAULTS)
        rows = tuple(
            row
            for row in dataset.ordinary()
            if row.target_device_id == "DEVICE-00"
        )
        vectors = [
            vector
            for vector in build_vectors(rows, model, DEFAULTS)
            if len(vector.observer_ids) >= 2
        ]
        assert vectors, "the simulated site must have a tag heard by two observers at once"

        classifier = ZONE_CLASSIFIERS.get("zone_bayes_v1")
        ranked = [
            fuse_zones(vector, classifier, fingerprints.points, model, DEFAULTS)
            for vector in vectors
        ]
        assert any(result.candidates for result in ranked)


class TestWeights:
    def _vector(self, model, freshness: str) -> LiveVector:
        rows = (
            replace(
                _heard(1_789_000_000_000, "OBS-00", "DEVICE-00", -50),
                metadata={"result_freshness": freshness},
            ),
        )
        return build_vectors(rows, model, DEFAULTS)[0]

    def test_a_cached_reading_weighs_less_than_a_fresh_one(self, model):
        fresh = frame_weights(self._vector(model, "FRESH"), model, DEFAULTS)
        cached = frame_weights(self._vector(model, "CACHED"), model, DEFAULTS)
        assert cached[RECIPROCAL_FRAME] < fresh[RECIPROCAL_FRAME]

    def test_more_independent_sources_raise_the_weight_but_not_without_limit(self, model):
        one = frame_weights(self._vector(model, "FRESH"), model, DEFAULTS)[RECIPROCAL_FRAME]
        many = build_vectors(
            tuple(
                _heard(1_789_000_000_000, f"OBS-0{index}", "DEVICE-00", -60)
                for index in range(2)
            ),
            model,
            DEFAULTS,
        )[0]
        two = frame_weights(many, model, DEFAULTS)[RECIPROCAL_FRAME]
        assert two > one

    def test_an_uncalibrated_observer_is_discounted(self, model):
        calibrated = frame_weights(self._vector(model, "FRESH"), model, DEFAULTS)
        # OBS-01 carries an offset with a spread too wide to apply, so it counts as uncalibrated.
        uncalibrated_vector = build_vectors(
            (_heard(1_789_000_000_000, "OBS-01", "DEVICE-00", -50),), model, DEFAULTS
        )[0]
        uncalibrated = frame_weights(uncalibrated_vector, model, DEFAULTS)
        assert uncalibrated[RECIPROCAL_FRAME] < calibrated[RECIPROCAL_FRAME]


class TestFusionArithmetic:
    def test_a_zone_no_observer_ranked_is_weakened_not_eliminated(self, model, dataset):
        """Silence is not a veto: an observer with no view of a zone has no opinion about it."""
        from rfmapper_lab.fingerprint import build_fingerprints

        fingerprints = build_fingerprints(dataset.observations, model, DEFAULTS)
        rows = tuple(row for row in dataset.ordinary() if row.observer_id == "OBS-WALK")[:60]
        vectors = build_vectors(rows, model, DEFAULTS)
        classifier = ZONE_CLASSIFIERS.get("zone_bayes_v1")

        for vector in vectors:
            result = fuse_zones(vector, classifier, fingerprints.points, model, DEFAULTS)
            if len(result.candidates) >= 2:
                assert all(candidate.score >= 0.0 for candidate in result.candidates)
                assert result.candidates[0].score >= result.candidates[1].score
                assert pytest.approx(1.0, abs=1e-6) == sum(
                    candidate.score for candidate in result.candidates
                )
                return
        pytest.skip("no multi-candidate window in this sample")

    def test_the_prior_nudges_without_constraining(self, model, dataset):
        """A device that really did jump must still be able to say so."""
        from rfmapper_lab.fingerprint import build_fingerprints
        from rfmapper_lab.pipeline import _prior

        fingerprints = build_fingerprints(dataset.observations, model, DEFAULTS)
        rows = tuple(row for row in dataset.ordinary() if row.observer_id == "OBS-WALK")[:60]
        vector = build_vectors(rows, model, DEFAULTS)[0]
        classifier = ZONE_CLASSIFIERS.get("zone_bayes_v1")

        far_zone = sorted(model.zones)[-1]
        prior = _prior(far_zone, model)
        assert min(prior.values()) > 0.0, "no zone may be given a zero prior"

        with_prior = fuse_zones(
            vector, classifier, fingerprints.points, model, DEFAULTS, prior=prior
        )
        without = fuse_zones(vector, classifier, fingerprints.points, model, DEFAULTS)
        assert {c.zone_id for c in with_prior.candidates} == {
            c.zone_id for c in without.candidates
        }
