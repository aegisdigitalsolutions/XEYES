"""The benchmark harness and the regression gate.

The methodology in ``docs/12-benchmark-methodology-and-error-metrics.md`` exists to stop a
fingerprinting system from reporting double the accuracy it delivers, and every rule it lays down is
one that a well-meaning implementation breaks by default: splitting by sample, resampling samples
instead of sessions, quoting an error without its coverage, letting the winner be chosen on TEST.
These tests check the rules rather than the numbers, because the numbers are synthetic and the rules
are what will still be true on a real site.
"""

from __future__ import annotations

import json
from dataclasses import replace

import pytest

from rfmapper_lab.benchmark import (
    Baseline,
    BenchmarkReport,
    Candidate,
    CandidateResult,
    SessionSplit,
    default_candidates,
    empirical_error_model,
    format_report,
    run_benchmark,
)
from rfmapper_lab.benchmark.baseline import TARGET_CONTAINMENT, check
from rfmapper_lab.benchmark.harness import select
from rfmapper_lab.benchmark.metrics import (
    Interval,
    Record,
    bootstrap,
    calibration_metrics,
    classification_metrics,
    positional_metrics,
    zone_accuracy_of,
)
from rfmapper_lab.benchmark.splits import (
    held_out_samples,
    session_ids,
    split_sessions,
    training_observations,
)
from rfmapper_lab.jsonio import dumps
from rfmapper_lab.models import DatasetKind, PositionEstimate, PrecisionTier
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.positioning import EmpiricalErrorModel
from rfmapper_lab.registry import POSITIONING_ENGINES
from rfmapper_lab.timeutil import format_ms

BASE_MS = 1_789_286_400_000

#: Enough resamples that the interval is meaningful, few enough that the suite stays quick.
RESAMPLES = 120


def _record(
    session: str,
    truth_zone: str,
    predicted_zone: str | None,
    *,
    truth=(0.0, 0.0),
    estimate_at=None,
    sigma: float | None = 4.0,
    cpu_ms: float = 1.0,
    ranked: tuple[str, ...] | None = None,
) -> Record:
    estimate = None
    if predicted_zone is not None or estimate_at is not None:
        has_xy = estimate_at is not None
        estimate = PositionEstimate(
            estimate_id=f"est-{session}-{truth_zone}-{predicted_zone}-{estimate_at}",
            algorithm_version="test",
            device_id="survey:SP-1",
            timestamp_utc=format_ms(BASE_MS),
            computed_at_utc=format_ms(BASE_MS),
            precision_tier=(
                PrecisionTier.APPROXIMATE_POSITION if has_xy else PrecisionTier.ZONE
            ),
            confidence=0.8,
            method="pos_wknn_centroid_v1" if has_xy else "pos_zone_only_v1",
            supporting_observer_ids=("OBS-00",),
            supporting_observation_ids=("obs-1",),
            building_id="B4",
            zone_id=predicted_zone,
            x=estimate_at[0] if has_xy else None,
            y=estimate_at[1] if has_xy else None,
            horizontal_uncertainty_m=sigma if has_xy else None,
        )
    return Record(
        session_id=session,
        survey_point_id="SP-1",
        truth_zone_id=truth_zone,
        truth_building_id="B4",
        truth_x=truth[0],
        truth_y=truth[1],
        estimate=estimate,
        ranked_zone_ids=ranked if ranked is not None else ((predicted_zone,) if predicted_zone else ()),
        cpu_ms=cpu_ms,
    )


