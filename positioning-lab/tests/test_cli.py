"""The command line, which is the whole Lab as an administrator meets it.

These are end-to-end tests: a synthetic site is written to disk as real packages and a real site
model, and the commands are invoked exactly as they would be from a shell. That is deliberate, and
it is the only place in the suite where Phase 1 runs against files rather than objects — every other
test hands the pipeline a dataset, which skips the zip, the checksums and the CSV cross-check.

They also pin the refusals. A reprocess against the wrong site model, an unknown engine, a run with
no usable rows: each has to fail with a non-zero status and an explanation, because these commands
run unattended from a scheduler where a silent success is indistinguishable from a real one.
"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from rfmapper_lab.cli import main
from rfmapper_lab.jsonio import dumps
from rfmapper_lab.simulator import write_packages
from rfmapper_lab.timeutil import date_stamp


@pytest.fixture(scope="module")
def workspace(site, tmp_path_factory) -> Path:
    """A directory laid out the way the commands expect to find one."""
    root = tmp_path_factory.mktemp("cli")
    (root / "site_model.json").write_text(dumps(site.reference_document), encoding="utf-8")
    write_packages(site.observations, root / "raw")
    return root


def _inputs(workspace: Path) -> list[str]:
    return [
        "--raw",
        str(workspace / "raw"),
        "--site-model",
        str(workspace / "site_model.json"),
    ]


def _derived_package(directory: Path) -> Path:
    packages = sorted(directory.glob("DERIVED_*.zip"))
    assert packages, f"no derived package written to {directory}"
    return packages[0]


class TestRun:
    def test_the_nightly_job_writes_one_derived_package(self, workspace, tmp_path, capsys):
        out = tmp_path / "derived"
        status = main([*(["run"] + _inputs(workspace)), "--out", str(out), "--dataset-kind", "SYNTHETIC"])
        printed = capsys.readouterr().out

        assert status == 0
        package = _derived_package(out)
        with zipfile.ZipFile(package) as archive:
            names = set(archive.namelist())

        assert "manifest.json" in names
        assert "position_estimates.json" in names
        assert "checksum.txt" in names
        assert "rows accepted" in printed
        assert str(package) in printed

    def test_the_run_states_that_its_uncertainty_is_unvalidated(self, workspace, tmp_path, capsys):
        """The command that produces the numbers is the right place to say they are not validated,
        because that is where somebody is looking when they decide to quote one.
        """
        main([*(["run"] + _inputs(workspace)), "--out", str(tmp_path / "d"), "--dataset-kind", "SYNTHETIC"])
        printed = capsys.readouterr().out

        assert "UNVALIDATED_UNCERTAINTY" in printed
        assert "before quoting any accuracy figure" in printed

    def test_two_runs_over_the_same_packages_produce_identical_bytes(self, workspace, tmp_path):
        """The clock comes from the data, not the wall, so a rerun is byte-identical."""
        first_dir, second_dir = tmp_path / "one", tmp_path / "two"
        for destination in (first_dir, second_dir):
            main([*(["run"] + _inputs(workspace)), "--out", str(destination), "--dataset-kind", "SYNTHETIC"])

        assert _derived_package(first_dir).read_bytes() == _derived_package(second_dir).read_bytes()

    def test_the_package_is_named_for_the_day_it_describes(self, workspace, tmp_path, site):
        main([*(["run"] + _inputs(workspace)), "--out", str(tmp_path / "d"), "--dataset-kind", "SYNTHETIC"])
        first_day = date_stamp(min(o.timestamp_ms for o in site.observations))

        assert _derived_package(tmp_path / "d").name == f"DERIVED_{first_day}.zip"

    def test_an_unknown_zone_engine_is_refused_by_name(self, workspace, tmp_path, capsys):
        status = main(
            [*(["run"] + _inputs(workspace)), "--out", str(tmp_path / "d"), "--zone-engine", "zone_vibes_v1"]
        )
        captured = capsys.readouterr()

        assert status == 1
        assert "unknown zone engine" in captured.err
        assert "zone_bayes_v1" in captured.err, "the error must list what is available"

    def test_an_unknown_positioning_engine_is_refused(self, workspace, tmp_path, capsys):
        status = main(
            [
                *(["run"] + _inputs(workspace)),
                "--out",
                str(tmp_path / "d"),
                "--positioning-engine",
                "pos_wishful_thinking_v1",
            ]
        )

        assert status == 1
        assert "unknown positioning engine" in capsys.readouterr().err

    def test_a_missing_input_path_is_refused_rather_than_treated_as_empty(self, workspace, tmp_path, capsys):
        status = main(
            [
                "run",
                "--raw",
                str(tmp_path / "nowhere"),
                "--site-model",
                str(workspace / "site_model.json"),
                "--out",
                str(tmp_path / "d"),
            ]
        )

        assert status == 1
        assert "does not exist" in capsys.readouterr().err

    def test_a_directory_with_no_packages_is_refused(self, workspace, tmp_path, capsys):
        empty = tmp_path / "empty"
        empty.mkdir()
        status = main(
            [
                "run",
                "--raw",
                str(empty),
                "--site-model",
                str(workspace / "site_model.json"),
                "--out",
                str(tmp_path / "d"),
            ]
        )

        assert status == 1
        assert "no .zip packages found" in capsys.readouterr().err

    def test_a_broken_site_model_stops_the_run_before_it_computes(self, workspace, tmp_path, capsys):
        broken = tmp_path / "broken.json"
        broken.write_text(json.dumps({"zones": []}), encoding="utf-8")
        status = main(
            [
                "run",
                "--raw",
                str(workspace / "raw"),
                "--site-model",
                str(broken),
                "--out",
                str(tmp_path / "d"),
            ]
        )

        assert status == 1
        assert "reference_model_id" in capsys.readouterr().err
        assert not (tmp_path / "d").exists()

    def test_an_explicit_computed_at_overrides_the_data_clock(self, workspace, tmp_path):
        out = tmp_path / "stamped"
        main(
            [
                *(["run"] + _inputs(workspace)),
                "--out",
                str(out),
                "--dataset-kind",
                "SYNTHETIC",
                "--computed-at",
                "2030-01-01T00:00:00.000Z",
            ]
        )
        with zipfile.ZipFile(_derived_package(out)) as archive:
            manifest = json.loads(archive.read("manifest.json"))

        assert manifest["created_at"] == "2030-01-01T00:00:00.000Z"


class TestReprocess:
    def test_a_pinned_reprocess_writes_a_new_generation(self, workspace, tmp_path, site, capsys):
        out = tmp_path / "reprocessed"
        stamps = sorted({date_stamp(o.timestamp_ms) for o in site.observations})
        status = main(
            [
                *(["reprocess"] + _inputs(workspace)),
                "--from",
                stamps[0],
                "--to",
                stamps[-1],
                "--reference",
                site.reference_document["reference_model_id"],
                "--zone-engine",
                "zone_nn_v1",
                "--algorithm-version",
                "pinned-9.9.9",
                "--dataset-kind",
                "SYNTHETIC",
                "--out",
                str(out),
            ]
        )
        printed = capsys.readouterr().out

        assert status == 0
        assert "no existing generation was read or modified" in printed
        with zipfile.ZipFile(_derived_package(out)) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        assert manifest["algorithm_version"] == "pinned-9.9.9"
        assert manifest["engine_versions"]["zone_engine_id"] == "zone_nn_v1"

    def test_the_wrong_site_model_stops_the_reprocess(self, workspace, tmp_path, site, capsys):
        """Reproducing a historical run needs the site model of the day; re-judging the same data
        under today's model is a different question and has to be asked explicitly.
        """
        stamps = sorted({date_stamp(o.timestamp_ms) for o in site.observations})
        status = main(
            [
                *(["reprocess"] + _inputs(workspace)),
                "--from",
                stamps[0],
                "--to",
                stamps[-1],
                "--reference",
                "some-other-site-2024",
                "--out",
                str(tmp_path / "d"),
            ]
        )

        assert status == 1
        assert "site model declares" in capsys.readouterr().err

    def test_a_range_with_no_observations_is_reported_rather_than_written_empty(
        self, workspace, tmp_path, site, capsys
    ):
        status = main(
            [
                *(["reprocess"] + _inputs(workspace)),
                "--from",
                "2019-01-01",
                "--to",
                "2019-01-02",
                "--reference",
                site.reference_document["reference_model_id"],
                "--out",
                str(tmp_path / "d"),
            ]
        )

        assert status == 1
        assert "no observations in that range" in capsys.readouterr().err


class TestBenchmark:
    def test_the_benchmark_prints_the_comparison_table(self, workspace, capsys):
        status = main([*(["benchmark"] + _inputs(workspace)), "--resamples", "60"])
        printed = capsys.readouterr().out

        assert status == 0
        assert "zone_bayes_v1" in printed
        assert "TRAIN=" in printed
        assert "cov" in printed.lower()

    def test_a_simulated_site_cannot_be_reported_as_real(self, workspace, capsys):
        """The dataset kind is read from the site model's own id, so nobody has to remember a
        flag for the run not to be quoted as a real-site result.
        """
        main([*(["benchmark"] + _inputs(workspace)), "--resamples", "60"])
        printed = capsys.readouterr().out

        assert "SYNTHETIC" in printed

    def test_the_report_can_be_written_for_the_record(self, workspace, tmp_path):
        out = tmp_path / "report"
        main([*(["benchmark"] + _inputs(workspace)), "--resamples", "60", "--out", str(out)])
        document = json.loads((out / "algorithm_report.json").read_text(encoding="utf-8"))

        assert document["candidates"]
        assert document["split"]["test_sessions"]
        assert document["selected"]

    def test_the_gate_passes_against_a_baseline_it_just_wrote(self, workspace, tmp_path, capsys):
        baseline = tmp_path / "baseline.json"
        main(
            [
                *(["benchmark"] + _inputs(workspace)),
                "--resamples",
                "60",
                "--update-baseline",
                str(baseline),
            ]
        )
        assert baseline.exists()
        capsys.readouterr()

        status = main(
            [*(["benchmark"] + _inputs(workspace)), "--resamples", "60", "--baseline", str(baseline)]
        )
        printed = capsys.readouterr().out

        assert status == 0
        assert "Regression gate: PASS" in printed

    def test_the_gate_fails_the_command_against_an_incomparable_baseline(
        self, workspace, tmp_path, capsys
    ):
        """A failing exit status is the point: this runs in CI, where only the status is read."""
        baseline = tmp_path / "foreign.json"
        baseline.write_text(
            json.dumps(
                {
                    "label": "zone_bayes_v1",
                    "dataset_content_sha256": "0" * 64,
                    "metrics": {"zone_accuracy": 0.9},
                    "intervals": {},
                }
            ),
            encoding="utf-8",
        )

        status = main(
            [*(["benchmark"] + _inputs(workspace)), "--resamples", "60", "--baseline", str(baseline)]
        )
        printed = capsys.readouterr().out

        assert status == 1
        assert "INCOMPARABLE" in printed

    def test_tuning_against_validation_is_a_flag_rather_than_a_habit(self, workspace, capsys):
        status = main(
            [*(["benchmark"] + _inputs(workspace)), "--resamples", "60", "--evaluate-on", "validation"]
        )

        assert status == 0
        assert "TRAIN=" in capsys.readouterr().out


class TestValidate:
    def test_validate_reads_the_inputs_and_computes_nothing(self, workspace, tmp_path, capsys):
        status = main(["validate", *_inputs(workspace)])
        printed = capsys.readouterr().out

        assert status == 0
        assert "Inputs are readable. Nothing was computed." in printed
        assert "Site model" in printed
        assert not list(tmp_path.glob("**/DERIVED_*.zip"))

    def test_validate_names_the_zones_with_no_ground_truth(self, workspace, capsys):
        """The simulator leaves one zone uncalibrated on purpose, and an operator needs to be told
        which one rather than discovering it as a gap in tomorrow's output.
        """
        main(["validate", *_inputs(workspace)])
        printed = capsys.readouterr().out

        assert "Zones with no ground truth" in printed

    def test_validate_fails_when_a_package_is_unusable(self, workspace, tmp_path, capsys):
        raw = tmp_path / "raw-with-a-bad-one"
        raw.mkdir()
        for package in sorted((workspace / "raw").glob("*.zip")):
            (raw / package.name).write_bytes(package.read_bytes())
        (raw / "OBSBROKEN_2026-09-14.zip").write_bytes(b"not a zip at all")

        status = main(
            ["validate", "--raw", str(raw), "--site-model", str(workspace / "site_model.json")]
        )
        printed = capsys.readouterr().out

        assert status == 1
        assert "blocking package problem" in printed
        assert "OBSBROKEN_2026-09-14" in printed


class TestDemo:
    def test_the_demo_runs_the_whole_system_over_a_site_it_invents(self, tmp_path, capsys):
        """The one test that covers ingest, inference, benchmark and export in one invocation.

        Kept small — two buildings, three sessions — because its value is in the wiring rather
        than in the numbers, and the numbers are the simulator's anyway.
        """
        out = tmp_path / "demo"
        status = main(
            [
                "demo",
                "--out",
                str(out),
                "--buildings",
                "2",
                "--zones-per-building",
                "3",
                "--survey-sessions",
                "3",
                "--walk-steps",
                "20",
                "--resamples",
                "40",
            ]
        )
        printed = capsys.readouterr().out

        assert status == 0
        assert (out / "site_model.json").exists()
        assert list((out / "raw").glob("OBS*.zip"))
        assert (out / "algorithm_report.json").exists()
        assert _derived_package(out / "derived").exists()

        assert "Ingest:" in printed
        assert "0 invalid" in printed, "the simulator's packages must read back cleanly"
        assert "Pipeline:" in printed
        assert "This is synthetic data" in printed

    def test_the_demo_is_reproducible_for_a_seed(self, tmp_path):
        first, second = tmp_path / "a", tmp_path / "b"
        for destination in (first, second):
            main(
                [
                    "demo",
                    "--out",
                    str(destination),
                    "--buildings",
                    "2",
                    "--zones-per-building",
                    "2",
                    "--survey-sessions",
                    "3",
                    "--walk-steps",
                    "10",
                    "--skip-benchmark",
                ]
            )

        assert (
            _derived_package(first / "derived").read_bytes()
            == _derived_package(second / "derived").read_bytes()
        )

    def test_a_different_seed_produces_a_different_site(self, tmp_path):
        for seed, destination in ((1, tmp_path / "s1"), (2, tmp_path / "s2")):
            main(
                [
                    "demo",
                    "--out",
                    str(destination),
                    "--seed",
                    str(seed),
                    "--buildings",
                    "2",
                    "--zones-per-building",
                    "2",
                    "--survey-sessions",
                    "3",
                    "--walk-steps",
                    "10",
                    "--skip-benchmark",
                ]
            )

        assert (
            _derived_package(tmp_path / "s1" / "derived").read_bytes()
            != _derived_package(tmp_path / "s2" / "derived").read_bytes()
        )


class TestInvocation:
    def test_no_arguments_prints_help_rather_than_guessing(self, capsys):
        assert main([]) == 2
        assert "usage" in capsys.readouterr().out

    def test_the_version_is_reportable(self, capsys):
        with pytest.raises(SystemExit) as exit_info:
            main(["--version"])

        assert exit_info.value.code == 0
        assert "rfmapper-lab" in capsys.readouterr().out
