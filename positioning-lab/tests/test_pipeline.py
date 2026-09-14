"""The whole pipeline, its invariants and its reproducibility.

Two kinds of test live here. The first asserts the invariants of
``docs/13-derived-output-schema.md`` across every row the pipeline produces on a realistic dataset,
which is the only way to catch a rule that holds in the unit tests and breaks on the combination.
The second asserts byte-identical reproduction, which ``docs/14-algorithm-versioning-strategy.md`` §5
requires and which is easy to lose to a set iteration, a wall-clock timestamp or an unsorted map.
"""

from __future__ import annotations

import json
import zipfile
from dataclasses import replace

import pytest

from rfmapper_lab.export import write_derived_package
from rfmapper_lab.jsonio import parse_checksum_file, sha256_hex
from rfmapper_lab.models import DatasetKind, PrecisionTier, ZoneEventType
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.pipeline import PipelineConfig, run_pipeline
from rfmapper_lab.timeutil import is_instant


@pytest.fixture(scope="module")
def run(dataset, config):
    return run_pipeline(dataset, config)


class TestPipelineProducesWork:
    def test_the_run_produces_estimates_for_every_enrolled_device(self, run, dataset):
        """A regression guard for the whole ingest-to-inference path.

        The two defects that cost the most during development both presented as this: the pipeline
        completed, reported no error, and silently produced nothing for an entire class of device.
        """
        estimated = {estimate.device_id for estimate in run.estimates}
        enrolled = set(dataset.reference.devices)

        assert enrolled, "the simulated site must enroll devices"
        assert enrolled <= estimated

    def test_the_run_reaches_every_tier_the_simulated_site_can_justify(self, run):
        """If a tier stops appearing, a strategy has started declining everything and the suite
        would otherwise keep passing on the tiers that remain.
        """
        tiers = set(run.tier_breakdown())

        assert PrecisionTier.ZONE.value in tiers or PrecisionTier.APPROXIMATE_POSITION.value in tiers
        assert PrecisionTier.PRECISION_RANGE.value in tiers, "RTT evidence must reach tier 5"

    def test_the_run_exercises_more_than_one_placement_method(self, run):
        methods = set(run.method_breakdown())

        assert len(methods) >= 2
        assert "pos_rtt_multilateration_v1" in methods

    def test_movement_and_transitions_are_produced(self, run):
        assert run.transitions
        assert run.movements
        assert any(t.event_type is ZoneEventType.RF_ZONE_ENTER for t in run.transitions)

    def test_the_quality_report_has_findings_on_synthetic_drift(self, run):
        """The simulator rotates observer bias between survey sessions, which is genuine drift and
        should be flagged rather than absorbed.
        """
        assert run.quality.flags
        assert all(flag.code for flag in run.quality.flags)