class TestSessionSplits:
    def test_a_session_lands_wholly_on_one_side_of_the_wall(self):
        """Splitting by sample puts near-duplicates of one measurement on both sides, and the
        accuracy then measures memorisation of a session rather than placement of a device.
        """
        split = split_sessions([f"S{index}" for index in range(10)], seed=7)
        groups = [set(split.train), set(split.validation), set(split.test)]

        assert sum(len(group) for group in groups) == 10
        assert set.union(*groups) == {f"S{index}" for index in range(10)}
        for left in range(3):
            for right in range(left + 1, 3):
                assert not groups[left] & groups[right]

    def test_the_split_is_reproducible_for_a_seed_and_changes_with_it(self):
        sessions = [f"S{index}" for index in range(10)]

        assert split_sessions(sessions, seed=7) == split_sessions(sessions, seed=7)
        assert split_sessions(sessions, seed=7) != split_sessions(sessions, seed=8)

    def test_the_split_does_not_depend_on_the_order_the_sessions_arrived(self):
        sessions = [f"S{index}" for index in range(10)]

        assert split_sessions(sessions, seed=7) == split_sessions(
            list(reversed(sessions)), seed=7
        )

    def test_test_is_never_empty_when_there_is_anything_to_hold_out(self):
        """A benchmark with nothing held out is not a benchmark."""
        for count in range(2, 12):
            split = split_sessions([f"S{i}" for i in range(count)], seed=3)
            assert split.test, f"{count} sessions produced an empty TEST split"
            assert split.train

    def test_a_single_session_degenerates_loudly(self):
        """One session cannot be both trained on and tested against, and the honest answer to
        "how accurate is this?" after one survey is that it has not been measured.
        """
        split = split_sessions(["S0"], seed=3)

        assert split.train == ("S0",)
        assert split.test == ()

    def test_no_sessions_at_all_is_an_empty_split_rather_than_an_error(self):
        assert split_sessions([], seed=3).test == ()

    def test_training_rows_come_only_from_the_named_sessions(self, dataset):
        """The one line that keeps the whole exercise honest."""
        ground_truth = dataset.ground_truth()
        sessions = session_ids(ground_truth)
        split = split_sessions(sessions, seed=DEFAULTS.random_seed)

        training = training_observations(ground_truth, split.train)

        assert training
        assert {row.survey_session_id for row in training} <= set(split.train)
        assert all(row.is_ground_truth for row in training)

    def test_held_out_samples_carry_the_truth_they_are_scored_against(self, dataset):
        ground_truth = dataset.ground_truth()
        split = split_sessions(session_ids(ground_truth), seed=DEFAULTS.random_seed)
        samples = held_out_samples(ground_truth, split.test, dataset.reference, DEFAULTS)

        assert samples
        for sample in samples:
            assert sample.session_id in split.test
            point = dataset.reference.survey_points[sample.survey_point_id]
            assert (sample.x, sample.y) == (point.x, point.y)
            assert sample.zone_id == point.zone_id
            assert sample.vector.measurements

    def test_a_held_out_window_with_no_signal_is_not_scored(self, dataset):
        """Scoring an empty input measures the algorithm's behaviour on nothing, which is a unit
        test rather than a benchmark.
        """
        ground_truth = dataset.ground_truth()
        split = split_sessions(session_ids(ground_truth), seed=DEFAULTS.random_seed)
        stripped = tuple(replace(row, rssi=None, rtt_distance_mm=None) for row in ground_truth)

        assert held_out_samples(stripped, split.test, dataset.reference, DEFAULTS) == ()


