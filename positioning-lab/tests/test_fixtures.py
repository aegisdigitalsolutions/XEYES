"""The reading half of the cross-language contract.

The Collector, the Master and the Lab never share a process, so no test can verify their file
contracts by calling one from the other. What can be verified is the artefact. The Kotlin suite
(``data-room/.../contract/ContractFixtureTest.kt``) drives the real export paths over one small
synthetic site and commits the result under ``contract/``; this module reads exactly those files,
with no knowledge of how they were produced beyond the specifications in ``docs/``.

The value is in what fails. If the Kotlin CSV encoder starts quoting differently, or the canonical
JSON drops an explicit null, or the Master's site-model export stops carrying zone polygons, a test
here goes red — in this repository, on a laptop, rather than on the night the pipeline first runs
against a week of real field work.

The derived package is the return leg: this module produces ``contract/DERIVED_2026-05-04.zip`` from
those inputs, and the Kotlin suite imports it. Rewrite it with
``pytest tests/test_fixtures.py --rfmapper-write-contract`` and read the diff, because the Master's
importer is on the other end of it.
"""

from __future__ import annotations

import io
import json
import zipfile
from pathlib import Path

import pytest

from rfmapper_lab.export.derived_package import write_derived_package
from rfmapper_lab.models import DatasetKind, IdentifierType, SensorType
from rfmapper_lab.parsing import load_dataset, load_packages, load_reference_model
from rfmapper_lab.parsing.package_reader import read_package
from rfmapper_lab.pipeline import PipelineConfig, run_pipeline

#: The scenario, restated here rather than imported, because there is nothing to import it from.
#: Duplicating the expected values is the price of a contract test that does not share code with the
#: producer — and it is the point: a constant that has to be changed on both sides is a change
#: somebody has to notice.
REFERENCE_MODEL_ID = "site-contract-2026-05-04"
REGISTRY_ID = "registry-contract-2026-05-04"
BUILDING = "B1"
ZONE_NORTH = "B1-NORTH"
ZONE_SOUTH = "B1-SOUTH"
TAG_DEVICE = "DEVICE-TAG-1"
TAG_BLE = "d0:d0:d0:00:00:0a"
TAG_SERVICE_UUID = "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162"
RETIRED_DEVICE = "DEVICE-RETIRED-1"
OBSERVER_NORTH = "OBS-C1"
OBSERVER_SOUTH = "OBS-C2"
SURVEYOR = "OBS-C3"
AP_RTT = "aa:bb:cc:00:00:03"

PACKAGE_NAMES = (
    "RFMapper_OBSC1_2026-05-04.zip",
    "RFMapper_OBSC2_2026-05-04.zip",
    "RFMapper_OBSC3_2026-05-04.zip",
    "RFMapper_OBSC3_2026-05-05.zip",
    "RFMapper_OBSC3_2026-05-06.zip",
)

TOTAL_ROWS = 2 * 36 + 3 * 2 * 8 * 5
SURVEY_ROWS = 3 * 2 * 8 * 5

DERIVED_PACKAGE = "DERIVED_2026-05-04.zip"


# -- fixtures -------------------------------------------------------------------------------------


@pytest.fixture(scope="session")
def fixture_paths(contract_dir: Path) -> tuple[Path, ...]:
    missing = [name for name in PACKAGE_NAMES if not (contract_dir / name).is_file()]
    if missing or not (contract_dir / "site_model.json").is_file():
        pytest.fail(
            f"the Kotlin-produced fixtures are missing from {contract_dir} ({missing}). "
            "Regenerate them with `./gradlew :data-room:testDebugUnitTest "
            "-Drfmapper.contract.write=true` in android/."
        )
    return tuple(contract_dir / name for name in PACKAGE_NAMES)


@pytest.fixture(scope="session")
def reference(contract_dir: Path):
    return load_reference_model(contract_dir / "site_model.json", contract_dir / "devices.json")


@pytest.fixture(scope="session")
def packages(fixture_paths):
    return load_packages(list(fixture_paths))


