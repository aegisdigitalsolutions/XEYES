"""Phases 4, 6, 7 and 8: fingerprints, zone classification, placement and uncertainty.

The tests are written around the claims in ``docs/10-positioning-mathematical-architecture.md`` that
would be easy to satisfy with a plausible-looking implementation that is quietly wrong: a missing
source treated as no information, a single survey point reported as a live position, collinear
anchors solved anyway, a coordinate emitted without an error bar.
"""

from __future__ import annotations

import math
from dataclasses import replace

import pytest

from rfmapper_lab.fingerprint import build_fingerprints
from rfmapper_lab.fingerprint.engine import FingerprintEngineV1
from rfmapper_lab.models import (
    FingerprintEntry,
    FingerprintPoint,
    IdentifierType,
    LiveVector,
    Measurement,
    Observation,
    ObserverCalibration,
    PositionEstimate,
    PrecisionTier,
    SensorType,
    ZoneCandidate,
    ZoneResult,
)
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.positioning import place
from rfmapper_lab.positioning.uncertainty import (
    EmpiricalErrorModel,
    containment,
    resolve_uncertainty,
)
from rfmapper_lab.registry import POSITIONING_ENGINES, ZONE_CLASSIFIERS
from rfmapper_lab.timeutil import format_ms
from rfmapper_lab.zone.classifiers import sensor_supports_zone_evidence, signal_distance

BASE_MS = 1_789_286_400_000


def _entry(identifier: str, median: float, *, visibility: float = 1.0, sigma: float = 3.0):
    return FingerprintEntry(
        radio_identifier=identifier,
        identifier_type=IdentifierType.WIFI_BSSID,
        sample_count=30,
        visibility_probability=visibility,
        rssi_median=median,
        rssi_stddev=sigma,
    )


def _point(zone_id: str, entries, *, x=None, y=None, suffix="") -> FingerprintPoint:
    return FingerprintPoint(
        fingerprint_id=f"FP-{zone_id}{suffix}",
        survey_point_id=f"SP-{zone_id}{suffix}",
        building_id=zone_id.split("-")[0],
        zone_id=zone_id,
        x=x,
        y=y,
        entries=tuple(entries),
    )


def _vector(readings: dict[str, float], *, device="DEVICE-X", timestamp=BASE_MS) -> LiveVector:
    return LiveVector(
        device_id=device,
        timestamp_ms=timestamp,
        timestamp_utc=format_ms(timestamp),
        measurements=tuple(
            Measurement(
                observer_id="OBS-00",
                radio_identifier=identifier,
                identifier_type=IdentifierType.WIFI_BSSID,
                sensor_type=SensorType.WIFI_SCAN,
                rssi_raw=int(value),
                rssi_normalized=value,
                age_ms=0,
                freshness="FRESH",
                observation_id=f"obs-{identifier}",
            )
            for identifier, value in readings.items()
        ),
    )


def _survey_row(
    observation_id: str,
    timestamp_ms: int,
    identifier: str,
    rssi: int,
    *,
    point_id: str,
    session: str,
    observer: str = "OBS-00",
) -> Observation:
    return Observation(
        observation_id=observation_id,
        timestamp_utc=format_ms(timestamp_ms),
        timestamp_ms=timestamp_ms,
        observer_id=observer,
        sensor_type=SensorType.WIFI_SCAN,
        radio_identifier=identifier,
        identifier_type=IdentifierType.WIFI_BSSID,
        rssi=rssi,
        metadata={
            "sample_kind": "GROUND_TRUTH",
            "survey_session_id": session,
            "survey_point_id": point_id,
            "result_freshness": "FRESH",
        },
    )