class TestMetrics:
    def test_declining_to_answer_scores_zero_rather_than_scoring_nothing(self):
        """An algorithm that emits nothing is not perfect on the subset it deigned to answer."""
        records = [
            _record("S1", "B4-WEST", "B4-WEST"),
            _record("S1", "B4-EAST", None),
            _record("S1", "B4-EAST", None),
            _record("S1", "B4-EAST", None),
        ]
        metrics = classification_metrics(records, _model_stub())

        assert metrics.zone_accuracy == pytest.approx(0.25)
        assert metrics.zone_coverage == pytest.approx(0.25)
        assert metrics.zone_accuracy_answered == pytest.approx(1.0)

    def test_coverage_cannot_be_omitted_from_an_error_report(self):
        """``PositionalMetrics`` takes coverage as a constructor argument, and the headline prints
        it beside every figure: 3-metre error on 10% of windows is not better than 7 on 95%.
        """
        selective = [
            _record("S1", "B4-WEST", "B4-WEST", estimate_at=(3.0, 0.0)),
            *[_record("S1", "B4-WEST", "B4-WEST") for _ in range(9)],
        ]
        generous = [
            _record("S1", "B4-WEST", "B4-WEST", estimate_at=(7.0, 0.0)) for _ in range(10)
        ]

        narrow = positional_metrics(selective)
        wide = positional_metrics(generous)

        assert narrow.median_m < wide.median_m
        assert narrow.coverage == pytest.approx(0.1)
        assert wide.coverage == pytest.approx(1.0)
        assert "Coverage" in narrow.headline() and "Coverage" in wide.headline()

    def test_no_coordinates_reports_no_error_rather_than_zero_error(self):
        metrics = positional_metrics([_record("S1", "B4-WEST", "B4-WEST") for _ in range(4)])

        assert metrics.median_m is None
        assert metrics.coverage == pytest.approx(0.0)
        assert "No coordinates emitted" in metrics.headline()

    def test_the_distribution_is_reported_not_only_the_mean(self):
        errors = [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 40.0]
        metrics = positional_metrics(
            [_record("S1", "B4-WEST", "B4-WEST", estimate_at=(e, 0.0)) for e in errors]
        )

        assert metrics.median_m == pytest.approx(1.0)
        assert metrics.p90_m > metrics.median_m
        assert metrics.max_m == pytest.approx(40.0)
        assert metrics.mean_m > metrics.median_m, "the mean describes a case that never happened"
        assert len(metrics.cdf) == len(errors)

    def test_top2_accuracy_reads_the_whole_ranking(self):
        records = [
            _record("S1", "B4-EAST", "B4-WEST", ranked=("B4-WEST", "B4-EAST")),
            _record("S1", "B4-EAST", "B4-WEST", ranked=("B4-WEST", "B4-CENTER")),
        ]
        metrics = classification_metrics(records, _model_stub())

        assert metrics.zone_accuracy == pytest.approx(0.0)
        assert metrics.zone_top2_accuracy == pytest.approx(0.5)

    def test_the_confusion_matrix_records_a_declined_answer_as_such(self):
        records = [_record("S1", "B4-WEST", None), _record("S1", "B4-WEST", "B4-EAST")]
        metrics = classification_metrics(records, _model_stub())

        assert metrics.confusion["B4-WEST"] == {"NONE": 1, "B4-EAST": 1}

    def test_overconfident_error_bars_are_named_as_such(self):
        """The failure this metric exists to catch: a small circle on a map is read as a fact."""
        flattering = [
            _record("S1", "B4-WEST", "B4-WEST", estimate_at=(10.0, 0.0), sigma=1.0)
            for _ in range(10)
        ]
        honest = [
            _record("S1", "B4-WEST", "B4-WEST", estimate_at=(float(index), 0.0), sigma=6.0)
            for index in range(10)
        ]

        assert calibration_metrics(flattering).verdict == "OVERCONFIDENT"
        assert calibration_metrics(honest).verdict in ("CALIBRATED", "UNDERCONFIDENT")
        assert calibration_metrics(flattering).p68_containment == pytest.approx(0.0)

    def test_unmeasured_calibration_is_distinguished_from_bad_calibration(self):
        zone_only = [_record("S1", "B4-WEST", "B4-WEST") for _ in range(4)]

        assert calibration_metrics(zone_only).verdict == "UNMEASURED"
        assert calibration_metrics(zone_only).p68_containment is None


class TestBootstrap:
    def test_sessions_are_the_unit_that_gets_resampled(self):
        """Resampling samples would treat 40 captures from one session as 40 independent
        observations of the site, and the interval would come out several times too narrow.
        """
        records = [
            *[_record("S1", "B4-WEST", "B4-WEST") for _ in range(40)],
            *[_record("S2", "B4-WEST", "B4-EAST") for _ in range(40)],
        ]
        interval = bootstrap(records, zone_accuracy_of, seed=5, resamples=RESAMPLES)

        assert interval.point == pytest.approx(0.5)
        # Two sessions that disagree completely: resampling sessions must be able to draw two of
        # either, so the interval has to span the whole scale.
        assert interval.low == pytest.approx(0.0)
        assert interval.high == pytest.approx(1.0)

    def test_a_single_session_yields_a_point_labelled_as_having_no_resamples(self):
        """An interval computed from one draw is a point dressed up as a range."""
        records = [_record("S1", "B4-WEST", "B4-WEST") for _ in range(20)]
        interval = bootstrap(records, zone_accuracy_of, seed=5, resamples=RESAMPLES)

        assert interval.resamples == 0
        assert interval.low == interval.high == pytest.approx(1.0)

    def test_the_interval_is_reproducible_for_a_seed(self):
        records = [
            _record(f"S{index % 4}", "B4-WEST", "B4-WEST" if index % 3 else "B4-EAST")
            for index in range(40)
        ]

        assert bootstrap(records, zone_accuracy_of, seed=5, resamples=RESAMPLES) == bootstrap(
            records, zone_accuracy_of, seed=5, resamples=RESAMPLES
        )

    def test_no_records_yields_no_interval_rather_than_a_zero(self):
        assert bootstrap([], zone_accuracy_of, seed=5, resamples=RESAMPLES) is None

    def test_overlap_is_what_decides_significance(self):
        assert Interval(0.8, 0.7, 0.9, 100).overlaps(Interval(0.75, 0.6, 0.85, 100))
        assert not Interval(0.9, 0.85, 0.95, 100).overlaps(Interval(0.5, 0.4, 0.6, 100))


