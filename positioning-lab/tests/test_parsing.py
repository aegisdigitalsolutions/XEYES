"""Phase 1: package ingest, deduplication and the site model reader.

Everything here is about what the Lab refuses. The generous cases are cheap to get right; the
expensive question is whether a tampered, truncated or mislabelled package is caught before its rows
reach an estimate, and whether the refusal costs the right amount — the package, or only the row.
"""

from __future__ import annotations

import io
import json
import zipfile
from dataclasses import replace
from pathlib import Path

import pytest

from rfmapper_lab.jsonio import checksum_file, sha256_hex
from rfmapper_lab.models import IdentifierType, Observation, SensorType
from rfmapper_lab.parsing import parse_reference_model
from rfmapper_lab.parsing.csv_reader import HEADER, encode_row, read_csv
from rfmapper_lab.parsing.dataset import deduplicate, load_dataset
from rfmapper_lab.parsing.package_reader import PackageProblem, read_package
from rfmapper_lab.parsing.reference_reader import (
    ReferenceModelError,
    load_devices,
    load_reference_model,
)
from rfmapper_lab.simulator import write_packages


@pytest.fixture(scope="session")
def packages(site, tmp_path_factory) -> tuple[Path, ...]:
    """Real zip files, written once and read many times."""
    destination = tmp_path_factory.mktemp("packages")
    written = write_packages(site.observations, destination)
    return tuple(package.path for package in written)


def _entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        return {info.filename: archive.read(info) for info in archive.infolist()}


def _rewrite(
    path: Path, destination: Path, *, resign: bool = False, **changes: bytes | None
) -> Path:
    """Copy a package, replacing or removing named entries.

    The point of editing a real package rather than hand-building a minimal one is that the parts
    left alone stay valid, so a test about a bad digest is not quietly also a test about a missing
    manifest.

    ``resign`` recomputes ``checksum.txt`` over the edited entries, which is the difference between
    the two kinds of damage this file cares about. Without it the edit looks like tampering and the
    package is refused whole, which is correct but tells you nothing about row handling; with it the
    package is internally consistent and whatever is left is a genuine row-level finding.
    """
    entries = _entries(path)
    for name, payload in changes.items():
        if payload is None:
            entries.pop(name, None)
        else:
            entries[name] = payload
    if resign:
        entries["checksum.txt"] = checksum_file(
            {
                name: sha256_hex(payload)
                for name, payload in entries.items()
                if name != "checksum.txt"
            }
        ).encode("utf-8")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            archive.writestr(name, entries[name])
    return destination


def _manifest(path: Path) -> dict:
    return json.loads(_entries(path)["manifest.json"].decode("utf-8"))


class TestPackageRoundTrip:
    def test_a_well_formed_package_is_read_whole(self, packages):
        contents = read_package(packages[0])

        assert contents.usable
        assert contents.issues == ()
        assert contents.observations
        assert contents.rows_read == len(contents.observations)
        assert contents.invalid_rows == 0

    def test_the_csv_and_the_json_carry_the_same_rows(self, packages):
        entries = _entries(packages[0])
        from_csv = read_csv(entries["observations.csv"].decode("utf-8"))
        from_json = read_package(packages[0]).observations

        assert from_csv.failures == ()
        assert from_csv.checksum_mismatches == ()
        assert {row.observation_id for row in from_csv.observations} == {
            row.observation_id for row in from_json
        }

    def test_the_package_digest_is_over_the_file_not_its_contents(self, packages, tmp_path):
        # Recompressing the same entries yields different bytes, so the reported sha must differ
        # even though every row is identical. The Master keys on this to detect a re-import.
        original = read_package(packages[0])
        repacked = read_package(_rewrite(packages[0], tmp_path / "repacked.zip"))

        assert repacked.package_sha256 != original.package_sha256
        assert {row.observation_id for row in repacked.observations} == {
            row.observation_id for row in original.observations
        }

    def test_writing_a_package_twice_produces_the_same_bytes(self, site, tmp_path):
        """The fixed member timestamp exists for this: otherwise the mtime moves the digest."""
        rows = site.observations[:200]
        first = write_packages(rows, tmp_path / "a")
        second = write_packages(rows, tmp_path / "b")

        assert [p.package_sha256 for p in first] == [p.package_sha256 for p in second]


