"""``observations.csv`` reader, per ``docs/03-csv-column-specification.md``.

JSON is the canonical form, so this reader exists for two narrower purposes: cross-checking the two
views of a package against each other, and letting a human inspect or hand-repair a file in the
field and have the Lab read it back. Both require the same dialect and the same ``row_checksum``
algorithm as ``core-model/csv/ObservationCsvCodec.kt``, which is what this reproduces.

Reading rules that are not negotiable (§4): match columns by header *name*, never coerce a
malformed value into a default, and keep unknown extra columns in ``metadata`` under a
``csv_extra_`` prefix instead of dropping them.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
from dataclasses import dataclass

from ..models import IdentifierType, Observation, SensorType
from ..timeutil import parse_ms

COLUMNS: tuple[str, ...] = (
    "observation_id",
    "schema_version",
    "timestamp_utc",
    "observer_id",
    "observer_device_type",
    "sensor_type",
    "target_device_id",
    "radio_identifier",
    "identifier_type",
    "ssid",
    "bssid",
    "ble_service_uuid",
    "manufacturer_data",
    "rssi",
    "tx_power",
    "frequency",
    "channel",
    "rtt_distance_mm",
    "rtt_stddev_mm",
    "latitude",
    "longitude",
    "horizontal_accuracy",
    "building_id",
    "zone_id",
    "x_coordinate",
    "y_coordinate",
    "confidence",
    "metadata_json",
    "row_checksum",
)

HEADER = ",".join(COLUMNS)

_VALUE_COLUMNS = COLUMNS[:-1]
_UNIT_SEPARATOR = "\u001f"


@dataclass(frozen=True, slots=True)
class RowFailure:
    row_number: int
    reasons: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class CsvReadResult:
    observations: tuple[Observation, ...]
    failures: tuple[RowFailure, ...]
    checksum_mismatches: tuple[int, ...]

    @property
    def rows_read(self) -> int:
        return len(self.observations) + len(self.failures)


def format_decimal(value: float | None, places: int = 6) -> str:
    """Plain decimal, no exponent, trailing zeros trimmed, so ``11.0`` encodes as ``11``."""
    if value is None:
        return ""
    text = f"{float(value):.{places}f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text if text not in ("", "-") else "0"


def encode_metadata(metadata: dict[str, str]) -> str:
    """Compact JSON with sorted keys — the form the row checksum is computed over."""
    if not metadata:
        return "{}"
    return json.dumps(
        {key: metadata[key] for key in sorted(metadata)},
        separators=(",", ":"),
        ensure_ascii=False,
    )


def value_cells(observation: Observation) -> list[str]:
    """The 28 checksum-covered cells, in column order, as decoded text."""
    o = observation
    return [
        o.observation_id,
        o.schema_version,
        o.timestamp_utc,
        o.observer_id,
        o.observer_device_type,
        o.sensor_type.value,
        o.target_device_id or "",
        o.radio_identifier,
        o.identifier_type.value,
        o.ssid or "",
        o.bssid or "",
        o.ble_service_uuid or "",
        o.manufacturer_data or "",
        "" if o.rssi is None else str(o.rssi),
        "" if o.tx_power is None else str(o.tx_power),
        "" if o.frequency is None else str(o.frequency),
        "" if o.channel is None else str(o.channel),
        "" if o.rtt_distance_mm is None else str(o.rtt_distance_mm),
        "" if o.rtt_stddev_mm is None else str(o.rtt_stddev_mm),
        format_decimal(o.latitude),
        format_decimal(o.longitude),
        format_decimal(o.location_accuracy_m),
        o.building_id or "",
        o.zone_id or "",
        format_decimal(o.observer_x),
        format_decimal(o.observer_y),
        format_decimal(o.confidence),
        encode_metadata(dict(o.metadata)),
    ]


def row_checksum(cells: list[str]) -> str:
    """First 16 hex characters of the SHA-256 of the decoded value columns joined by U+001F.

    Truncated to 64 bits on purpose: this catches a spreadsheet mangling a file, which is the
    realistic field failure. Tamper resistance is ``checksum.txt``.
    """
    if len(cells) != len(_VALUE_COLUMNS):
        raise ValueError(f"row checksum covers {len(_VALUE_COLUMNS)} columns, got {len(cells)}")
    joined = _UNIT_SEPARATOR.join(cells)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()[:16]


def encode_row(observation: Observation) -> str:
    """Encode one observation in the canonical dialect, for fixtures and round-trip tests."""
    cells = value_cells(observation)
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="", quoting=csv.QUOTE_MINIMAL)
    writer.writerow([*cells, row_checksum(cells)])
    return buffer.getvalue()


def read_csv(text: str) -> CsvReadResult:
    """Decode a whole ``observations.csv``.

    A malformed cell invalidates its row and the row is reported; the rest of the file still loads.
    One corrupted line in a day's collection must not cost the day.
    """
    reader = csv.reader(io.StringIO(text, newline=""))
    try:
        header = next(reader)
    except StopIteration:
        return CsvReadResult((), (RowFailure(1, ("CSV_EMPTY",)),), ())

    header = [name.lstrip("\ufeff") for name in header]
    missing = [name for name in COLUMNS if name not in header]
    if missing:
        return CsvReadResult(
            (), (RowFailure(1, (f"CSV_HEADER_MISMATCH: missing {', '.join(missing)}",)),), ()
        )

    observations: list[Observation] = []
    failures: list[RowFailure] = []
    mismatches: list[int] = []

    for index, record in enumerate(reader, start=2):
        if not record or all(cell == "" for cell in record):
            continue
        if len(record) != len(header):
            failures.append(
                RowFailure(
                    index,
                    (
                        f"CSV_FIELD_COUNT_MISMATCH: expected {len(header)} fields, "
                        f"got {len(record)}",
                    ),
                )
            )
            continue
        decoded = _decode_row(header, record)
        if isinstance(decoded, RowFailure):
            failures.append(RowFailure(index, decoded.reasons))
            continue
        observation, matched = decoded
        observations.append(observation)
        if not matched:
            mismatches.append(index)

    return CsvReadResult(tuple(observations), tuple(failures), tuple(mismatches))


def _decode_row(
    header: list[str], record: list[str]
) -> tuple[Observation, bool] | RowFailure:
    by_name = dict(zip(header, record))
    reasons: list[str] = []

    def text(column: str) -> str | None:
        raw = by_name.get(column)
        return raw if raw else None

    def integer(column: str) -> int | None:
        raw = text(column)
        if raw is None:
            return None
        try:
            return int(raw)
        except ValueError:
            reasons.append(f"INVALID_INTEGER: {column}='{raw}'")
            return None

    def decimal(column: str) -> float | None:
        raw = text(column)
        if raw is None:
            return None
        try:
            parsed = float(raw)
        except ValueError:
            reasons.append(f"INVALID_DECIMAL: {column}='{raw}'")
            return None
        # NaN and the infinities are rejected rather than clamped (§4.5): a coordinate of
        # "Infinity" is not a large measurement, it is a broken one.
        if parsed != parsed or parsed in (float("inf"), float("-inf")):
            reasons.append(f"INVALID_DECIMAL: {column}='{raw}'")
            return None
        return parsed

    sensor_raw = text("sensor_type")
    try:
        sensor = SensorType(sensor_raw)
    except ValueError:
        sensor = None
        reasons.append(f"INVALID_ENUM: sensor_type='{sensor_raw}'")

    identifier_raw = text("identifier_type")
    try:
        identifier_type = IdentifierType(identifier_raw)
    except ValueError:
        identifier_type = None
        reasons.append(f"INVALID_ENUM: identifier_type='{identifier_raw}'")

    metadata = _decode_metadata(by_name.get("metadata_json"))
    if metadata is None:
        reasons.append("INVALID_METADATA_JSON")

    values = {
        "rssi": integer("rssi"),
        "tx_power": integer("tx_power"),
        "frequency": integer("frequency"),
        "channel": integer("channel"),
        "rtt_distance_mm": integer("rtt_distance_mm"),
        "rtt_stddev_mm": integer("rtt_stddev_mm"),
        "latitude": decimal("latitude"),
        "longitude": decimal("longitude"),
        "location_accuracy_m": decimal("horizontal_accuracy"),
        "observer_x": decimal("x_coordinate"),
        "observer_y": decimal("y_coordinate"),
        "confidence": decimal("confidence"),
    }

    timestamp = text("timestamp_utc")
    try:
        timestamp_ms = parse_ms(timestamp or "")
    except ValueError:
        timestamp_ms = 0
        reasons.append(f"INVALID_TIMESTAMP: timestamp_utc='{timestamp}'")

    if reasons or sensor is None or identifier_type is None or metadata is None:
        return RowFailure(0, tuple(reasons or ("INVALID_ROW",)))

    extras = {
        f"csv_extra_{name}": by_name[name]
        for name in header
        if name not in COLUMNS and by_name.get(name)
    }

    observation = Observation(
        observation_id=text("observation_id") or "",
        schema_version=text("schema_version") or "",
        timestamp_utc=timestamp or "",
        timestamp_ms=timestamp_ms,
        observer_id=text("observer_id") or "",
        observer_device_type=text("observer_device_type") or "",
        sensor_type=sensor,
        radio_identifier=text("radio_identifier") or "",
        identifier_type=identifier_type,
        ssid=text("ssid"),
        bssid=text("bssid"),
        ble_service_uuid=text("ble_service_uuid"),
        manufacturer_data=text("manufacturer_data"),
        building_id=text("building_id"),
        zone_id=text("zone_id"),
        target_device_id=text("target_device_id"),
        metadata=dict(metadata) | extras,
        **values,
    )

    declared = text("row_checksum")
    # Recomputed over the metadata as the producer wrote it, excluding extras folded in above.
    recomputed = row_checksum(value_cells(observation.with_metadata(dict(metadata))))
    return observation, declared is None or declared == recomputed


def _decode_metadata(raw: str | None) -> dict[str, str] | None:
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict):
        return None
    if any(not isinstance(value, str) for value in parsed.values()):
        return None
    return {str(key): value for key, value in parsed.items()}