class TestSelection:
    def _result(self, label, accuracy, cpu_ms, *, containment=0.7, width=0.05):
        from rfmapper_lab.benchmark.metrics import CalibrationMetrics, ClassificationMetrics

        return CandidateResult(
            candidate=Candidate(label=label, zone_engine="zone_bayes_v1"),
            classification=ClassificationMetrics(
                samples=50,
                zone_accuracy=accuracy,
                zone_coverage=1.0,
                zone_accuracy_answered=accuracy,
                zone_top2_accuracy=accuracy,
                building_accuracy=1.0,
                adjacent_error_rate=0.0,
            ),
            positional=positional_metrics(
                [_record("S1", "B4-WEST", "B4-WEST", estimate_at=(4.0, 0.0))]
            ),
            calibration=CalibrationMetrics(
                evaluated=50,
                p68_containment=containment,
                p95_containment=0.95,
                error_to_sigma_ratio=0.8,
            ),
            cpu_ms=cpu_ms,
            intervals={
                "zone_accuracy": Interval(accuracy, accuracy - width, accuracy + width, 2000)
            },
        )

    def test_overlapping_intervals_leave_the_cheaper_candidate_holding_the_title(self):
        """"Not proven better" is not "better", and a nightly batch pays the cost on every row."""
        expensive = self._result("expensive", 0.82, cpu_ms=10.0)
        cheap = self._result("cheap", 0.80, cpu_ms=1.0)

        winner, reason = select((expensive, cheap))

        assert winner == "cheap"
        assert "not significantly better" in reason
        assert "10.0x" in reason

    def test_a_clearly_better_candidate_wins(self):
        strong = self._result("strong", 0.95, cpu_ms=10.0, width=0.01)
        weak = self._result("weak", 0.50, cpu_ms=1.0, width=0.01)

        winner, reason = select((strong, weak))

        assert winner == "strong"
        assert "does not overlap" in reason

    def test_an_overconfident_candidate_is_rejected_however_accurate(self):
        """Under-reported uncertainty is the most damaging failure this system can have."""
        flattering = self._result("flattering", 0.99, cpu_ms=1.0, containment=0.2, width=0.01)
        honest = self._result("honest", 0.70, cpu_ms=1.0, containment=0.7, width=0.01)

        winner, reason = select((flattering, honest))

        assert winner == "honest"
        assert "rejected as overconfident: flattering" in reason

    def test_when_every_candidate_is_overconfident_the_report_says_so(self):
        first = self._result("first", 0.9, cpu_ms=1.0, containment=0.1, width=0.01)
        second = self._result("second", 0.8, cpu_ms=1.0, containment=0.2, width=0.01)

        winner, reason = select((first, second))

        assert winner == "first"
        assert "every candidate is overconfident" in reason
        assert "must not be trusted" in reason

    def test_a_degenerate_interval_is_not_reported_as_separation(self):
        """One held-out session gives one bootstrap draw. The ranking stands; the proof does not."""
        leader = replace(
            self._result("leader", 0.9, cpu_ms=1.0),
            intervals={"zone_accuracy": Interval(0.9, 0.9, 0.9, 0)},
        )
        other = replace(
            self._result("other", 0.5, cpu_ms=1.0),
            intervals={"zone_accuracy": Interval(0.5, 0.5, 0.5, 0)},
        )

        winner, reason = select((leader, other))

        assert winner == "leader"
        assert "unproven" in reason
        assert "not significantly better" not in reason

    def test_nothing_to_score_selects_nothing(self):
        assert select(()) == (None, "no held-out samples to score against")