class TestDerivedInvariants:
    def test_no_coordinate_is_emitted_without_an_uncertainty(self, run):
        for estimate in run.estimates:
            if estimate.x is not None:
                assert estimate.horizontal_uncertainty_m is not None
                assert estimate.horizontal_uncertainty_m > 0

    def test_a_coordinate_implies_a_tier_that_may_carry_one(self, run):
        for estimate in run.estimates:
            assert (estimate.x is not None) == estimate.precision_tier.has_coordinates

    def test_the_ranged_tier_is_unreachable_without_ranging_evidence(self, run):
        """Invariant 4 of ``docs/13``: no amount of confident RSSI is promotable into tier 5."""
        ranged = [e for e in run.estimates if e.precision_tier is PrecisionTier.PRECISION_RANGE]

        assert ranged
        assert all(e.method == "pos_rtt_multilateration_v1" for e in ranged)

    def test_a_zone_claim_always_names_its_building(self, run):
        for estimate in run.estimates:
            if estimate.zone_id is not None:
                assert estimate.building_id is not None

    def test_site_presence_never_asserts_a_zone(self, run):
        for estimate in run.estimates:
            if estimate.precision_tier is PrecisionTier.SITE_PRESENCE:
                assert estimate.zone_id is None

    def test_every_estimate_cites_the_raw_evidence_it_rests_on(self, run):
        """Traceability is what the Master's import gate checks, and an estimate that cannot name
        its observations is not admissible.
        """
        known = {observation.observation_id for observation in run.dataset.observations}

        for estimate in run.estimates:
            assert estimate.supporting_observation_ids
            assert set(estimate.supporting_observation_ids) <= known
            assert estimate.supporting_observer_ids

    def test_every_estimate_cites_the_reference_model_and_parameter_set(self, run, dataset):
        for estimate in run.estimates:
            assert estimate.reference_model_id == dataset.reference.reference_model_id
            assert estimate.parameter_set_sha256 == DEFAULTS.sha256()
            assert estimate.algorithm_version
            assert estimate.engine_versions.get("zone_engine_id") == run.config.zone_engine

    def test_confidence_is_a_product_of_named_inspectable_factors(self, run):
        for estimate in run.estimates:
            assert 0.0 <= estimate.confidence <= 1.0
            assert estimate.confidence_factors
            assert all(value >= 0.0 for value in estimate.confidence_factors.values())

    def test_a_synthetic_run_marks_every_row_as_synthetic(self, run):
        """No accuracy claim about a real site may be traced to simulated data by accident."""
        for estimate in run.estimates:
            assert "DATASET_SYNTHETIC" in estimate.quality_flags

    def test_every_timestamp_is_in_the_canonical_form(self, run):
        for estimate in run.estimates:
            assert is_instant(estimate.timestamp_utc)
            assert is_instant(estimate.computed_at_utc)
        for transition in run.transitions:
            assert is_instant(transition.transition_start_utc)
            assert is_instant(transition.transition_confirmed_utc)

    def test_a_transition_cites_estimates_the_run_actually_emitted(self, run):
        emitted = {estimate.estimate_id for estimate in run.estimates}

        for transition in run.transitions:
            cited = set(transition.supporting_estimate_ids) - {"lost"}
            assert cited <= emitted

    def test_rows_leave_in_a_stable_order(self, run):
        assert list(run.estimates) == sorted(
            run.estimates, key=lambda e: (e.timestamp_utc, e.estimate_id)
        )
        assert list(run.transitions) == sorted(
            run.transitions, key=lambda t: (t.transition_confirmed_utc, t.transition_id)
        )

    def test_an_unvalidated_site_says_so_on_every_positioned_row(self, run):
        """No benchmark has supplied an empirical error term for this run, so no uncertainty here
        may be presented as measured.
        """
        for estimate in run.estimates:
            if estimate.horizontal_uncertainty_m is not None:
                assert "UNVALIDATED_UNCERTAINTY" in estimate.quality_flags


class TestDeterminism:
    def test_two_runs_over_the_same_inputs_agree_row_for_row(self, dataset, config):
        first = run_pipeline(dataset, config)
        second = run_pipeline(dataset, config)

        assert first.estimates == second.estimates
        assert first.transitions == second.transitions
        assert first.movements == second.movements

    def test_record_ids_are_derived_from_content_not_generated(self, dataset, config):
        """``uuid4`` would defeat the Master's idempotent import, which keys on ``estimate_id``."""
        first = run_pipeline(dataset, config)
        second = run_pipeline(dataset, config)

        assert [e.estimate_id for e in first.estimates] == [
            e.estimate_id for e in second.estimates
        ]
        assert len({e.estimate_id for e in first.estimates}) == len(first.estimates)

    def test_reordering_the_input_rows_changes_nothing(self, dataset, config):
        """Packages arrive in whatever order the operator copied them, and deduplication sorts
        the result, so the pipeline must not depend on file order.
        """
        shuffled = replace(
            dataset,
            observations=tuple(
                sorted(dataset.observations, key=lambda o: o.observation_id)
            ),
        )

        assert run_pipeline(shuffled, config).estimates == run_pipeline(dataset, config).estimates

    def test_a_changed_parameter_produces_a_different_algorithm_fingerprint(self, dataset, config):
        """A quiet change to a threshold must not be able to masquerade as the same algorithm."""
        tweaked = replace(DEFAULTS, zone=replace(DEFAULTS.zone, mismatch_penalty=9.0))

        assert tweaked.sha256() != DEFAULTS.sha256()
        altered = run_pipeline(dataset, replace(config, params=tweaked))
        assert altered.estimates[0].parameter_set_sha256 == tweaked.sha256()

    def test_a_different_zone_engine_is_recorded_on_the_row(self, dataset, config):
        alternative = run_pipeline(dataset, config.with_engine("zone_nn_v1"))

        assert all(
            e.engine_versions["zone_engine_id"] == "zone_nn_v1" for e in alternative.estimates
        )