class TestSignalDistance:
    def test_a_reliably_seen_source_that_is_absent_counts_against_the_location(self):
        """The term naive matchers omit (``docs/10`` §6.1).

        Two locations agree perfectly on the sources they share. One of them also reliably sees a
        third source that the live vector does not, and that absence is what separates them.
        """
        live = {"ap-1": -60.0, "ap-2": -70.0}
        without = _point("B1-A", [_entry("ap-1", -60), _entry("ap-2", -70)])
        with_extra = _point(
            "B1-B",
            [_entry("ap-1", -60), _entry("ap-2", -70), _entry("ap-3", -55, visibility=0.95)],
        )

        assert signal_distance(live, without, DEFAULTS.zone) == pytest.approx(0.0)
        assert signal_distance(live, with_extra, DEFAULTS.zone) > 5.0

    def test_a_rarely_seen_absent_source_barely_counts(self):
        """The penalty is proportional to visibility, so absence is weighted by how surprising
        it is. A source seen in 5% of samples being missing says almost nothing.
        """
        live = {"ap-1": -60.0, "ap-2": -70.0}
        rare = _point(
            "B1-A", [_entry("ap-1", -60), _entry("ap-2", -70), _entry("ap-3", -55, visibility=0.05)]
        )
        reliable = _point(
            "B1-B", [_entry("ap-1", -60), _entry("ap-2", -70), _entry("ap-3", -55, visibility=1.0)]
        )

        assert signal_distance(live, rare, DEFAULTS.zone) < signal_distance(
            live, reliable, DEFAULTS.zone
        )

    def test_a_source_the_fingerprint_never_saw_contributes_nothing(self):
        """Asymmetric on purpose: the fingerprint assigns no visibility to a source it never
        recorded, so there is no term to add. Inventing one would be a different algorithm.
        """
        point = _point("B1-A", [_entry("ap-1", -60), _entry("ap-2", -70)])
        matched = signal_distance({"ap-1": -60.0, "ap-2": -70.0}, point, DEFAULTS.zone)
        plus_unknown = signal_distance(
            {"ap-1": -60.0, "ap-2": -70.0, "ap-99": -50.0}, point, DEFAULTS.zone
        )

        assert matched == pytest.approx(plus_unknown)

    def test_a_single_coincidence_is_a_refusal_not_a_confident_match(self):
        """One shared source can agree exactly and mean nothing. ``inf`` is a refusal, and the
        alternative — a distance of zero — would rank as the best match on the site.
        """
        point = _point("B1-A", [_entry("ap-1", -60), _entry("ap-2", -70)])
        distance = signal_distance({"ap-1": -60.0}, point, DEFAULTS.zone)

        assert distance == math.inf

    def test_the_minimum_overlap_is_a_parameter_not_a_constant(self):
        point = _point("B1-A", [_entry("ap-1", -60), _entry("ap-2", -70)])
        relaxed = replace(DEFAULTS.zone, min_shared_sources=1)

        assert math.isfinite(signal_distance({"ap-1": -60.0}, point, relaxed))