@pytest.fixture(scope="session")
def fixture_dataset(packages, reference):
    return load_dataset(packages, reference)


# -- REFERENCE ------------------------------------------------------------------------------------


class TestSiteModel:
    def test_the_master_export_is_readable_and_complete(self, reference):
        assert reference.reference_model_id == REFERENCE_MODEL_ID
        assert set(reference.buildings) == {BUILDING}
        assert set(reference.zones) == {ZONE_NORTH, ZONE_SOUTH}
        assert len(reference.infrastructure) == 5
        assert set(reference.observers) == {OBSERVER_NORTH, OBSERVER_SOUTH, SURVEYOR}
        assert len(reference.survey_points) == 2

    def test_zone_geometry_survives_the_crossing(self, reference):
        north = reference.zones[ZONE_NORTH]
        assert [(p.x, p.y) for p in north.polygon] == [
            (0.0, 0.0),
            (10.0, 0.0),
            (10.0, 7.0),
            (0.0, 7.0),
        ]
        # Derived by the Master when it stored the polygon; the Lab reads rather than recomputes,
        # so a disagreement about a centroid would put every zone-centroid estimate off by it.
        assert north.centroid_x == pytest.approx(5.0)
        assert north.centroid_y == pytest.approx(3.5)

    def test_topology_is_carried_rather_than_inferred_from_geometry(self, reference):
        assert reference.adjacency(ZONE_NORTH, ZONE_SOUTH).value == "ADJACENT"
        edge = next(e for e in reference.edges if e.from_zone_id == ZONE_NORTH)
        assert edge.edge_type == "DOOR"
        assert edge.typical_traversal_s == pytest.approx(4.0)

    def test_collector_phones_are_registered_as_sources(self, reference):
        # This is the hinge of the whole comparison: a fingerprint records what was heard *at* a
        # point, while a target device is only ever heard *by* an observer. Without the observer's
        # own advertised identifier in the site model the two cannot be related, and every estimate
        # would fall back to a coarser tier for a reason no operator could see.
        assert reference.source_for_observer(OBSERVER_NORTH) == "c1:c1:c1:00:00:01"
        assert reference.source_for_observer(OBSERVER_SOUTH) == "c2:c2:c2:00:00:02"
        assert reference.source_for_observer(SURVEYOR) is None

    def test_the_rtt_anchor_has_the_coordinates_a_range_needs(self, reference):
        anchor = next(n for n in reference.infrastructure.values() if n.type == "RTT_ANCHOR")
        assert anchor.rtt_capable
        assert anchor.is_located_anchor
        assert anchor.known_bssid == AP_RTT

    def test_a_declared_incapacity_is_not_read_as_silence(self, reference):
        observer = reference.observers[OBSERVER_NORTH]
        assert not observer.can_see(SensorType.WIFI_ASSOCIATION)
        assert observer.can_see(SensorType.BLE)

    def test_the_site_model_carries_no_fingerprints(self, reference):
        # Fingerprints are the Lab's output, not its input. A site model arriving with them would
        # mean the Master had promoted something this run is about to recompute.
        assert reference.fingerprints == ()


class TestDeviceRegistry:
    def test_both_devices_arrive_with_their_identifiers(self, reference, contract_dir):
        document = json.loads((contract_dir / "devices.json").read_text(encoding="utf-8"))
        assert document["registry_id"] == REGISTRY_ID

        tag = reference.devices[TAG_DEVICE]
        assert tag.status == "AUTHORIZED"
        assert TAG_BLE in tag.identifiers
        assert TAG_SERVICE_UUID in tag.identifiers

    def test_a_blocked_device_is_still_recognisable(self, reference):
        # Filtering it out would make its rows indistinguishable from environmental RF, which is
        # the opposite of what blocking a device is for.
        assert reference.devices[RETIRED_DEVICE].status == "BLOCKED"
        assert reference.devices[RETIRED_DEVICE].identifiers == ("ee:ff:00:11:22:33",)

    def test_attribution_resolves_through_the_registry(self, reference):
        assert reference.device_for_identifier(TAG_BLE) == TAG_DEVICE
        assert reference.device_for_identifier("4f:2a:9c:d1:0e:77") is None