class TestPackageRefusal:
    def test_a_tampered_file_costs_the_whole_package(self, packages, tmp_path):
        """An edited ``observations.csv`` fails its declared digest, and nothing else can be
        trusted either: whoever changed one entry could have changed any.
        """
        entries = _entries(packages[0])
        edited = entries["observations.csv"].replace(b"-55", b"-35", 1)
        contents = read_package(
            _rewrite(packages[0], tmp_path / "tampered.zip", **{"observations.csv": edited})
        )

        assert not contents.usable
        assert PackageProblem.CHECKSUM_MISMATCH in {i.problem for i in contents.issues}

    def test_a_missing_checksum_file_is_refused_rather_than_assumed_intact(self, packages, tmp_path):
        contents = read_package(
            _rewrite(packages[0], tmp_path / "unsigned.zip", **{"checksum.txt": None})
        )

        assert not contents.usable
        assert PackageProblem.CHECKSUM_MISSING in {i.problem for i in contents.issues}

    def test_a_checksum_file_naming_an_absent_entry_is_refused(self, packages, tmp_path):
        contents = read_package(
            _rewrite(packages[0], tmp_path / "incomplete.zip", **{"sessions.json": None})
        )

        assert not contents.usable
        assert PackageProblem.CHECKSUM_MISMATCH in {i.problem for i in contents.issues}

    def test_a_corrupt_archive_is_reported_not_raised(self, tmp_path):
        """Reading is an inspection, so a broken file is a finding rather than a crash."""
        path = tmp_path / "shredded.zip"
        path.write_bytes(b"PK\x03\x04 and then nothing that follows the format")
        contents = read_package(path)

        assert not contents.usable
        assert contents.observations == ()
        assert contents.package_sha256, "a rejected package must still report its file digest"
        assert PackageProblem.UNREADABLE_ZIP in {i.problem for i in contents.issues}

    def test_a_derived_package_is_not_mistaken_for_observations(self, packages, tmp_path):
        manifest = _manifest(packages[0]) | {"package_type": "DERIVED"}
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "wrong-type.zip",
                resign=True,
                **{"manifest.json": json.dumps(manifest).encode("utf-8")},
            )
        )

        assert not contents.usable
        assert PackageProblem.WRONG_PACKAGE_TYPE in {i.problem for i in contents.issues}

    def test_an_unknown_schema_major_is_refused_and_an_unknown_minor_is_not(self, packages, tmp_path):
        """Forward compatibility has a boundary, and this is where it sits (``docs/03`` §4.3).

        A new minor may add optional fields an old reader can ignore. A new major may redefine what
        an existing field means, and a reader that guessed would produce plausible wrong numbers.
        """
        base = _manifest(packages[0])

        future_minor = read_package(
            _rewrite(
                packages[0],
                tmp_path / "minor.zip",
                resign=True,
                **{"manifest.json": json.dumps(base | {"schema_version": "1.99.0"}).encode()},
            )
        )
        future_major = read_package(
            _rewrite(
                packages[0],
                tmp_path / "major.zip",
                resign=True,
                **{"manifest.json": json.dumps(base | {"schema_version": "9.0.0"}).encode()},
            )
        )

        assert future_minor.usable
        assert not future_major.usable
        assert PackageProblem.UNSUPPORTED_SCHEMA_MAJOR in {i.problem for i in future_major.issues}

    def test_a_row_from_another_observer_costs_the_package(self, packages, tmp_path):
        """One package, one observer (``docs/16`` §5). A stray row means the observer attribution
        in the file cannot be believed, and observer identity is what calibration is keyed on.
        """
        rows = json.loads(_entries(packages[0])["observations.json"].decode("utf-8"))
        rows[0] = rows[0] | {"observer_id": "OBS-SOMEONE-ELSE"}
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "merged.zip",
                resign=True,
                **{"observations.json": json.dumps(rows).encode("utf-8")},
            )
        )

        assert not contents.usable
        assert PackageProblem.FOREIGN_OBSERVER in {i.problem for i in contents.issues}