class TestZoneClassifiers:
    ZONES = ("zone_nn_v1", "zone_wknn_v1", "zone_cosine_v1", "zone_bayes_v1")

    @pytest.fixture()
    def two_zones(self, model):
        """Two fingerprints in real zones of the simulated site, far apart in signal space."""
        near, far = sorted(model.zones)[:2]
        return (
            _point(near, [_entry("ap-1", -45), _entry("ap-2", -50), _entry("ap-3", -85)]),
            _point(far, [_entry("ap-1", -85), _entry("ap-2", -88), _entry("ap-3", -45)]),
        )

    @pytest.mark.parametrize("classifier_id", ZONES)
    def test_every_classifier_returns_a_ranking_not_a_winner(self, classifier_id, model, two_zones):
        result = ZONE_CLASSIFIERS.get(classifier_id).classify(
            _vector({"ap-1": -46.0, "ap-2": -51.0, "ap-3": -84.0}), two_zones, model, DEFAULTS
        )

        assert len(result.candidates) == 2, "the runner-up must survive to the confidence step"
        assert result.candidates[0].score >= result.candidates[1].score
        assert result.method == classifier_id

    @pytest.mark.parametrize("classifier_id", ZONES)
    def test_every_classifier_picks_the_zone_the_vector_was_drawn_from(
        self, classifier_id, model, two_zones
    ):
        result = ZONE_CLASSIFIERS.get(classifier_id).classify(
            _vector({"ap-1": -46.0, "ap-2": -51.0, "ap-3": -84.0}), two_zones, model, DEFAULTS
        )

        assert result.best.zone_id == two_zones[0].zone_id

    @pytest.mark.parametrize("classifier_id", ZONES)
    def test_every_classifier_declines_rather_than_guesses(self, classifier_id, model, two_zones):
        """A vector sharing nothing with any fingerprint yields no candidates. An empty ranking
        degrades the tier; a guessed zone would be indistinguishable from a real one.
        """
        result = ZONE_CLASSIFIERS.get(classifier_id).classify(
            _vector({"unknown-1": -50.0, "unknown-2": -60.0}), two_zones, model, DEFAULTS
        )

        assert result.candidates == ()

    @pytest.mark.parametrize("classifier_id", ZONES)
    def test_a_zone_outside_the_site_model_is_never_ranked(self, classifier_id, model):
        """The classifier ranks zones, but the site model decides which zones exist."""
        orphan = _point("ZONE-FROM-A-DELETED-SITE", [_entry("ap-1", -45), _entry("ap-2", -50)])
        result = ZONE_CLASSIFIERS.get(classifier_id).classify(
            _vector({"ap-1": -45.0, "ap-2": -50.0}), (orphan,), model, DEFAULTS
        )

        assert result.candidates == ()

    def test_ties_break_on_zone_id_so_the_output_is_reproducible(self, model):
        """Byte-identical re-runs require a total order, and float equality does not supply one."""
        first, second = sorted(model.zones)[:2]
        identical = [
            _point(first, [_entry("ap-1", -60), _entry("ap-2", -70)]),
            _point(second, [_entry("ap-1", -60), _entry("ap-2", -70)]),
        ]
        vector = _vector({"ap-1": -60.0, "ap-2": -70.0})

        rankings = {
            tuple(
                candidate.zone_id
                for candidate in ZONE_CLASSIFIERS.get("zone_nn_v1")
                .classify(vector, tuple(order), model, DEFAULTS)
                .candidates
            )
            for order in (identical, list(reversed(identical)))
        }
        assert len(rankings) == 1
        assert next(iter(rankings)) == (first, second)

    def test_the_bayesian_posterior_is_a_distribution_over_evaluated_zones(self, model, two_zones):
        result = ZONE_CLASSIFIERS.get("zone_bayes_v1").classify(
            _vector({"ap-1": -46.0, "ap-2": -51.0, "ap-3": -84.0}), two_zones, model, DEFAULTS
        )

        assert sum(candidate.score for candidate in result.candidates) == pytest.approx(1.0)

    def test_a_surveyed_sigma_of_zero_does_not_annihilate_a_near_match(self, model):
        """Without the sigma floor the Gaussian becomes a delta function and one dB of drift
        rules out an otherwise perfect location.
        """
        near, far = sorted(model.zones)[:2]
        exact = _point(
            near,
            [_entry("ap-1", -60, sigma=0.0), _entry("ap-2", -70, sigma=0.0)],
        )
        other = _point(far, [_entry("ap-1", -85, sigma=3.0), _entry("ap-2", -88, sigma=3.0)])
        result = ZONE_CLASSIFIERS.get("zone_bayes_v1").classify(
            _vector({"ap-1": -61.0, "ap-2": -69.0}), (exact, other), model, DEFAULTS
        )

        assert result.best.zone_id == near
        assert math.isfinite(result.best.score)

    def test_the_anchor_classifier_never_produces_coordinates(self, model):
        """A published coverage radius is a planning figure, not a distance measurement."""
        node = next(
            (n for n in model.infrastructure.values() if n.zone_id and n.known_bssid), None
        )
        assert node is not None, "the simulated site must register a located anchor"

        result = ZONE_CLASSIFIERS.get("zone_anchor_v1").classify(
            _vector({node.known_bssid: -55.0}), (), model, DEFAULTS
        )

        assert result.best.zone_id == node.zone_id
        assert all(candidate.distance is None for candidate in result.candidates)

    def test_margin_reports_indiscriminability_rather_than_hiding_it(self, model):
        near, far = sorted(model.zones)[:2]
        twins = (
            _point(near, [_entry("ap-1", -60), _entry("ap-2", -70)]),
            _point(far, [_entry("ap-1", -60.5), _entry("ap-2", -70.5)]),
        )
        distinct = (
            _point(near, [_entry("ap-1", -60), _entry("ap-2", -70)]),
            _point(far, [_entry("ap-1", -85), _entry("ap-2", -88)]),
        )
        vector = _vector({"ap-1": -60.0, "ap-2": -70.0})
        classifier = ZONE_CLASSIFIERS.get("zone_bayes_v1")

        indistinct = classifier.classify(vector, twins, model, DEFAULTS).margin
        clear = classifier.classify(vector, distinct, model, DEFAULTS).margin
        assert indistinct < clear

    def test_a_gnss_fix_is_not_zone_evidence(self):
        """A GNSS fix locates the observer, not the device it saw, and a manual entry is an
        assertion rather than a measurement.
        """
        assert not sensor_supports_zone_evidence(SensorType.GPS)
        assert not sensor_supports_zone_evidence(SensorType.MANUAL)
        assert sensor_supports_zone_evidence(SensorType.WIFI_SCAN)
        assert sensor_supports_zone_evidence(SensorType.RTT)