class TestDerivedPackage:
    def test_the_package_round_trips_and_names_every_entry_in_its_checksum(self, run, tmp_path):
        package = write_derived_package(run, tmp_path)

        with zipfile.ZipFile(package.path) as archive:
            entries = {info.filename: archive.read(info) for info in archive.infolist()}

        declared = parse_checksum_file(entries["checksum.txt"].decode("utf-8"))
        assert set(declared) == set(entries) - {"checksum.txt"}
        for name, digest in declared.items():
            assert sha256_hex(entries[name]) == digest

    def test_writing_the_same_run_twice_produces_identical_bytes(self, run, tmp_path):
        first = write_derived_package(run, tmp_path / "a")
        second = write_derived_package(run, tmp_path / "b")

        assert first.package_sha256 == second.package_sha256
        assert first.entry_digests == second.entry_digests

    def test_the_manifest_carries_what_the_master_needs_to_accept_the_package(self, run, tmp_path):
        package = write_derived_package(run, tmp_path)
        with zipfile.ZipFile(package.path) as archive:
            manifest = json.loads(archive.read("manifest.json"))

        assert manifest["package_type"] == "DERIVED"
        assert manifest["algorithm_version"] == run.config.algorithm_version
        assert manifest["parameter_set_sha256"] == run.config.params.sha256()
        assert manifest["reference_model_id"] == run.dataset.reference.reference_model_id
        assert manifest["dataset_kind"] == DatasetKind.SYNTHETIC.value
        # The traceability gate: a package citing data the Master never imported is refused.
        assert manifest["source_dataset_ids"] == list(run.dataset.source_dataset_ids)
        assert manifest["counts"]["position_estimates"] == len(run.estimates)

    def test_an_empty_date_range_is_absent_rather_than_a_pair_of_nulls(self, dataset, config, tmp_path):
        """The Kotlin model declares the bounds non-null inside an optional range, so a
        half-populated object fails to decode on the Master.
        """
        empty = replace(dataset, observations=())
        package = write_derived_package(run_pipeline(empty, config), tmp_path)

        with zipfile.ZipFile(package.path) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        assert manifest["date_range"] is None

    def test_the_csv_and_json_views_carry_the_same_rows(self, run, tmp_path):
        package = write_derived_package(run, tmp_path)
        with zipfile.ZipFile(package.path) as archive:
            rows = json.loads(archive.read("position_estimates.json"))
            csv_text = archive.read("position_estimates.csv").decode("utf-8")

        csv_lines = [line for line in csv_text.splitlines() if line]
        assert len(csv_lines) == len(rows) + 1
        assert len(rows) == len(run.estimates)

    def test_a_package_without_a_benchmark_says_no_accuracy_was_measured(self, run, tmp_path):
        """The absence of validation is stated rather than left looking like an oversight."""
        package = write_derived_package(run, tmp_path)
        with zipfile.ZipFile(package.path) as archive:
            report = json.loads(archive.read("algorithm_report.json"))

        assert report["benchmark"] is None
        assert "No held-out accuracy was measured" in report["accuracy_claim"]
        assert report["parameter_set"]["zone"]["mismatch_penalty"] == DEFAULTS.zone.mismatch_penalty

    def test_an_embedded_benchmark_report_carries_no_host_dependent_timing(self, run, tmp_path):
        """A median CPU time differs between runs on the same data, and sealing one into the
        package would change its checksum for a reason that has nothing to do with a conclusion.
        The timing stays in the standalone report the ``benchmark`` command writes.
        """
        report = {
            "report_id": "r-1",
            "selected": "zone_bayes_v1",
            "candidates": [
                {"label": "zone_bayes_v1", "median_cpu_ms": 1.9268, "classification": {}},
                {"label": "zone_nn_v1", "median_cpu_ms": 1.7562, "classification": {}},
            ],
        }
        slower = {
            **report,
            "candidates": [
                {**candidate, "median_cpu_ms": candidate["median_cpu_ms"] + 0.5}
                for candidate in report["candidates"]
            ],
        }

        first = write_derived_package(run, tmp_path / "a", algorithm_report=report)
        second = write_derived_package(run, tmp_path / "b", algorithm_report=slower)

        with zipfile.ZipFile(first.path) as archive:
            embedded = json.loads(archive.read("algorithm_report.json"))
        assert [c["label"] for c in embedded["candidates"]] == ["zone_bayes_v1", "zone_nn_v1"]
        assert all("median_cpu_ms" not in candidate for candidate in embedded["candidates"])
        assert embedded["selected"] == "zone_bayes_v1"
        assert first.package_sha256 == second.package_sha256

    def test_the_quality_report_travels_with_the_package(self, run, tmp_path):
        package = write_derived_package(run, tmp_path)
        with zipfile.ZipFile(package.path) as archive:
            quality = json.loads(archive.read("quality_report.json"))

        assert quality["flags"]
        assert all(isinstance(flag["code"], str) for flag in quality["flags"])
        assert all(
            isinstance(value, str)
            for flag in quality["flags"]
            for value in (flag.get("evidence") or {}).values()
        ), "evidence is Map<String, String> on the Kotlin side"