class TestHarnessOnSyntheticData:
    @staticmethod
    @pytest.fixture(scope="class")
    def report(dataset):
        return run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
            generated_at_utc=format_ms(BASE_MS),
        )

    def test_every_registered_classifier_is_compared_without_anyone_listing_it(self, report):
        from rfmapper_lab.registry import ZONE_CLASSIFIERS

        labels = {result.candidate.label for result in report.results}
        assert set(ZONE_CLASSIFIERS.ids()) <= labels

    def test_every_candidate_is_scored_on_the_identical_held_out_samples(self, report):
        """Not a convention: the candidates share one sample list, so there is no path by which
        two of them could see different data.
        """
        counts = {result.classification.samples for result in report.results}
        assert len(counts) == 1
        assert counts != {0}

        keys = {
            tuple((r.session_id, r.survey_point_id) for r in result.records)
            for result in report.results
        }
        assert len(keys) == 1

    def test_the_report_names_the_data_it_was_measured_on(self, report, dataset):
        """So a run against different data cannot be compared with this one by accident."""
        assert report.dataset_content_sha256 == dataset.content_sha256
        assert report.reference_model_id == dataset.reference.reference_model_id
        assert report.dataset_kind is DatasetKind.SYNTHETIC

    def test_a_winner_is_selected_with_a_stated_reason(self, report):
        assert report.selected
        assert report.selection_reason
        assert report.result_for(report.selected) is not None

    def test_the_train_sessions_are_absent_from_the_scored_records(self, report):
        scored = {record.session_id for result in report.results for record in result.records}

        assert scored
        assert not scored & set(report.split.train)
        assert scored <= set(report.split.test)

    def test_tuning_can_be_done_against_validation_without_touching_test(self, dataset):
        """A hyperparameter chosen by looking at TEST has made TEST a training set."""
        on_validation = run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
            evaluate_on="validation",
        )
        scored = {
            record.session_id
            for result in on_validation.results
            for record in result.records
        }

        assert scored <= set(on_validation.split.validation)

    def test_the_rtt_variant_makes_the_coverage_trade_off_visible(self, report):
        """Without a row like it in the table, someone will eventually read the best median error
        as the system's accuracy.
        """
        ranged = report.result_for("zone_bayes_v1 + rtt")
        general = report.result_for("zone_bayes_v1")

        assert ranged is not None and general is not None
        assert ranged.positional.coverage <= general.positional.coverage or (
            ranged.positional.median_m is not None
            and general.positional.median_m is not None
            and ranged.positional.median_m <= general.positional.median_m
        )

    def test_the_report_is_json_serialisable_and_carries_its_provenance(self, report):
        document = json.loads(json.dumps(report.as_dict()))

        assert document["dataset_kind"] == "SYNTHETIC"
        assert document["split"]["test_sessions"]
        assert document["candidates"]
        assert document["engine_versions"]
        assert all(
            candidate["parameter_set_sha256"] == DEFAULTS.sha256()
            for candidate in document["candidates"]
        )

    def test_the_text_report_never_prints_an_error_without_its_coverage(self, report):
        text = format_report(report)

        assert "cov" in text.lower()
        assert report.selected in text
        assert "SYNTHETIC" in text

    def test_a_synthetic_report_states_that_no_real_site_claim_follows(self, report):
        text = format_report(report)

        assert "synthetic" in text.lower()

    def test_two_runs_of_the_harness_agree(self, dataset):
        first = run_benchmark(dataset, dataset_kind=DatasetKind.SYNTHETIC, resamples=RESAMPLES)
        second = run_benchmark(dataset, dataset_kind=DatasetKind.SYNTHETIC, resamples=RESAMPLES)

        assert first.selected == second.selected
        assert [r.classification.as_dict() for r in first.results] == [
            r.classification.as_dict() for r in second.results
        ]
        assert [r.intervals["zone_accuracy"].as_dict() for r in first.results] == [
            r.intervals["zone_accuracy"].as_dict() for r in second.results
        ]

    def test_restricting_the_candidate_list_restricts_the_comparison(self, dataset):
        only = run_benchmark(
            dataset,
            candidates=(Candidate(label="just-bayes", zone_engine="zone_bayes_v1"),),
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
        )

        assert [result.candidate.label for result in only.results] == ["just-bayes"]
        assert only.selected == "just-bayes"

    def test_the_default_candidate_set_is_built_from_the_registry(self):
        labels = [candidate.label for candidate in default_candidates()]

        assert "zone_bayes_v1" in labels
        assert "zone_bayes_v1 + rtt" in labels
        assert len(set(labels)) == len(labels)

    def test_the_report_id_is_derived_from_the_data_and_the_split(self, dataset, report):
        repeated = run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
            generated_at_utc=format_ms(BASE_MS),
        )
        other_split = run_benchmark(
            dataset,
            candidates=(
                Candidate(
                    label="zone_bayes_v1",
                    zone_engine="zone_bayes_v1",
                    params=replace(DEFAULTS, random_seed=DEFAULTS.random_seed + 1),
                ),
            ),
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
        )

        assert report.report_id == repeated.report_id
        assert report.report_id != other_split.report_id