class TestFingerprintEngine:
    def _survey(self, *, sessions: int, samples: int, point_id: str) -> list[Observation]:
        rows: list[Observation] = []
        for session in range(sessions):
            for sample in range(samples):
                # Fresh burst per sample, so bursts rather than rows are the sample unit.
                timestamp = BASE_MS + session * 86_400_000 + sample * 5_000
                for index, identifier in enumerate(("ap-1", "ap-2", "ap-3")):
                    rows.append(
                        _survey_row(
                            f"s{session}-p{sample}-{identifier}",
                            timestamp,
                            identifier,
                            -55 - 5 * index,
                            point_id=point_id,
                            session=f"SESSION-{session}",
                        )
                    )
        return rows

    @pytest.fixture()
    def surveyed(self, model):
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        built = FingerprintEngineV1().build(rows, model, DEFAULTS)
        assert built, "a well-surveyed point must yield a fingerprint"
        return built[0]

    def test_a_fingerprint_stores_a_distribution_not_a_value(self, surveyed):
        entry = surveyed.by_identifier()["ap-1"]

        assert entry.rssi_median == pytest.approx(-55.0)
        assert entry.rssi_stddev is not None
        assert entry.rssi_p10 is not None and entry.rssi_p90 is not None
        assert entry.rssi_min is not None and entry.rssi_max is not None

    def test_visibility_probability_is_recorded_rather_than_a_floor_rssi_substituted(
        self, model
    ):
        """A source in half the bursts must read as "seen half the time", not as −100 dBm."""
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        # A fourth source present in a third of the bursts.
        for session in range(3):
            for sample in range(3):
                rows.append(
                    _survey_row(
                        f"flaky-{session}-{sample}",
                        BASE_MS + session * 86_400_000 + sample * 5_000,
                        "ap-flaky",
                        -78,
                        point_id=point_id,
                        session=f"SESSION-{session}",
                    )
                )

        built = FingerprintEngineV1().build(rows, model, DEFAULTS)[0]
        flaky = built.by_identifier()["ap-flaky"]

        assert 0.0 < flaky.visibility_probability < 0.5
        assert flaky.rssi_median == pytest.approx(-78.0)
        assert built.by_identifier()["ap-1"].visibility_probability == pytest.approx(1.0)

    def test_a_point_thinner_than_the_minimum_yields_nothing(self, model):
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=1, samples=1, point_id=point_id)[:2]

        assert FingerprintEngineV1().build(rows, model, DEFAULTS) == ()

    def test_temporal_stability_is_none_when_only_one_session_exists(self, model):
        """Reporting 1.0 would claim a stability that was never measured: cross-session variance
        is precisely what a single visit cannot see.
        """
        point_id = sorted(model.survey_points)[0]
        one_visit = FingerprintEngineV1().build(
            self._survey(sessions=1, samples=8, point_id=point_id), model, DEFAULTS
        )[0]
        three_visits = FingerprintEngineV1().build(
            self._survey(sessions=3, samples=8, point_id=point_id), model, DEFAULTS
        )[0]

        assert one_visit.by_identifier()["ap-1"].temporal_stability is None
        assert three_visits.by_identifier()["ap-1"].temporal_stability is not None
        assert one_visit.session_count == 1
        assert three_visits.session_count == 3

    def test_a_survey_point_the_site_model_does_not_define_is_skipped(self, model):
        rows = self._survey(sessions=3, samples=8, point_id="SP-NOWHERE")

        assert FingerprintEngineV1().build(rows, model, DEFAULTS) == ()

    def test_an_ordinary_observation_can_never_become_ground_truth(self, model):
        """Phase 3 reads survey samples and nothing else (``docs/11`` §1)."""
        point_id = sorted(model.survey_points)[0]
        rows = [
            row.with_metadata({k: v for k, v in row.metadata.items() if k != "sample_kind"})
            for row in self._survey(sessions=3, samples=8, point_id=point_id)
        ]

        assert all(not row.is_ground_truth for row in rows)
        assert FingerprintEngineV1().build(rows, model, DEFAULTS) == ()

    def test_a_ground_truth_claim_without_a_session_is_just_a_claim(self, model):
        point_id = sorted(model.survey_points)[0]
        rows = [
            row.with_metadata(
                {k: v for k, v in row.metadata.items() if k != "survey_session_id"}
            )
            for row in self._survey(sessions=3, samples=8, point_id=point_id)
        ]

        assert FingerprintEngineV1().build(rows, model, DEFAULTS) == ()

    def test_the_fingerprint_id_is_derived_from_its_evidence(self, model):
        """Re-running over the same survey sessions must reproduce the same id, which is what
        makes the Master's import idempotent rather than merely deduplicated.
        """
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        first = FingerprintEngineV1().build(rows, model, DEFAULTS)[0]
        again = FingerprintEngineV1().build(list(reversed(rows)), model, DEFAULTS)[0]
        fewer = FingerprintEngineV1().build(
            self._survey(sessions=2, samples=8, point_id=point_id), model, DEFAULTS
        )[0]

        assert first.fingerprint_id == again.fingerprint_id
        assert first.fingerprint_id != fewer.fingerprint_id

    def test_an_uncorrected_observer_offset_travels_into_the_fingerprint(self, model):
        """Calibration is applied at normalization, before any statistic is computed, and an
        offset too spread out to apply is declined rather than applied badly.
        """
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        rows = [replace(row, observer_id="OBS-BIASED") for row in rows]

        tight = replace(model)
        tight.calibration = dict(model.calibration) | {
            "OBS-BIASED": ObserverCalibration("OBS-BIASED", rssi_offset_db=6.0, spread_db=1.0)
        }
        loose = replace(model)
        loose.calibration = dict(model.calibration) | {
            "OBS-BIASED": ObserverCalibration("OBS-BIASED", rssi_offset_db=6.0, spread_db=20.0)
        }

        corrected = FingerprintEngineV1().build(rows, tight, DEFAULTS)[0]
        declined = FingerprintEngineV1().build(rows, loose, DEFAULTS)[0]

        assert corrected.by_identifier()["ap-1"].rssi_median == pytest.approx(-49.0)
        assert declined.by_identifier()["ap-1"].rssi_median == pytest.approx(-55.0)

    def test_a_promoted_reference_fingerprint_is_never_replaced_by_todays_build(self, model):
        """A promoted fingerprint may rest on months of sessions. Preferring a freshly built one
        because it is newer would be a downgrade disguised as an update.
        """
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        survey_point = model.survey_points[point_id]
        promoted = FingerprintPoint(
            fingerprint_id="FP-PROMOTED",
            survey_point_id=point_id,
            building_id=survey_point.building_id,
            zone_id=survey_point.zone_id,
            status="GROUND_TRUTH",
            entries=(_entry("ap-1", -61),),
        )
        with_promoted = replace(model)
        with_promoted.fingerprints = (promoted,)

        result = build_fingerprints(rows, with_promoted, DEFAULTS)
        for_point = [p for p in result.points if p.survey_point_id == point_id]

        assert [p.fingerprint_id for p in for_point] == ["FP-PROMOTED"]
        assert result.is_promoted("FP-PROMOTED")

    def test_a_freshly_built_fingerprint_is_used_and_marked_a_candidate(self, model):
        point_id = sorted(model.survey_points)[0]
        rows = self._survey(sessions=3, samples=8, point_id=point_id)
        result = build_fingerprints(rows, model, DEFAULTS)

        assert result.candidates
        assert result.promoted == ()
        assert all(point.status == "CANDIDATE" for point in result.candidates)