# -- RAW ------------------------------------------------------------------------------------------


class TestPackages:
    def test_every_package_is_accepted_without_a_single_finding(self, packages):
        for package in packages:
            assert package.usable, f"{package.dataset_id}: {package.blocking_issues}"
            assert package.issues == (), f"{package.dataset_id}: {package.issues}"
            assert package.invalid_rows == 0

    def test_the_declared_entries_are_all_present(self, fixture_paths):
        with zipfile.ZipFile(fixture_paths[0]) as archive:
            assert archive.namelist() == [
                "manifest.json",
                "observations.csv",
                "observations.json",
                "observer.json",
                "sessions.json",
                "checksum.txt",
            ]

    def test_the_two_views_of_the_same_rows_agree_exactly(self, fixture_paths):
        # read_package already cross-checks and reports; this asserts the fixture is the clean case,
        # so a future CSV_JSON_MISMATCH is a real regression rather than a known wart.
        for path in fixture_paths:
            contents = read_package(path)
            assert not any(
                issue.problem.value in {"CSV_JSON_MISMATCH", "ROW_CHECKSUM_MISMATCH"}
                for issue in contents.issues
            )

    def test_the_dataset_is_the_expected_size_with_no_duplicates(self, fixture_dataset):
        stats = fixture_dataset.stats
        assert stats.packages == len(PACKAGE_NAMES)
        assert stats.rows_accepted == TOTAL_ROWS
        assert stats.duplicates == 0
        assert stats.invalid == 0
        assert stats.rejected_packages == ()
        assert fixture_dataset.source_dataset_ids == tuple(
            name.removesuffix(".zip") for name in PACKAGE_NAMES
        )

    def test_survey_and_ordinary_rows_stay_separate(self, fixture_dataset):
        assert len(fixture_dataset.ground_truth()) == SURVEY_ROWS
        assert len(fixture_dataset.ordinary()) == TOTAL_ROWS - SURVEY_ROWS
        assert all(o.observer_id == SURVEYOR for o in fixture_dataset.ground_truth())
        assert all(o.survey_session_id for o in fixture_dataset.ground_truth())

    def test_three_survey_visits_are_visible_as_three_sessions(self, fixture_dataset):
        sessions = {o.survey_session_id for o in fixture_dataset.ground_truth()}
        assert len(sessions) == 3, "a fingerprint built from one visit records only that visit"
        assert {o.survey_point_id for o in fixture_dataset.ground_truth()} == {"SP-N", "SP-S"}


