"""Reads one observation export package, per ``docs/16-export-package-specification.md``.

``observations.json`` is canonical and ``observations.csv`` is the inspectable view; when they
disagree the disagreement is reported rather than resolved by preferring one
(``docs/03-csv-column-specification.md`` §1). The Lab's job here is to decide whether a package can
be trusted, not to repair it.
"""

from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, BinaryIO

from ..jsonio import parse_checksum_file, sha256_hex
from ..models import IdentifierType, Observation, SensorType
from ..timeutil import parse_ms
from ..version import SCHEMA_VERSION
from . import csv_reader

MANIFEST = "manifest.json"
OBSERVATIONS_JSON = "observations.json"
OBSERVATIONS_CSV = "observations.csv"
OBSERVER = "observer.json"
SESSIONS = "sessions.json"
CHECKSUM = "checksum.txt"


class PackageProblem(str, Enum):
    UNREADABLE_ZIP = "UNREADABLE_ZIP"
    MISSING_MANIFEST = "MISSING_MANIFEST"
    MISSING_OBSERVATIONS = "MISSING_OBSERVATIONS"
    WRONG_PACKAGE_TYPE = "WRONG_PACKAGE_TYPE"
    UNSUPPORTED_SCHEMA_MAJOR = "UNSUPPORTED_SCHEMA_MAJOR"
    CHECKSUM_MISSING = "CHECKSUM_MISSING"
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    MALFORMED_ROW = "MALFORMED_ROW"
    ROW_CHECKSUM_MISMATCH = "ROW_CHECKSUM_MISMATCH"
    CSV_JSON_MISMATCH = "CSV_JSON_MISMATCH"
    MANIFEST_COUNT_MISMATCH = "MANIFEST_COUNT_MISMATCH"
    FOREIGN_OBSERVER = "FOREIGN_OBSERVER"

    @property
    def blocking(self) -> bool:
        """Whether the problem costs the whole package rather than some of its rows.

        The line is drawn at trust: an unreadable zip or a failed file digest means nothing in the
        package can be believed. A handful of malformed rows means those rows are unusable and the
        rest are fine, and refusing the package would throw away a day of good collection over one
        mangled line.
        """
        return self in _BLOCKING


_BLOCKING = frozenset(
    {
        PackageProblem.UNREADABLE_ZIP,
        PackageProblem.MISSING_MANIFEST,
        PackageProblem.MISSING_OBSERVATIONS,
        PackageProblem.WRONG_PACKAGE_TYPE,
        PackageProblem.UNSUPPORTED_SCHEMA_MAJOR,
        PackageProblem.CHECKSUM_MISSING,
        PackageProblem.CHECKSUM_MISMATCH,
        PackageProblem.FOREIGN_OBSERVER,
    }
)


@dataclass(frozen=True, slots=True)
class Issue:
    problem: PackageProblem
    message: str

    def as_dict(self) -> dict[str, str]:
        return {"code": self.problem.value, "message": self.message}


@dataclass(frozen=True, slots=True)
class PackageContents:
    dataset_id: str
    package_sha256: str
    manifest: dict[str, Any]
    observer: dict[str, Any]
    sessions: tuple[dict[str, Any], ...]
    observations: tuple[Observation, ...]
    issues: tuple[Issue, ...]
    rows_read: int = 0
    invalid_rows: int = 0

    @property
    def usable(self) -> bool:
        return not any(issue.problem.blocking for issue in self.issues)

    @property
    def blocking_issues(self) -> tuple[Issue, ...]:
        return tuple(issue for issue in self.issues if issue.problem.blocking)

    @property
    def observer_id(self) -> str:
        return str(self.manifest.get("observer_id", ""))