class TestRowLevelDamage:
    def test_a_malformed_row_costs_the_row_and_not_the_day(self, packages, tmp_path):
        rows = json.loads(_entries(packages[0])["observations.json"].decode("utf-8"))
        expected = len(rows) - 1
        rows[2] = rows[2] | {"sensor_type": "TELEPATHY"}
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "one-bad-row.zip",
                resign=True,
                **{"observations.json": json.dumps(rows).encode("utf-8")},
            )
        )

        assert contents.usable, "one mangled line must not cost a day of collection"
        assert contents.invalid_rows == 1
        assert len(contents.observations) == expected
        assert PackageProblem.MALFORMED_ROW in {i.problem for i in contents.issues}

    def test_many_malformed_rows_produce_a_legible_finding_not_thousands(self, packages, tmp_path):
        rows = json.loads(_entries(packages[0])["observations.json"].decode("utf-8"))
        for index in range(40):
            rows[index] = rows[index] | {"observation_id": ""}
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "many-bad-rows.zip",
                resign=True,
                **{"observations.json": json.dumps(rows).encode("utf-8")},
            )
        )

        malformed = [i for i in contents.issues if i.problem is PackageProblem.MALFORMED_ROW]
        assert contents.invalid_rows == 40
        assert len(malformed) <= 6

    def test_a_divergence_between_the_two_views_is_reported_not_resolved(self, packages, tmp_path):
        rows = json.loads(_entries(packages[0])["observations.json"].decode("utf-8"))
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "diverged.zip",
                resign=True,
                **{"observations.json": json.dumps(rows[:-5]).encode("utf-8")},
            )
        )

        problems = {i.problem for i in contents.issues}
        assert PackageProblem.CSV_JSON_MISMATCH in problems
        assert PackageProblem.MANIFEST_COUNT_MISMATCH in problems

    def test_an_unknown_field_is_ignored_rather_than_refused(self, packages, tmp_path):
        """A minor schema version may add a field, and an older reader must still read the day."""
        rows = json.loads(_entries(packages[0])["observations.json"].decode("utf-8"))
        rows = [row | {"barometric_pressure_hpa": 1013} for row in rows]
        contents = read_package(
            _rewrite(
                packages[0],
                tmp_path / "extra-field.zip",
                resign=True,
                **{"observations.json": json.dumps(rows).encode("utf-8")},
            )
        )

        assert contents.invalid_rows == 0
        assert len(contents.observations) == len(rows)

    def test_a_spreadsheet_mangled_cell_fails_its_row_checksum(self):
        row = Observation(
            observation_id="obs-1",
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=1_789_286_400_000,
            observer_id="OBS-00",
            sensor_type=SensorType.WIFI_SCAN,
            radio_identifier="aa:bb:cc:dd:ee:ff",
            identifier_type=IdentifierType.WIFI_BSSID,
            rssi=-55,
        )
        honest = f"{HEADER}\n{encode_row(row)}\n"
        mangled = honest.replace(",-55,", ",-35,")

        assert read_csv(honest).checksum_mismatches == ()
        result = read_csv(mangled)
        # Still decoded: the reader reports the discrepancy and leaves the judgement upstream.
        assert result.observations[0].rssi == -35
        assert result.checksum_mismatches == (2,)

    def test_a_short_row_is_rejected_by_position_not_padded(self):
        text = f"{HEADER}\nobs-1,1.0.0,2026-09-14T08:00:00.000Z\n"
        result = read_csv(text)

        assert result.observations == ()
        assert result.failures[0].row_number == 2

    def test_an_infinite_coordinate_is_a_broken_measurement_not_a_large_one(self):
        row = Observation(
            observation_id="obs-1",
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=1_789_286_400_000,
            observer_id="OBS-00",
            sensor_type=SensorType.WIFI_SCAN,
            radio_identifier="aa:bb:cc:dd:ee:ff",
            identifier_type=IdentifierType.WIFI_BSSID,
            rssi=-55,
        )
        cells = encode_row(row).split(",")
        cells[24] = "Infinity"
        result = read_csv(f"{HEADER}\n{','.join(cells)}\n")

        assert result.observations == ()
        assert "INVALID_DECIMAL" in result.failures[0].reasons[0]

    def test_an_unknown_column_is_kept_under_a_prefix_rather_than_dropped(self):
        row = Observation(
            observation_id="obs-1",
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=1_789_286_400_000,
            observer_id="OBS-00",
            sensor_type=SensorType.WIFI_SCAN,
            radio_identifier="aa:bb:cc:dd:ee:ff",
            identifier_type=IdentifierType.WIFI_BSSID,
            rssi=-55,
        )
        text = f"{HEADER},future_column\n{encode_row(row)},42\n"
        decoded = read_csv(text).observations[0]

        assert decoded.metadata["csv_extra_future_column"] == "42"
        # The extra column is not part of the producer's checksum, so folding it in must not be
        # read as corruption.
        assert read_csv(text).checksum_mismatches == ()