class TestPlacement:
    def _rtt(self, identifier: str, distance_mm: int, *, stddev_mm: int = 500) -> Measurement:
        return Measurement(
            observer_id="OBS-WALK",
            radio_identifier=identifier,
            identifier_type=IdentifierType.WIFI_BSSID,
            sensor_type=SensorType.RTT,
            rssi_raw=-60,
            rssi_normalized=-60.0,
            age_ms=0,
            freshness="FRESH",
            observation_id=f"obs-rtt-{identifier}",
            rtt_distance_mm=distance_mm,
            rtt_stddev_mm=stddev_mm,
        )

    def _anchor_vector(self, model, positions: dict[str, tuple[float, float]], truth):
        """Build an RTT vector whose ranges are consistent with ``truth``."""
        measurements = []
        for identifier, (x, y) in positions.items():
            distance = math.hypot(truth[0] - x, truth[1] - y)
            measurements.append(self._rtt(identifier, int(distance * 1000)))
        return LiveVector(
            device_id="DEVICE-PHONE",
            timestamp_ms=BASE_MS,
            timestamp_utc=format_ms(BASE_MS),
            measurements=tuple(measurements),
        )

    @pytest.fixture()
    def triangle(self, model):
        """Three located anchors in a genuine triangle, injected into a copy of the site."""
        positions = {
            "aa:00:00:00:00:01": (0.0, 0.0),
            "aa:00:00:00:00:02": (20.0, 0.0),
            "aa:00:00:00:00:03": (10.0, 18.0),
        }
        from rfmapper_lab.models import InfrastructureNode

        sited = replace(model)
        sited.infrastructure = dict(model.infrastructure) | {
            f"ANCHOR-{index}": InfrastructureNode(
                node_id=f"ANCHOR-{index}",
                friendly_name=f"ANCHOR-{index}",
                type="RTT_ANCHOR",
                x=x,
                y=y,
                known_bssid=identifier,
                rtt_capable=True,
            )
            for index, (identifier, (x, y)) in enumerate(positions.items())
        }
        return sited, positions

    def test_multilateration_recovers_the_position_the_ranges_describe(self, triangle):
        sited, positions = triangle
        truth = (8.0, 6.0)
        placement = POSITIONING_ENGINES.get("pos_rtt_multilateration_v1").estimate(
            self._anchor_vector(sited, positions, truth), ZoneResult((), "none"), (), sited, DEFAULTS
        )

        assert placement is not None
        assert placement.precision_tier is PrecisionTier.PRECISION_RANGE
        assert math.hypot(placement.x - truth[0], placement.y - truth[1]) < 0.5
        assert placement.sigma_geometric_m >= DEFAULTS.positioning.rtt_bias_floor_m

    def test_collinear_anchors_are_declined_rather_than_solved(self, model):
        """The solver converges on a line and reports a small residual; the answer is confidently
        wrong along the baseline, and a circular error bar would misdescribe it.
        """
        from rfmapper_lab.models import InfrastructureNode

        positions = {
            "bb:00:00:00:00:01": (0.0, 1.0),
            "bb:00:00:00:00:02": (10.0, 1.0),
            "bb:00:00:00:00:03": (20.0, 1.0),
        }
        sited = replace(model)
        sited.infrastructure = {
            f"LINE-{index}": InfrastructureNode(
                node_id=f"LINE-{index}",
                friendly_name=f"LINE-{index}",
                type="RTT_ANCHOR",
                x=x,
                y=y,
                known_bssid=identifier,
                rtt_capable=True,
            )
            for index, (identifier, (x, y)) in enumerate(positions.items())
        }

        placement = POSITIONING_ENGINES.get("pos_rtt_multilateration_v1").estimate(
            self._anchor_vector(sited, positions, (10.0, 1.0)),
            ZoneResult((), "none"),
            (),
            sited,
            DEFAULTS,
        )
        assert placement is None

    def test_two_ranges_are_declined_because_of_the_mirror_ambiguity(self, triangle):
        sited, positions = triangle
        pair = dict(list(positions.items())[:2])
        placement = POSITIONING_ENGINES.get("pos_rtt_multilateration_v1").estimate(
            self._anchor_vector(sited, pair, (8.0, 6.0)),
            ZoneResult((), "none"),
            (),
            sited,
            DEFAULTS,
        )
        assert placement is None

    def test_a_range_to_an_unlocated_anchor_is_unusable(self, model):
        """There is nothing to be eleven metres from until an administrator measures the anchor."""
        from rfmapper_lab.models import InfrastructureNode

        sited = replace(model)
        sited.infrastructure = {
            "FLOATING": InfrastructureNode(
                node_id="FLOATING",
                friendly_name="FLOATING",
                type="WIFI_AP",
                known_bssid="cc:00:00:00:00:01",
                rtt_capable=True,
            )
        }
        vector = LiveVector(
            device_id="DEVICE-PHONE",
            timestamp_ms=BASE_MS,
            timestamp_utc=format_ms(BASE_MS),
            measurements=tuple(
                self._rtt(f"cc:00:00:00:00:0{index}", 11_000) for index in range(1, 4)
            ),
        )

        placement = POSITIONING_ENGINES.get("pos_rtt_multilateration_v1").estimate(
            vector, ZoneResult((), "none"), (), sited, DEFAULTS
        )
        assert placement is None

    def test_repeated_ranges_to_one_anchor_are_one_constraint(self, triangle):
        """Counting them separately would shrink the reported covariance without adding
        information, which is under-reported uncertainty by arithmetic.
        """
        sited, positions = triangle
        identifier = next(iter(positions))
        vector = LiveVector(
            device_id="DEVICE-PHONE",
            timestamp_ms=BASE_MS,
            timestamp_utc=format_ms(BASE_MS),
            measurements=tuple(
                self._rtt(identifier, 8_000 + offset, stddev_mm=500 + offset)
                for offset in (0, 50, 100)
            ),
        )

        placement = POSITIONING_ENGINES.get("pos_rtt_multilateration_v1").estimate(
            vector, ZoneResult((), "none"), (), sited, DEFAULTS
        )
        assert placement is None, "three readings of one anchor is one range, not three"

    def test_the_weighted_centroid_needs_more_than_one_located_fingerprint(self, model):
        """A single located fingerprint would return that survey point's own coordinates, which
        reports where somebody once stood rather than where the device is now.
        """
        zone_id = sorted(model.zones)[0]
        entries = [_entry("ap-1", -60), _entry("ap-2", -70)]
        zones = ZoneResult(
            (ZoneCandidate(zone_id, model.zones[zone_id].building_id, 1.0),), "test"
        )
        vector = _vector({"ap-1": -60.0, "ap-2": -70.0})
        engine = POSITIONING_ENGINES.get("pos_wknn_centroid_v1")

        alone = (_point(zone_id, entries, x=5.0, y=5.0),)
        pair = (
            _point(zone_id, entries, x=5.0, y=5.0, suffix="-a"),
            _point(zone_id, entries, x=9.0, y=5.0, suffix="-b"),
        )

        assert engine.estimate(vector, zones, alone, model, DEFAULTS) is None
        together = engine.estimate(vector, zones, pair, model, DEFAULTS)
        assert together is not None
        assert together.x == pytest.approx(7.0)
        assert together.precision_tier is PrecisionTier.APPROXIMATE_POSITION

    def test_the_fallback_chain_ends_at_a_zone_and_that_is_a_valid_answer(self, model):
        """Most evidence in a real deployment supports a zone and nothing finer. A point with a
        large circle would read as a measurement that was never made.
        """
        zone_id = sorted(model.zones)[0]
        zones = ZoneResult(
            (ZoneCandidate(zone_id, model.zones[zone_id].building_id, 0.8),), "test"
        )
        bare = replace(model)
        bare.zones = {
            zone_id: replace(model.zones[zone_id], polygon=(), centroid_x=None, centroid_y=None,
                             enclosing_radius_m=None)
        }

        placement = place(_vector({"ap-1": -60.0}), zones, (), bare, DEFAULTS)

        assert placement is not None
        assert placement.method == "pos_zone_only_v1"
        assert placement.precision_tier is PrecisionTier.ZONE
        assert not placement.has_coordinates

    def test_no_zone_and_no_range_produces_no_placement_at_all(self, model):
        assert place(_vector({"ap-1": -60.0}), ZoneResult((), "none"), (), model, DEFAULTS) is None

    def test_the_strategy_order_prefers_the_deepest_justifiable_tier(self, triangle):
        sited, positions = triangle
        zone_id = sorted(sited.zones)[0]
        zones = ZoneResult(
            (ZoneCandidate(zone_id, sited.zones[zone_id].building_id, 0.9),), "test"
        )
        located = (
            _point(zone_id, [_entry("ap-1", -60)], x=5.0, y=5.0, suffix="-a"),
            _point(zone_id, [_entry("ap-1", -60)], x=9.0, y=5.0, suffix="-b"),
        )
        vector = self._anchor_vector(sited, positions, (8.0, 6.0))

        placement = place(vector, zones, located, sited, DEFAULTS)
        assert placement.method == "pos_rtt_multilateration_v1"

        # Restricting the strategy list is how the benchmark isolates a method, and it must not
        # smuggle the ranged result back in.
        without_rtt = place(
            vector, zones, located, sited, DEFAULTS, strategies=("pos_zone_centroid_v1",)
        )
        assert without_rtt.method == "pos_zone_centroid_v1"

    def test_a_zone_centroid_reports_the_zones_own_radius(self, model):
        zone_id = next(
            (z for z, zone in sorted(model.zones.items()) if zone.radius_m), None
        )
        assert zone_id is not None, "the simulated site must publish zone geometry"
        zone = model.zones[zone_id]
        zones = ZoneResult((ZoneCandidate(zone_id, zone.building_id, 0.7),), "test")

        placement = POSITIONING_ENGINES.get("pos_zone_centroid_v1").estimate(
            _vector({"ap-1": -60.0}), zones, (), model, DEFAULTS
        )

        assert placement.sigma_geometric_m == pytest.approx(zone.radius_m)
        assert "ZONE_CENTROID_ONLY" in placement.quality_flags