class TestPipelineBoundaries:
    def test_a_survey_capture_is_never_fed_back_through_the_classifier(self, dataset, config):
        """A fingerprint validating itself would produce accuracy figures that are meaningless in
        a way that is easy to mistake for success.
        """
        run = run_pipeline(dataset, config)
        survey_ids = {row.observation_id for row in dataset.ground_truth()}

        assert survey_ids, "the simulated site must contain survey captures"
        for estimate in run.estimates:
            assert not (set(estimate.supporting_observation_ids) & survey_ids)

    def test_a_dataset_with_no_ordinary_rows_produces_no_estimates(self, dataset, config):
        survey_only = replace(dataset, observations=dataset.ground_truth())
        run = run_pipeline(survey_only, config)

        assert run.estimates == ()
        assert run.fingerprints.points, "fingerprints are still built from the survey captures"

    def test_an_empty_dataset_completes_rather_than_raising(self, dataset, config):
        """The Lab runs unattended on a schedule, so a day with no packages is an ordinary
        outcome that must produce an empty generation, not a stack trace.
        """
        run = run_pipeline(replace(dataset, observations=()), config)

        assert run.estimates == ()
        assert run.transitions == ()
        assert run.counts()["position_estimates"] == 0

    def test_disabling_the_temporal_filter_changes_the_track_but_not_its_shape(
        self, dataset, config
    ):
        filtered = run_pipeline(dataset, replace(config, enable_temporal_filter=True))
        raw = run_pipeline(dataset, replace(config, enable_temporal_filter=False))

        assert len(filtered.estimates) == len(raw.estimates)
        assert any("TEMPORAL_MEDIAN_FILTERED" in e.quality_flags for e in filtered.estimates)
        assert all("TEMPORAL_MEDIAN_FILTERED" not in e.quality_flags for e in raw.estimates)

    def test_a_reprocess_of_a_sub_range_reuses_the_windows_of_the_whole_day(self, dataset, config):
        """Reprocessing a slice must not renumber the estimates that overlap the full run, or the
        Master's import would duplicate them instead of recognising them.
        """
        whole = run_pipeline(dataset, config)
        span = dataset.time_range()
        midpoint = (span[0] + span[1]) // 2
        slice_run = run_pipeline(dataset.within(midpoint, span[1]), config)

        whole_ids = {e.estimate_id for e in whole.estimates}
        overlapping = [
            e for e in slice_run.estimates if e.device_id in {x.device_id for x in whole.estimates}
        ]
        assert overlapping
        assert sum(1 for e in overlapping if e.estimate_id in whole_ids) > 0
