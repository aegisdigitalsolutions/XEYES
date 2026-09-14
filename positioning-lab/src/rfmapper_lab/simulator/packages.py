"""Writes synthetic ``OBS<id>_<date>.zip`` packages, per ``docs/16-export-package-specification.md``.

In production the Collector writes these and the Lab only reads them, so a writer here is a test
fixture rather than a component. It earns its place by letting the demo drive the whole ingest
path — zip entries, ``checksum.txt``, per-row checksums, the JSON-versus-CSV cross-check, one
observer per package, global deduplication — instead of handing the pipeline an in-memory list and
leaving Phase 1 unexercised until a real export exists.

What it cannot do is prove the Lab agrees with the Kotlin Collector. Both sides are written from the
same specification, and only a package produced by the app and read here can settle that; the
round-trip test on the Android side covers the other direction.
"""

from __future__ import annotations

import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

from ..jsonio import checksum_file, encode, sha256_hex
from ..models import Observation
from ..parsing.csv_reader import HEADER, encode_row
from ..timeutil import date_stamp, format_ms
from ..version import LAB_NAME, LAB_VERSION, SCHEMA_VERSION

MANIFEST = "manifest.json"
OBSERVATIONS_JSON = "observations.json"
OBSERVATIONS_CSV = "observations.csv"
OBSERVER = "observer.json"
SESSIONS = "sessions.json"
CHECKSUM = "checksum.txt"

#: Same fixed member timestamp as the derived writer, for the same reason: a wall-clock mtime would
#: change the archive bytes on every run and make byte-identical reproduction impossible for a
#: reason that has nothing to do with the data.
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


@dataclass(frozen=True, slots=True)
class WrittenPackage:
    path: Path
    observer_id: str
    observation_count: int
    package_sha256: str


def write_packages(
    observations: Sequence[Observation],
    destination: Path,
    observers: dict[str, dict] | None = None,
) -> tuple[WrittenPackage, ...]:
    """One package per observer per UTC day, which is how the Collector exports.

    Splitting by day matters for more than tidiness: it is what makes the duplicate counter
    meaningful. A day export and a session export of the same evening overlap by design, and the
    Lab has to treat that as routine rather than as corruption.
    """
    destination.mkdir(parents=True, exist_ok=True)
    grouped: dict[tuple[str, str], list[Observation]] = {}
    for observation in observations:
        key = (observation.observer_id, date_stamp(observation.timestamp_ms))
        grouped.setdefault(key, []).append(observation)

    written: list[WrittenPackage] = []
    for observer_id, stamp in sorted(grouped):
        rows = sorted(
            grouped[(observer_id, stamp)],
            key=lambda o: (o.timestamp_ms, o.observation_id),
        )
        written.append(
            _write_one(
                rows=rows,
                observer_id=observer_id,
                stamp=stamp,
                destination=destination,
                observer=(observers or {}).get(observer_id),
            )
        )
    return tuple(written)


def _write_one(
    rows: Sequence[Observation],
    observer_id: str,
    stamp: str,
    destination: Path,
    observer: dict | None,
) -> WrittenPackage:
    path = destination / f"OBS{_slug(observer_id)}_{stamp}.zip"
    sessions = sorted({row.session_id for row in rows if row.session_id})

    entries: list[tuple[str, bytes]] = [
        (MANIFEST, encode(_manifest(rows, observer_id, stamp, sessions))),
        (OBSERVATIONS_JSON, encode([_row_to_dict(row) for row in rows])),
        (OBSERVATIONS_CSV, _csv(rows)),
        (OBSERVER, encode(observer or _observer_block(observer_id))),
        (
            SESSIONS,
            encode(
                [
                    {
                        "session_id": session,
                        "observer_id": observer_id,
                        "started_at": format_ms(rows[0].timestamp_ms),
                        "ended_at": format_ms(rows[-1].timestamp_ms),
                        "observation_count": sum(
                            1 for row in rows if row.session_id == session
                        ),
                    }
                    for session in sessions
                ]
            ),
        ),
    ]

    digests = {name: sha256_hex(payload) for name, payload in entries}
    entries.append((CHECKSUM, checksum_file(digests).encode("utf-8")))

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)

    return WrittenPackage(
        path=path,
        observer_id=observer_id,
        observation_count=len(rows),
        package_sha256=sha256_hex(path.read_bytes()),
    )


def _manifest(
    rows: Sequence[Observation],
    observer_id: str,
    stamp: str,
    sessions: Sequence[str],
) -> dict:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.sensor_type.value] = counts.get(row.sensor_type.value, 0) + 1
    return {
        "schema_version": SCHEMA_VERSION,
        "package_type": "OBSERVATIONS",
        "export_id": f"sim-{_slug(observer_id)}-{stamp}",
        "observer_id": observer_id,
        "created_at": format_ms(rows[-1].timestamp_ms),
        "date_range": {
            "from": format_ms(rows[0].timestamp_ms),
            "to": format_ms(rows[-1].timestamp_ms),
        },
        "observation_count": len(rows),
        "sensor_counts": dict(sorted(counts.items())),
        "session_ids": list(sessions),
        "generator": {"name": f"{LAB_NAME}.simulator", "version": LAB_VERSION},
    }


def _observer_block(observer_id: str) -> dict:
    return {
        "observer_id": observer_id,
        "friendly_name": observer_id,
        "platform": "ANDROID",
        "observer_device_type": "ANDROID_PHONE",
        "app_version": LAB_VERSION,
    }


def _row_to_dict(observation: Observation) -> dict:
    """The canonical observation object, keyed as ``docs/02`` names the fields.

    ``x_coordinate`` and ``horizontal_accuracy`` are the schema's names for what the Lab's model
    calls ``observer_x`` and ``location_accuracy_m``. The reader maps them back; writing the
    model's own field names here would produce a package the reader silently reads as having no
    observer position.
    """
    o = observation
    return {
        "observation_id": o.observation_id,
        "schema_version": o.schema_version or SCHEMA_VERSION,
        "timestamp_utc": o.timestamp_utc,
        "observer_id": o.observer_id,
        "observer_device_type": o.observer_device_type,
        "sensor_type": o.sensor_type.value,
        "target_device_id": o.target_device_id,
        "radio_identifier": o.radio_identifier,
        "identifier_type": o.identifier_type.value,
        "ssid": o.ssid,
        "bssid": o.bssid,
        "ble_service_uuid": o.ble_service_uuid,
        "manufacturer_data": o.manufacturer_data,
        "rssi": o.rssi,
        "tx_power": o.tx_power,
        "frequency": o.frequency,
        "channel": o.channel,
        "rtt_distance_mm": o.rtt_distance_mm,
        "rtt_stddev_mm": o.rtt_stddev_mm,
        "latitude": o.latitude,
        "longitude": o.longitude,
        "horizontal_accuracy": o.location_accuracy_m,
        "building_id": o.building_id,
        "zone_id": o.zone_id,
        "x_coordinate": o.observer_x,
        "y_coordinate": o.observer_y,
        "confidence": o.confidence,
        "metadata": dict(sorted(o.metadata.items())),
    }


def _csv(rows: Iterable[Observation]) -> bytes:
    body = "".join(f"{encode_row(row)}\n" for row in rows)
    return (HEADER + "\n" + body).encode("utf-8")


def _slug(observer_id: str) -> str:
    return "".join(character for character in observer_id if character.isalnum())


__all__ = ["WrittenPackage", "write_packages"]