class TestDeduplication:
    def _row(self, observation_id: str, rssi: int) -> Observation:
        return Observation(
            observation_id=observation_id,
            timestamp_utc="2026-09-14T08:00:00.000Z",
            timestamp_ms=1_789_286_400_000,
            observer_id="OBS-00",
            sensor_type=SensorType.BLE,
            radio_identifier="aa:bb:cc:dd:ee:ff",
            identifier_type=IdentifierType.BLE_MAC_PUBLIC,
            rssi=rssi,
        )

    def test_an_overlapping_re_export_is_routine_and_counted(self):
        batch = [self._row("a", -50), self._row("b", -60)]
        kept, duplicates, mismatches = deduplicate([batch, batch])

        assert len(kept) == 2
        assert duplicates == 2
        assert mismatches == 0

    def test_the_first_occurrence_wins(self):
        kept, _, _ = deduplicate([[self._row("a", -50)], [self._row("a", -90)]])
        assert kept[0].rssi == -50

    def test_the_same_id_on_a_different_measurement_is_an_anomaly(self):
        """Two different measurements sharing an id is not an overlap, it is a generator bug."""
        _, duplicates, mismatches = deduplicate([[self._row("a", -50)], [self._row("a", -90)]])

        assert duplicates == 1
        assert mismatches == 1

    def test_rows_come_back_in_time_order_regardless_of_package_order(self):
        early = replace(self._row("z", -50), timestamp_ms=1_000)
        late = replace(self._row("a", -50), timestamp_ms=2_000)
        kept, _, _ = deduplicate([[late], [early]])

        assert [row.observation_id for row in kept] == ["z", "a"]

    def test_a_rejected_package_is_named_rather_than_looking_like_a_quiet_day(
        self, packages, model, tmp_path
    ):
        """An observer that contributed nothing must be distinguishable from one that was refused."""
        good = read_package(packages[0])
        bad_path = _rewrite(packages[0], tmp_path / "bad.zip", **{"checksum.txt": None})
        bad = read_package(bad_path, name="OBSBROKEN_2026-09-14.zip")

        dataset = load_dataset([good, bad], model)

        assert dataset.stats.packages == 2
        assert dataset.stats.rejected_packages == ("OBSBROKEN_2026-09-14",)
        assert good.dataset_id in dataset.source_dataset_ids
        assert "OBSBROKEN_2026-09-14" not in dataset.source_dataset_ids
        assert len(dataset.observations) == len(good.observations)

    def test_the_content_hash_changes_with_the_rows_and_not_with_the_run(self, dataset):
        assert dataset.content_sha256 == dataset.content_sha256
        narrowed = dataset.within(*dataset.time_range())
        assert narrowed.content_sha256 == dataset.content_sha256

        span = dataset.time_range()
        half = dataset.within(span[0], (span[0] + span[1]) // 2)
        assert half.content_sha256 != dataset.content_sha256


class TestReferenceModel:
    def test_the_simulated_site_round_trips_through_the_reader(self, site, tmp_path):
        path = tmp_path / "site_model.json"
        path.write_text(json.dumps(site.reference_document), encoding="utf-8")
        model = load_reference_model(path)

        assert model.zones
        assert model.observers
        assert model.reference_model_id == site.reference_document["reference_model_id"]

    def test_a_dangling_zone_edge_breaks_the_whole_document(self, site):
        document = dict(site.reference_document)
        document["zone_edges"] = [
            *document["zone_edges"],
            {"from_zone_id": next(iter(document["zones"]))["zone_id"], "to_zone_id": "NOWHERE"},
        ]

        with pytest.raises(ReferenceModelError, match="unknown zone 'NOWHERE'"):
            parse_reference_model(document)

    def test_an_unnamed_site_model_cannot_be_cited(self, site):
        document = {k: v for k, v in site.reference_document.items() if k != "reference_model_id"}

        with pytest.raises(ReferenceModelError, match="reference_model_id"):
            parse_reference_model(document)

    def test_an_rtt_anchor_without_coordinates_is_refused(self, site):
        document = dict(site.reference_document)
        document["infrastructure_nodes"] = [
            *document["infrastructure_nodes"],
            {"node_id": "GHOST-ANCHOR", "type": "RTT_ANCHOR", "rtt_capable": True},
        ]

        with pytest.raises(ReferenceModelError, match="cannot anchor a range"):
            parse_reference_model(document)

    def test_an_unknown_schema_major_is_refused(self, site):
        document = dict(site.reference_document) | {"schema_version": "9.0.0"}

        with pytest.raises(ReferenceModelError, match="major"):
            parse_reference_model(document)

    def test_the_device_registry_may_arrive_as_a_separate_file(self, site, tmp_path):
        """Geometry and enrollment version independently: issuing a phone must not renumber a site."""
        document = {k: v for k, v in site.reference_document.items() if k != "managed_devices"}
        site_path = tmp_path / "site_model.json"
        site_path.write_text(json.dumps(document), encoding="utf-8")
        devices_path = tmp_path / "devices.json"
        devices_path.write_text(
            json.dumps(site.reference_document["managed_devices"]), encoding="utf-8"
        )

        without = load_reference_model(site_path)
        merged = load_reference_model(site_path, devices_path)

        assert not without.devices
        assert merged.devices == load_devices(devices_path)
        assert merged.reference_model_id == without.reference_model_id

    def test_an_unreadable_identifier_type_degrades_to_other_rather_than_failing(self, site):
        """A fingerprint entry naming an identifier kind this build does not know is still usable
        evidence: the statistics are the signal and the label is metadata.

        The asymmetry with an unknown ``sensor_type`` on a RAW row, which invalidates the row, is
        deliberate. There the label says how to interpret the measurement; here the measurement is
        already a distribution and the label only says where it came from.
        """
        point = site.reference_document["survey_points"][0]
        document = dict(site.reference_document)
        document["fingerprints"] = [
            {
                "fingerprint_id": "FP-FUTURE",
                "survey_point_id": point["survey_point_id"],
                "building_id": point["building_id"],
                "zone_id": point["zone_id"],
                "entries": [
                    {
                        "radio_identifier": "aa:bb:cc:dd:ee:ff",
                        "identifier_type": "UWB_ANCHOR_FROM_THE_FUTURE",
                        "rssi_median": -62.0,
                        "sample_count": 30,
                        "visibility_probability": 0.9,
                    }
                ],
            }
        ]

        model = parse_reference_model(document)
        assert model.fingerprints[0].entries[0].identifier_type is IdentifierType.OTHER


class TestPackageReaderStreams:
    def test_a_package_can_be_read_from_a_stream(self, packages):
        """The Lab reads from object storage in production, where there is no local path."""
        payload = packages[0].read_bytes()
        from_stream = read_package(io.BytesIO(payload), name=packages[0].name)
        from_path = read_package(packages[0])

        assert from_stream.package_sha256 == from_path.package_sha256
        assert from_stream.dataset_id == from_path.dataset_id
        assert len(from_stream.observations) == len(from_path.observations)