class TestAwkwardRows:
    """The rows most likely to be mishandled silently by one side and not the other."""

    @staticmethod
    def _rows(dataset, **match):
        return [
            o
            for o in dataset.observations
            if all(getattr(o, key) == value for key, value in match.items())
        ]

    def test_an_ssid_with_a_comma_a_quote_and_a_non_ascii_character_round_trips(
        self, fixture_dataset
    ):
        # An SSID is an arbitrary byte string. If the two CSV dialects disagree about quoting, this
        # is the row where it shows, and it shows as a shifted column rather than as an error.
        rows = [o for o in fixture_dataset.observations if o.ssid and "Caf" in o.ssid]
        assert len(rows) == 2
        assert {o.ssid for o in rows} == {'Café "Nord", guest'}

    def test_a_hidden_ssid_is_absent_rather_than_empty(self, fixture_dataset):
        rows = self._rows(fixture_dataset, radio_identifier="aa:bb:cc:00:00:09")
        assert len(rows) == 2
        assert all(row.ssid is None for row in rows)
        assert all(row.bssid == "aa:bb:cc:00:00:09" for row in rows)

    def test_a_cached_scan_keeps_its_age_and_its_lower_confidence(self, fixture_dataset):
        rows = [o for o in fixture_dataset.observations if o.freshness == "CACHED"]
        assert len(rows) == 2
        for row in rows:
            assert row.metadata["scan_result_age_ms"] == "27400"
            assert row.metadata["throttled"] == "true"
            assert row.confidence == pytest.approx(0.4)

    def test_a_ranging_row_carries_its_distance_and_its_spread(self, fixture_dataset):
        rows = self._rows(fixture_dataset, sensor_type=SensorType.RTT)
        assert len(rows) == 2
        assert {row.rtt_distance_mm for row in rows} == {6420, 9180}
        assert all(row.rtt_stddev_mm == 780 for row in rows)
        assert all(row.metadata["rtt_num_successful"] == "7" for row in rows)

    def test_a_gnss_fix_keeps_full_coordinate_precision(self, fixture_dataset):
        rows = self._rows(fixture_dataset, sensor_type=SensorType.GPS)
        assert len(rows) == 2
        assert {row.latitude for row in rows} == {51.50742, 51.50751}
        assert all(row.longitude == pytest.approx(-0.12781) for row in rows)
        assert all(row.location_accuracy_m == pytest.approx(14.5) for row in rows)
        assert all(row.identifier_type is IdentifierType.GNSS_FIX for row in rows)

    def test_a_randomised_address_is_carried_and_left_unattributed(self, fixture_dataset, reference):
        rows = self._rows(fixture_dataset, identifier_type=IdentifierType.BLE_MAC_RANDOM)
        assert len(rows) == 2
        assert all(row.target_device_id is None for row in rows)
        assert all(reference.device_for_identifier(row.radio_identifier) is None for row in rows)

    def test_the_master_attribution_survives_the_export_it_was_not_part_of(self, fixture_dataset):
        # target_device_id is written by the Master on import, not by the Collector. These packages
        # come straight from a Collector, so the column has to be empty — an attribution appearing
        # here would mean the Lab was reading a re-export and treating it as primary evidence.
        assert all(o.target_device_id is None for o in fixture_dataset.observations)

    def test_a_degraded_permission_state_is_recorded_on_the_row(self, fixture_dataset):
        rows = [o for o in fixture_dataset.observations if o.metadata.get("permission_degraded")]
        assert len(rows) == 2
        assert all(
            row.metadata["missing_permissions"] == "ACCESS_BACKGROUND_LOCATION" for row in rows
        )

    def test_the_managed_tag_is_heard_by_both_collectors_at_the_same_instants(self, fixture_dataset):
        rows = self._rows(fixture_dataset, radio_identifier=TAG_BLE)
        assert len(rows) == 60
        north = {o.timestamp_ms for o in rows if o.observer_id == OBSERVER_NORTH}
        south = {o.timestamp_ms for o in rows if o.observer_id == OBSERVER_SOUTH}
        assert north == south
        assert len(north) == 30
        # Nearer the north collector, which is what makes the expected zone a fact about the data
        # rather than about the engine.
        assert max(o.rssi for o in rows if o.observer_id == OBSERVER_NORTH) > max(
            o.rssi for o in rows if o.observer_id == OBSERVER_SOUTH
        )


# -- DERIVED --------------------------------------------------------------------------------------


def _pipeline(dataset):
    span = dataset.time_range()
    config = PipelineConfig(
        dataset_kind=DatasetKind.REAL,
        # From the data, never from the wall clock: computed_at goes into the manifest and into
        # every estimate id, so a system clock here would make two runs over identical inputs
        # produce different bytes.
        computed_at_ms=span[1] if span else 0,
    )
    return run_pipeline(dataset, config), config