class TestUncertainty:
    def test_a_coordinate_without_an_error_bar_cannot_be_constructed(self):
        """Enforced in the constructor rather than checked by the writer: a bare point on a map
        reads as a fact, so the type refuses to represent one.
        """
        fields = dict(
            estimate_id="e-1",
            algorithm_version="test",
            device_id="DEVICE-X",
            timestamp_utc=format_ms(BASE_MS),
            computed_at_utc=format_ms(BASE_MS),
            precision_tier=PrecisionTier.APPROXIMATE_POSITION,
            confidence=0.7,
            method="pos_wknn_centroid_v1",
            supporting_observer_ids=("OBS-00",),
            supporting_observation_ids=("obs-1",),
            building_id="B4",
            x=5.0,
            y=5.0,
        )

        with pytest.raises(ValueError, match="never emit a position without uncertainty"):
            PositionEstimate(**fields)

        assert PositionEstimate(**fields, horizontal_uncertainty_m=4.0).x == 5.0

    def test_a_ranged_tier_cannot_be_claimed_without_ranged_evidence(self):
        """No amount of confident RSSI is promotable into ``PRECISION_RANGE``."""
        with pytest.raises(ValueError, match="requires coordinates"):
            PositionEstimate(
                estimate_id="e-2",
                algorithm_version="test",
                device_id="DEVICE-X",
                timestamp_utc=format_ms(BASE_MS),
                computed_at_utc=format_ms(BASE_MS),
                precision_tier=PrecisionTier.PRECISION_RANGE,
                confidence=0.99,
                method="pos_rtt_multilateration_v1",
                supporting_observer_ids=("OBS-00",),
                supporting_observation_ids=("obs-1",),
            )

    def test_site_presence_must_not_assert_a_zone(self):
        with pytest.raises(ValueError, match="SITE_PRESENCE must not assert a zone"):
            PositionEstimate(
                estimate_id="e-3",
                algorithm_version="test",
                device_id="DEVICE-X",
                timestamp_utc=format_ms(BASE_MS),
                computed_at_utc=format_ms(BASE_MS),
                precision_tier=PrecisionTier.SITE_PRESENCE,
                confidence=0.4,
                method="pos_zone_only_v1",
                supporting_observer_ids=("OBS-00",),
                supporting_observation_ids=("obs-1",),
                building_id="B4",
                zone_id="B4-WEST",
            )

    def test_an_estimate_with_no_supporting_observation_is_not_an_estimate(self):
        with pytest.raises(ValueError, match="no supporting observation"):
            PositionEstimate(
                estimate_id="e-4",
                algorithm_version="test",
                device_id="DEVICE-X",
                timestamp_utc=format_ms(BASE_MS),
                computed_at_utc=format_ms(BASE_MS),
                precision_tier=PrecisionTier.BUILDING,
                confidence=0.4,
                method="pos_zone_only_v1",
                supporting_observer_ids=("OBS-00",),
                supporting_observation_ids=(),
                building_id="B4",
            )

    def test_an_unmeasured_method_is_flagged_rather_than_padded_with_a_default(self):
        value, flags = resolve_uncertainty(
            "pos_wknn_centroid_v1", 4.0, EmpiricalErrorModel.unvalidated(), DEFAULTS.uncertainty
        )

        assert value == pytest.approx(4.0)
        assert "UNVALIDATED_UNCERTAINTY" in flags

    def test_the_two_terms_add_in_quadrature(self):
        measured = EmpiricalErrorModel(p68_by_method={"pos_wknn_centroid_v1": 3.0}, dataset_kind="REAL")
        value, flags = resolve_uncertainty(
            "pos_wknn_centroid_v1", 4.0, measured, DEFAULTS.uncertainty
        )

        assert value == pytest.approx(5.0)
        assert flags == ()

    def test_an_empirical_term_from_synthetic_data_says_so(self):
        """A number measured on a simulator is not evidence about a real site."""
        synthetic = EmpiricalErrorModel(
            p68_by_method={"pos_wknn_centroid_v1": 3.0}, dataset_kind="SYNTHETIC"
        )
        _, flags = resolve_uncertainty(
            "pos_wknn_centroid_v1", 4.0, synthetic, DEFAULTS.uncertainty
        )

        assert "UNCERTAINTY_FROM_SYNTHETIC_BENCHMARK" in flags
        assert not synthetic.validated

    def test_a_sub_metre_claim_from_fingerprinting_is_floored(self):
        value, flags = resolve_uncertainty(
            "pos_wknn_centroid_v1", 0.02, EmpiricalErrorModel.unvalidated(), DEFAULTS.uncertainty
        )

        assert value == pytest.approx(DEFAULTS.uncertainty.min_uncertainty_m)
        assert "UNCERTAINTY_FLOORED" in flags

    def test_an_absurd_spread_is_capped_and_says_so(self):
        value, flags = resolve_uncertainty(
            "pos_zone_centroid_v1", 4_000.0, EmpiricalErrorModel.unvalidated(), DEFAULTS.uncertainty
        )

        assert value == pytest.approx(DEFAULTS.uncertainty.max_uncertainty_m)
        assert "UNCERTAINTY_CAPPED" in flags

    def test_a_zone_answer_has_no_horizontal_uncertainty_to_report(self):
        value, flags = resolve_uncertainty(
            "pos_zone_only_v1", None, EmpiricalErrorModel.unvalidated(), DEFAULTS.uncertainty
        )

        assert value is None
        assert flags == ()

    def test_containment_is_the_metric_that_catches_flattering_error_bars(self):
        errors = [1.0, 2.0, 3.0, 12.0]
        honest = [4.0, 4.0, 4.0, 4.0]
        flattering = [0.5, 0.5, 0.5, 0.5]

        assert containment(errors, honest) == pytest.approx(0.75)
        assert containment(errors, flattering) == pytest.approx(0.0)
        assert containment(errors, flattering, multiplier=1.96) == pytest.approx(0.0)
        assert containment([], []) is None

    def test_a_zero_sigma_is_excluded_rather_than_counted_as_a_miss(self):
        """A method that reported no sigma has not failed containment; it has not been measured."""
        assert containment([1.0, 2.0], [0.0, 4.0]) == pytest.approx(1.0)