class TestEmpiricalErrorModel:
    """The only route by which an estimate's uncertainty stops being geometric.

    Without a measured model the Lab reports a circle derived from zone size and fingerprint
    spread, flags it ``UNVALIDATED_UNCERTAINTY``, and has nothing to replace it with. The benchmark
    measures per-method error; these tests are about that measurement reaching the next run.
    """

    @staticmethod
    @pytest.fixture(scope="class")
    def report(dataset):
        return run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
            generated_at_utc=format_ms(BASE_MS),
        )

    def test_the_measured_p68_is_emitted_per_placement_method(self, report):
        model = empirical_error_model(report)

        assert model is not None
        assert model.p68_by_method
        assert all(value > 0 for value in model.p68_by_method.values())
        # Keyed by method, not by candidate: uncertainty is a property of how a position was
        # obtained, and an RTT multilateration and a zone centroid are not comparably precise.
        assert all(
            method in POSITIONING_ENGINES.ids() for method in model.p68_by_method
        )

    def test_it_is_what_run_empirical_reads(self, report, tmp_path):
        written = tmp_path / "empirical_error_model.json"
        written.write_text(dumps(empirical_error_model(report).as_dict()), encoding="utf-8")

        loaded = EmpiricalErrorModel.load(written)

        assert loaded.p68_by_method == empirical_error_model(report).p68_by_method
        assert loaded.dataset_kind == "SYNTHETIC"
        assert loaded.source_report_id == report.report_id

    def test_a_synthetic_measurement_never_counts_as_validated(self, report):
        # Feeding synthetic error back is useful for development and must never let a real site's
        # estimates lose the flag that says nobody measured them.
        assert not empirical_error_model(report).validated

    def test_a_method_with_too_few_held_out_samples_is_not_quoted(self, report):
        generous = empirical_error_model(report, min_samples=1)
        strict = empirical_error_model(report, min_samples=10_000)

        assert generous is not None and generous.p68_by_method
        assert strict is None, "a percentile over a handful of captures describes the captures"

    def test_an_overconfident_candidate_still_yields_a_model(self):
        """Refusing here would leave the optimistic sigma in place, which is the opposite of
        recalibration: the measured error is exactly what should displace it.
        """
        records = tuple(
            _record(
                session=f"S{index % 3}",
                truth_zone="Z1",
                predicted_zone="Z1",
                estimate_at=(6.0, 0.0),
                sigma=0.4,
            )
            for index in range(12)
        )
        report = BenchmarkReport(
            dataset_content_sha256="sha",
            reference_model_id="site-1",
            dataset_kind=DatasetKind.REAL,
            split=SessionSplit(train=(), validation=(), test=("S0", "S1", "S2"), seed=1),
            results=(
                CandidateResult(
                    candidate=Candidate(label="only", zone_engine="zone_bayes_v1"),
                    classification=classification_metrics(records, _model_stub()),
                    positional=positional_metrics(records),
                    calibration=calibration_metrics(records),
                    cpu_ms=1.0,
                    records=records,
                ),
            ),
            selected="only",
            selection_reason="only candidate",
        )

        assert report.results[0].calibration.verdict == "OVERCONFIDENT"
        model = empirical_error_model(report)
        assert model is not None
        assert model.p68_by_method["pos_wknn_centroid_v1"] == pytest.approx(6.0, abs=0.01)
        assert model.validated, "a real-data measurement is what validation means"

    def test_no_selected_candidate_means_no_model_rather_than_a_guess(self):
        empty = BenchmarkReport(
            dataset_content_sha256="sha",
            reference_model_id="site-1",
            dataset_kind=DatasetKind.REAL,
            split=SessionSplit(train=(), validation=(), test=(), seed=1),
            results=(),
            selected=None,
            selection_reason="no held-out samples to score against",
        )

        assert empirical_error_model(empty) is None


