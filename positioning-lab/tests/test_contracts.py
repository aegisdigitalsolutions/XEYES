"""The cross-language contracts: time format, canonical JSON, identifiers, CSV.

These are the places where the Lab and the Kotlin apps have to agree byte for byte. A drift here
does not throw; it produces a package the other side reads slightly differently, which is the
failure mode hardest to notice and most expensive to unwind.
"""

from __future__ import annotations

import json

import pytest

from rfmapper_lab.jsonio import (
    checksum_file,
    derive_id,
    dumps,
    dumps_sorted,
    parse_checksum_file,
    sha256_hex,
)
from rfmapper_lab.models import IdentifierType, Observation, SensorType
from rfmapper_lab.parsing import csv_reader
from rfmapper_lab.parsing.normalize import (
    is_random_ble_address,
    normalize_identifier,
    normalize_mac,
    normalize_uuid,
)
from rfmapper_lab.timeutil import date_stamp, day_bounds, format_ms, is_instant, parse_ms


class TestTime:
    def test_round_trip_preserves_millisecond(self):
        for epoch_ms in (0, 1, 999, 1_000, 1_789_000_000_123):
            assert parse_ms(format_ms(epoch_ms)) == epoch_ms

    def test_format_is_the_canonical_shape(self):
        assert format_ms(0) == "1970-01-01T00:00:00.000Z"
        assert format_ms(1_757_836_800_000) == "2025-09-14T08:00:00.000Z"

    def test_milliseconds_are_always_three_digits(self):
        # A writer that emitted "…:00.5Z" would still parse, and would sort wrongly against its
        # own siblings. Fixed width is what makes lexical order equal chronological order.
        assert format_ms(500).endswith(".500Z")
        assert format_ms(50).endswith(".050Z")

    def test_date_stamp_and_day_bounds_agree(self):
        stamp = date_stamp(1_757_836_800_000)
        assert stamp == "2025-09-14"
        start, end = day_bounds(stamp)
        assert start <= 1_757_836_800_000 <= end
        assert end - start == 86_400_000 - 1

    def test_is_instant_rejects_near_misses(self):
        assert is_instant("2026-09-14T08:00:00.000Z")
        assert not is_instant("2026-09-14T08:00:00Z")
        assert not is_instant("2026-09-14 08:00:00.000Z")
        assert not is_instant("2026-09-14T08:00:00.000+01:00")


class TestCanonicalJson:
    def test_sorted_form_is_stable_regardless_of_insertion_order(self):
        assert dumps_sorted({"b": 1, "a": 2}) == dumps_sorted({"a": 2, "b": 1})

    def test_nulls_are_explicit(self):
        # The Kotlin side decodes with explicitNulls, so an omitted optional is not the same as a
        # null one. Dropping nulls here would produce packages it refuses.
        assert '"x": null' in dumps({"x": None})

    def test_nan_is_refused_rather_than_written(self):
        with pytest.raises(ValueError):
            dumps({"x": float("nan")})

    def test_derive_id_is_content_addressed(self):
        first = derive_id("estimate", "pipeline-1.0.0", "DEVICE-01", "2026-09-14T08:00:00.000Z")
        second = derive_id("estimate", "pipeline-1.0.0", "DEVICE-01", "2026-09-14T08:00:00.000Z")
        third = derive_id("estimate", "pipeline-1.0.0", "DEVICE-02", "2026-09-14T08:00:00.000Z")
        assert first == second
        assert first != third

    def test_checksum_file_is_sha256sum_format_sorted_by_name(self):
        text = checksum_file({"b.json": "bb", "a.json": "aa"})
        assert text == "aa  a.json\nbb  b.json\n"
        assert parse_checksum_file(text) == {"a.json": "aa", "b.json": "bb"}

    def test_sha256_matches_a_known_value(self):
        assert sha256_hex(b"") == (
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )


class TestIdentifierNormalization:
    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"),
            ("aa-bb-cc-dd-ee-ff", "aa:bb:cc:dd:ee:ff"),
            ("aabbccddeeff", "aa:bb:cc:dd:ee:ff"),
            ("a:b:c:d:e:f", "0a:0b:0c:0d:0e:0f"),
        ],
    )
    def test_mac_forms_collapse_to_one(self, raw, expected):
        assert normalize_mac(raw) == expected

    @pytest.mark.parametrize("raw", ["", "  ", "00:00:00:00:00:00", "02:00:00:00:00:00", "zz"])
    def test_sentinels_and_rubbish_are_not_addresses(self, raw):
        assert normalize_mac(raw) is None

    def test_uuid_is_lowercased_and_hyphenated(self):
        assert (
            normalize_uuid("0000180F-0000-1000-8000-00805F9B34FB")
            == "0000180f-0000-1000-8000-00805f9b34fb"
        )
        assert (
            normalize_uuid("0000180f00001000800000805f9b34fb")
            == "0000180f-0000-1000-8000-00805f9b34fb"
        )

    def test_locally_administered_bit_marks_a_rotating_address(self):
        assert is_random_ble_address("c2:11:00:00:00:01")
        assert not is_random_ble_address("c0:11:00:00:00:01")

    def test_normalization_is_idempotent(self):
        once = normalize_identifier("AA:BB:CC:DD:EE:FF", IdentifierType.WIFI_BSSID)
        assert normalize_identifier(once, IdentifierType.WIFI_BSSID) == once


def _observation(**overrides) -> Observation:
    base = dict(
        observation_id="11111111-2222-3333-4444-555555555555",
        schema_version="1.0.0",
        timestamp_utc="2026-09-14T08:00:00.000Z",
        timestamp_ms=parse_ms("2026-09-14T08:00:00.000Z"),
        observer_id="OBS-00",
        observer_device_type="ANDROID_PHONE",
        sensor_type=SensorType.WIFI_SCAN,
        radio_identifier="aa:bb:cc:00:00:01",
        identifier_type=IdentifierType.WIFI_BSSID,
        rssi=-61,
        metadata={"result_freshness": "FRESH"},
    )
    base.update(overrides)
    return Observation(**base)


class TestCsv:
    def test_header_has_the_specified_columns_in_order(self):
        assert csv_reader.COLUMNS[0] == "observation_id"
        assert csv_reader.COLUMNS[-1] == "row_checksum"
        assert len(csv_reader.COLUMNS) == 29

    def test_row_round_trips_through_the_canonical_dialect(self):
        original = _observation(ssid="Guest, Wi-Fi", metadata={"note": 'has "quotes"'})
        text = csv_reader.HEADER + "\n" + csv_reader.encode_row(original) + "\n"
        result = csv_reader.read_csv(text)

        assert result.failures == ()
        assert result.checksum_mismatches == ()
        decoded = result.observations[0]
        assert decoded.ssid == "Guest, Wi-Fi"
        assert decoded.metadata["note"] == 'has "quotes"'
        assert decoded.observation_id == original.observation_id

    def test_row_checksum_covers_every_value_column(self):
        cells = csv_reader.value_cells(_observation())
        tampered = list(cells)
        tampered[cells.index("-61")] = "-40"
        assert csv_reader.row_checksum(cells) != csv_reader.row_checksum(tampered)

    def test_a_tampered_cell_is_reported_not_repaired(self):
        original = _observation()
        row = csv_reader.encode_row(original).replace(",-61,", ",-40,")
        result = csv_reader.read_csv(csv_reader.HEADER + "\n" + row + "\n")

        # The row still decodes: the checksum detects corruption, it does not gate the read. The
        # Lab reports the disagreement rather than deciding which version is true.
        assert result.checksum_mismatches == (2,)
        assert result.observations[0].rssi == -40

    def test_one_bad_row_does_not_cost_the_file(self):
        good = csv_reader.encode_row(_observation())
        other = csv_reader.encode_row(
            _observation(observation_id="99999999-2222-3333-4444-555555555555")
        )
        text = csv_reader.HEADER + "\n" + good + "\n" + "not,enough,columns\n" + other + "\n"
        result = csv_reader.read_csv(text)

        assert len(result.observations) == 2
        assert len(result.failures) == 1
        assert result.failures[0].row_number == 3

    def test_unknown_columns_are_kept_rather_than_dropped(self):
        text = (
            csv_reader.HEADER
            + ",future_column\n"
            + csv_reader.encode_row(_observation())
            + ",tomorrow\n"
        )
        result = csv_reader.read_csv(text)
        assert result.observations[0].metadata["csv_extra_future_column"] == "tomorrow"

    def test_decimals_never_use_exponent_notation(self):
        assert csv_reader.format_decimal(0.0000001) == "0"
        assert csv_reader.format_decimal(11.0) == "11"
        assert csv_reader.format_decimal(-0.5) == "-0.5"

    def test_metadata_column_is_sorted_compact_json(self):
        encoded = csv_reader.encode_metadata({"b": "2", "a": "1"})
        assert encoded == '{"a":"1","b":"2"}'
        assert json.loads(encoded) == {"a": "1", "b": "2"}