class TestDerivedPackage:
    def test_the_pipeline_positions_the_registered_tag_in_the_right_zone(self, fixture_dataset):
        result, _ = _pipeline(fixture_dataset)

        assert result.estimates, "no estimates: the Lab read the fixtures but concluded nothing"
        assert {e.device_id for e in result.estimates} == {TAG_DEVICE}
        assert {e.zone_id for e in result.estimates} == {ZONE_NORTH}
        assert all(e.building_id == BUILDING for e in result.estimates)
        assert all(e.reference_model_id == REFERENCE_MODEL_ID for e in result.estimates)

    def test_no_estimate_carries_a_coordinate_without_an_error_bar(self, fixture_dataset):
        result, _ = _pipeline(fixture_dataset)
        assert all(
            e.horizontal_uncertainty_m is not None
            for e in result.estimates
            if e.x is not None
        )

    def test_unvalidated_uncertainty_is_stated_on_every_estimate(self, fixture_dataset):
        # No benchmark was run for this package, and the estimates have to say so themselves. An
        # operator reading the map must not be able to mistake these for validated figures.
        result, _ = _pipeline(fixture_dataset)
        assert all("UNVALIDATED_UNCERTAINTY" in e.quality_flags for e in result.estimates)

    def test_no_estimate_claims_ranged_precision(self, fixture_dataset):
        # The ranging in these fixtures is observer-to-anchor, which says where the *observer* is.
        # Nothing ranged the tag, so tier 4 would be a claim the evidence does not support.
        result, _ = _pipeline(fixture_dataset)
        assert all(e.precision_tier.value != "PRECISION_RANGE" for e in result.estimates)

    def test_the_package_cites_every_dataset_it_read(self, fixture_dataset):
        result, _ = _pipeline(fixture_dataset)
        expected = tuple(name.removesuffix(".zip") for name in PACKAGE_NAMES)
        assert all(e.source_dataset_ids == expected for e in result.estimates)

    def test_two_runs_over_the_same_inputs_produce_the_same_package(
        self, fixture_dataset, tmp_path
    ):
        first = write_derived_package(_pipeline(fixture_dataset)[0], tmp_path / "a")
        second = write_derived_package(_pipeline(fixture_dataset)[0], tmp_path / "b")
        assert first.package_sha256 == second.package_sha256
        assert first.entry_digests == second.entry_digests

    def test_the_committed_derived_fixture_is_what_this_build_produces(
        self,
        fixture_dataset,
        contract_dir: Path,
        writing_contract: bool,
        tmp_path,
    ):
        """The fixture the Master's importer reads, checked against a fresh run.

        Compared entry by entry rather than by archive bytes: compressed output belongs to whichever
        zlib CPython was built against, so pinning it would fail on an interpreter upgrade while
        nothing about the contract had moved.
        """
        result, _ = _pipeline(fixture_dataset)
        produced = write_derived_package(result, tmp_path)
        assert produced.package_name == DERIVED_PACKAGE

        target = contract_dir / DERIVED_PACKAGE
        if writing_contract:
            target.write_bytes(produced.path.read_bytes())
            return

        assert target.is_file(), (
            f"{DERIVED_PACKAGE} is missing; regenerate with "
            "`pytest tests/test_fixtures.py --rfmapper-write-contract`"
        )
        fresh = _entries(produced.path.read_bytes())
        committed = _entries(target.read_bytes())

        hint = (
            "\nThe Master's DerivedPackageImporter reads this package. If the change is intended, "
            "regenerate with `--rfmapper-write-contract`, read the diff, and re-run the Kotlin "
            "contract test."
        )
        assert list(committed) == list(fresh), f"the entry list has changed.{hint}"
        for name in fresh:
            assert committed[name] == fresh[name], f"entry '{name}' has changed.{hint}"

    def test_the_fixture_manifest_says_what_the_master_checks(self, contract_dir: Path):
        target = contract_dir / DERIVED_PACKAGE
        if not target.is_file():
            pytest.skip(f"{DERIVED_PACKAGE} has not been generated yet")

        manifest = json.loads(_entries(target.read_bytes())["manifest.json"].decode("utf-8"))
        assert manifest["package_type"] == "DERIVED"
        assert manifest["reference_model_id"] == REFERENCE_MODEL_ID
        # Traceability: the Master refuses a package citing data it has never imported, so these
        # ids have to be the raw package names exactly as the Collector wrote them.
        assert manifest["source_dataset_ids"] == [
            name.removesuffix(".zip") for name in PACKAGE_NAMES
        ]
        assert manifest["counts"]["position_estimates"] > 0
        assert manifest["parameter_set_sha256"]


def _entries(archive: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        return {info.filename: zf.read(info) for info in zf.infolist() if not info.is_dir()}