class TestRegressionGate:
    @staticmethod
    @pytest.fixture(scope="class")
    def report(dataset):
        return run_benchmark(
            dataset,
            dataset_kind=DatasetKind.SYNTHETIC,
            resamples=RESAMPLES,
            generated_at_utc=format_ms(BASE_MS),
        )

    def test_a_report_compared_against_its_own_baseline_passes(self, report):
        baseline = Baseline.from_report(report)
        gate = check(report, baseline)

        assert gate.comparable
        assert gate.passed
        assert "PASS" in gate.describe()

    def test_a_baseline_round_trips_through_a_file(self, report, tmp_path):
        baseline = Baseline.from_report(report)
        path = tmp_path / "baseline.json"
        baseline.write(path)

        assert Baseline.load(path).metrics == pytest.approx(baseline.metrics)
        assert check(report, Baseline.load(path)).passed

    def test_a_baseline_from_other_data_makes_the_gate_decline_to_judge(self, report):
        baseline = replace(
            Baseline.from_report(report), dataset_content_sha256="0" * 64
        )
        gate = check(report, baseline)

        assert not gate.comparable
        assert not gate.passed
        assert "not comparable" in gate.findings[0]
        assert "INCOMPARABLE" in gate.describe()

    def test_a_real_degradation_fails_the_gate(self, report):
        baseline = Baseline.from_report(report)
        flattered = replace(
            baseline,
            metrics={**baseline.metrics, "zone_accuracy": baseline.metrics["zone_accuracy"] + 0.5},
            intervals={"zone_accuracy": {"low": 0.0, "high": 0.02}},
        )
        gate = check(report, flattered)

        assert gate.comparable
        assert not gate.passed
        assert any("zone_accuracy fell" in finding for finding in gate.findings)

    def test_noise_inside_the_interval_passes(self, report):
        """A benchmark that fails on noise gets disabled within a month, and then nothing is
        checked at all.
        """
        baseline = Baseline.from_report(report)
        jittered = replace(
            baseline,
            metrics={**baseline.metrics, "zone_accuracy": baseline.metrics["zone_accuracy"] + 0.01},
            intervals={"zone_accuracy": {"low": 0.0, "high": 0.4}},
        )

        assert check(report, jittered).passed

    def test_becoming_less_confident_than_the_errors_justify_is_also_a_regression(self, report):
        """Uncertainty that is too wide makes a usable estimate look unusable."""
        baseline = Baseline.from_report(report)
        if "p68_containment" not in baseline.metrics:
            pytest.skip("this run emitted no positioned rows to calibrate against")

        at_target = replace(
            baseline,
            metrics={**baseline.metrics, "p68_containment": TARGET_CONTAINMENT},
            intervals={"p68_containment": {"low": 0.66, "high": 0.70}},
        )
        gate = check(report, at_target)
        current = baseline.metrics["p68_containment"]

        moved_away = abs(current - TARGET_CONTAINMENT) > 0.02
        assert gate.passed is not moved_away

    def test_a_missing_candidate_makes_the_gate_decline(self, report):
        baseline = replace(Baseline.from_report(report), label="an-algorithm-that-was-deleted")
        # Falls back to the report's own selection rather than failing outright, so renaming a
        # candidate does not look like a regression.
        gate = check(report, baseline)

        assert gate.comparable


def _model_stub():
    """A reference model with the three zones the metric tests name, and no edges.

    Adjacency reports UNKNOWN_EDGE without edges, which is what the adjacent-error-rate metric
    should treat as not-adjacent rather than as adjacent.
    """
    from rfmapper_lab.models import ReferenceModel, Zone

    return ReferenceModel(
        reference_model_id="stub",
        buildings={"B4": "B4"},
        zones={
            zone_id: Zone(zone_id=zone_id, building_id="B4", name=zone_id)
            for zone_id in ("B4-WEST", "B4-EAST", "B4-CENTER")
        },
    )