def read_package(source: Path | BinaryIO, *, name: str | None = None) -> PackageContents:
    """Read and validate one ``OBS<id>_<date>.zip``."""
    if isinstance(source, Path):
        raw = source.read_bytes()
        package_name = name or source.name
    else:
        raw = source.read()
        package_name = name or "package.zip"

    sha = sha256_hex(raw)
    dataset_id = package_name.removesuffix(".zip")

    try:
        entries = _read_entries(raw)
    except (zipfile.BadZipFile, OSError) as exc:
        return _empty(dataset_id, sha, Issue(PackageProblem.UNREADABLE_ZIP, str(exc)))

    if MANIFEST not in entries:
        return _empty(dataset_id, sha, Issue(PackageProblem.MISSING_MANIFEST, f"{MANIFEST} is absent"))
    try:
        manifest = json.loads(entries[MANIFEST].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        return _empty(
            dataset_id, sha, Issue(PackageProblem.MISSING_MANIFEST, f"{MANIFEST} is unreadable: {exc}")
        )

    issues: list[Issue] = []
    if manifest.get("package_type", "OBSERVATIONS") != "OBSERVATIONS":
        issues.append(
            Issue(
                PackageProblem.WRONG_PACKAGE_TYPE,
                f"package_type is {manifest.get('package_type')}, expected OBSERVATIONS",
            )
        )
    declared_schema = str(manifest.get("schema_version", ""))
    if not _schema_readable(declared_schema):
        issues.append(
            Issue(
                PackageProblem.UNSUPPORTED_SCHEMA_MAJOR,
                f"schema_version {declared_schema!r} has a major this build cannot read "
                f"(supports {SCHEMA_VERSION})",
            )
        )

    issues.extend(_verify_checksums(entries))

    if OBSERVATIONS_JSON not in entries:
        issues.append(Issue(PackageProblem.MISSING_OBSERVATIONS, f"{OBSERVATIONS_JSON} is absent"))
        return _empty(dataset_id, sha, *issues, manifest=manifest)

    observations, decode_issues, rows_read, invalid = _decode_json(entries[OBSERVATIONS_JSON])
    issues.extend(decode_issues)

    if OBSERVATIONS_CSV in entries:
        issues.extend(_cross_check_csv(entries[OBSERVATIONS_CSV], observations))

    issues.extend(_check_manifest_counts(manifest, observations))
    issues.extend(_check_single_observer(manifest, observations))

    observer = _read_json_object(entries.get(OBSERVER))
    sessions = _read_json_array(entries.get(SESSIONS))

    return PackageContents(
        dataset_id=dataset_id,
        package_sha256=sha,
        manifest=manifest,
        observer=observer,
        sessions=sessions,
        observations=observations,
        issues=tuple(issues),
        rows_read=rows_read,
        invalid_rows=invalid,
    )


# -- validation -----------------------------------------------------------------------------------


def _verify_checksums(entries: dict[str, bytes]) -> list[Issue]:
    if CHECKSUM not in entries:
        return [Issue(PackageProblem.CHECKSUM_MISSING, f"{CHECKSUM} is absent")]
    declared = parse_checksum_file(entries[CHECKSUM].decode("utf-8", errors="replace"))
    if not declared:
        return [Issue(PackageProblem.CHECKSUM_MISSING, f"{CHECKSUM} lists no files")]

    issues: list[Issue] = []
    for name, expected in sorted(declared.items()):
        content = entries.get(name)
        if content is None:
            issues.append(
                Issue(PackageProblem.CHECKSUM_MISMATCH, f"{CHECKSUM} lists missing file '{name}'")
            )
        elif sha256_hex(content).lower() != expected.lower():
            issues.append(
                Issue(PackageProblem.CHECKSUM_MISMATCH, f"'{name}' does not match its declared sha256")
            )
    return issues


def _decode_json(payload: bytes) -> tuple[tuple[Observation, ...], list[Issue], int, int]:
    try:
        rows = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        return (), [Issue(PackageProblem.MISSING_OBSERVATIONS, f"{OBSERVATIONS_JSON}: {exc}")], 0, 0
    if not isinstance(rows, list):
        return (
            (),
            [Issue(PackageProblem.MISSING_OBSERVATIONS, f"{OBSERVATIONS_JSON} is not an array")],
            0,
            0,
        )

    observations: list[Observation] = []
    issues: list[Issue] = []
    invalid = 0
    for index, row in enumerate(rows):
        try:
            observations.append(observation_from_json(row))
        except (ValueError, TypeError, AttributeError) as exc:
            invalid += 1
            # Only the first few are reported by message; the count carries the rest. A package
            # with 12,000 broken rows should produce one legible finding, not 12,000.
            if invalid <= 5:
                issues.append(
                    Issue(PackageProblem.MALFORMED_ROW, f"{OBSERVATIONS_JSON}[{index}]: {exc}")
                )
    if invalid > 5:
        issues.append(
            Issue(
                PackageProblem.MALFORMED_ROW,
                f"{invalid} rows in {OBSERVATIONS_JSON} could not be decoded",
            )
        )
    return tuple(observations), issues, len(rows), invalid


def _cross_check_csv(payload: bytes, from_json: tuple[Observation, ...]) -> list[Issue]:
    """Compare the two views of the same data by observation id, and report any divergence.

    Deliberately not a repair: if the CSV and the JSON disagree about what was collected, something
    upstream is wrong and picking a winner would hide it.
    """
    result = csv_reader.read_csv(payload.decode("utf-8", errors="replace"))
    issues: list[Issue] = []
    if result.checksum_mismatches:
        issues.append(
            Issue(
                PackageProblem.ROW_CHECKSUM_MISMATCH,
                f"{len(result.checksum_mismatches)} CSV rows fail their own row_checksum "
                f"(first at line {result.checksum_mismatches[0]})",
            )
        )
    if result.failures:
        issues.append(
            Issue(
                PackageProblem.MALFORMED_ROW,
                f"{len(result.failures)} CSV rows could not be decoded "
                f"(first at line {result.failures[0].row_number}: "
                f"{result.failures[0].reasons[0]})",
            )
        )

    json_ids = {o.observation_id for o in from_json}
    csv_ids = {o.observation_id for o in result.observations}
    only_json = json_ids - csv_ids
    only_csv = csv_ids - json_ids
    if only_json or only_csv:
        issues.append(
            Issue(
                PackageProblem.CSV_JSON_MISMATCH,
                f"{len(only_json)} observations appear only in JSON and {len(only_csv)} only in CSV",
            )
        )
    return issues


def _check_manifest_counts(manifest: dict[str, Any], observations: tuple[Observation, ...]) -> list[Issue]:
    declared = manifest.get("observation_count")
    if not isinstance(declared, int) or declared == len(observations):
        return []
    return [
        Issue(
            PackageProblem.MANIFEST_COUNT_MISMATCH,
            f"manifest declares {declared} observations but the package carries {len(observations)}",
        )
    ]


def _check_single_observer(manifest: dict[str, Any], observations: tuple[Observation, ...]) -> list[Issue]:
    """A package belongs to exactly one observer (``docs/16`` §5).

    A stray row from another installation means either a merged file or a mislabelled export, and
    either way the observer attribution in the package cannot be trusted.
    """
    declared = manifest.get("observer_id")
    if not declared:
        return []
    stray = sorted({o.observer_id for o in observations if o.observer_id != declared})
    if not stray:
        return []
    return [
        Issue(
            PackageProblem.FOREIGN_OBSERVER,
            f"package declares observer {declared} but contains rows from {', '.join(stray)}",
        )
    ]


# -- decoding -------------------------------------------------------------------------------------


def observation_from_json(row: Any) -> Observation:
    """Decode one canonical observation object.

    Unknown keys are ignored, which is the forward-compatibility rule: a minor schema version may
    add optional fields and an older reader must still accept the package.
    """
    if not isinstance(row, dict):
        raise ValueError("observation is not an object")

    timestamp = _require_str(row, "timestamp_utc")
    return Observation(
        observation_id=_require_str(row, "observation_id"),
        schema_version=str(row.get("schema_version") or ""),
        timestamp_utc=timestamp,
        timestamp_ms=parse_ms(timestamp),
        observer_id=_require_str(row, "observer_id"),
        observer_device_type=str(row.get("observer_device_type") or ""),
        sensor_type=SensorType(_require_str(row, "sensor_type")),
        radio_identifier=_require_str(row, "radio_identifier"),
        identifier_type=IdentifierType(_require_str(row, "identifier_type")),
        rssi=_optional_int(row, "rssi"),
        ssid=_optional_str(row, "ssid"),
        bssid=_optional_str(row, "bssid"),
        ble_service_uuid=_optional_str(row, "ble_service_uuid"),
        manufacturer_data=_optional_str(row, "manufacturer_data"),
        frequency=_optional_int(row, "frequency"),
        channel=_optional_int(row, "channel"),
        tx_power=_optional_int(row, "tx_power"),
        rtt_distance_mm=_optional_int(row, "rtt_distance_mm"),
        rtt_stddev_mm=_optional_int(row, "rtt_stddev_mm"),
        latitude=_optional_float(row, "latitude"),
        longitude=_optional_float(row, "longitude"),
        location_accuracy_m=_optional_float(row, "horizontal_accuracy"),
        building_id=_optional_str(row, "building_id"),
        zone_id=_optional_str(row, "zone_id"),
        observer_x=_optional_float(row, "x_coordinate"),
        observer_y=_optional_float(row, "y_coordinate"),
        target_device_id=_optional_str(row, "target_device_id"),
        confidence=_optional_float(row, "confidence"),
        metadata={
            str(key): str(value)
            for key, value in (row.get("metadata") or {}).items()
            if value is not None
        },
    )


def _require_str(row: dict[str, Any], key: str) -> str:
    value = row.get(key)
    if not isinstance(value, str) or not value:
        raise ValueError(f"{key} is required")
    return value


def _optional_str(row: dict[str, Any], key: str) -> str | None:
    value = row.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{key} must be a string")
    return value or None


def _optional_int(row: dict[str, Any], key: str) -> int | None:
    value = row.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be a number")
    if isinstance(value, float) and not value.is_integer():
        raise ValueError(f"{key} must be an integer")
    return int(value)


def _optional_float(row: dict[str, Any], key: str) -> float | None:
    value = row.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be a number")
    parsed = float(value)
    if parsed != parsed or parsed in (float("inf"), float("-inf")):
        raise ValueError(f"{key} must be finite")
    return parsed


def _read_entries(raw: bytes) -> dict[str, bytes]:
    import io

    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        return {
            info.filename: archive.read(info)
            for info in archive.infolist()
            if not info.is_dir()
        }


def _read_json_object(payload: bytes | None) -> dict[str, Any]:
    if not payload:
        return {}
    try:
        parsed = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _read_json_array(payload: bytes | None) -> tuple[dict[str, Any], ...]:
    if not payload:
        return ()
    try:
        parsed = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return ()
    if not isinstance(parsed, list):
        return ()
    return tuple(item for item in parsed if isinstance(item, dict))


def _schema_readable(declared: str) -> bool:
    """Accept unknown minors and patches; refuse an unknown major (``docs/03`` §4.3)."""
    try:
        declared_major = int(declared.split(".", 1)[0])
    except (ValueError, AttributeError):
        return False
    return declared_major == int(SCHEMA_VERSION.split(".", 1)[0])


def _empty(
    dataset_id: str,
    sha: str,
    *issues: Issue,
    manifest: dict[str, Any] | None = None,
) -> PackageContents:
    return PackageContents(
        dataset_id=dataset_id,
        package_sha256=sha,
        manifest=manifest or {},
        observer={},
        sessions=(),
        observations=(),
        issues=tuple(issues),
    )


__all__ = ["Issue", "PackageContents", "PackageProblem", "observation_from_json", "read_package"]
