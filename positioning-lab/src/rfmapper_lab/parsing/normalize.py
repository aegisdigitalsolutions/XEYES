"""Identifier and signal normalization, mirroring ``core-model/RadioIdentifierNormalizer.kt``.

Deduplication and fingerprint matching compare identifiers byte for byte, so this has to agree with
the Kotlin implementation exactly. Two spellings of one MAC address would otherwise be two distinct
radio sources, which silently halves a fingerprint's sample count and weakens every match that
depends on it.
"""

from __future__ import annotations

import re

from ..models import IdentifierType, ObserverCalibration, SensorType

BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

#: Android has historically reported these instead of null for an unknown BSSID. Keeping one would
#: create a phantom access point apparently visible across the entire site.
UNKNOWN_MAC_SENTINELS = frozenset({"02:00:00:00:00:00", "00:00:00:00:00:00"})

_MAC_CANONICAL = re.compile(r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
_UUID_CANONICAL = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_HEX = re.compile(r"^[0-9a-fA-F]+$")
_SEPARATORS = re.compile(r"[:\-.]")


def normalize_mac(raw: str | None) -> str | None:
    """Lowercase, colon-separated, zero-padded octets. ``None`` when not a 6-octet address."""
    if raw is None:
        return None
    trimmed = raw.strip().lower()
    if not trimmed or trimmed in UNKNOWN_MAC_SENTINELS:
        return None
    if _MAC_CANONICAL.match(trimmed):
        return trimmed

    parts = [part for part in _SEPARATORS.split(trimmed) if part]
    if len(parts) == 6:
        octets = parts
    elif len(parts) == 1 and len(trimmed) == 12:
        octets = [trimmed[i : i + 2] for i in range(0, 12, 2)]
    else:
        return None
    if any(len(octet) > 2 or not _HEX.match(octet) for octet in octets):
        return None
    return ":".join(octet.rjust(2, "0") for octet in octets)


def normalize_uuid(raw: str | None) -> str | None:
    """Lowercase 8-4-4-4-12, expanding 16- and 32-bit Bluetooth short UUIDs against the base UUID."""
    if raw is None:
        return None
    trimmed = raw.strip().lower().removeprefix("0x")
    if not trimmed:
        return None
    if _UUID_CANONICAL.match(trimmed):
        return trimmed

    compact = trimmed.replace("-", "")
    if not compact or not _HEX.match(compact):
        return None
    if len(compact) == 4:
        return f"0000{compact}{BLUETOOTH_BASE_SUFFIX}"
    if len(compact) == 8:
        return f"{compact}{BLUETOOTH_BASE_SUFFIX}"
    if len(compact) == 32:
        return (
            f"{compact[0:8]}-{compact[8:12]}-{compact[12:16]}-{compact[16:20]}-{compact[20:32]}"
        )
    return None


def normalize_hex(raw: str | None) -> str | None:
    """Uppercase hex without separators; ``None`` rather than an empty string."""
    if raw is None:
        return None
    compact = re.sub(r"[\s:\-]", "", raw).upper()
    if not compact or not _HEX.match(compact):
        return None
    return compact


def normalize_identifier(raw: str, identifier_type: IdentifierType) -> str:
    """Normalize per identifier type, falling back to the raw value when it is unrecognisable.

    Falling back rather than dropping is deliberate: an identifier this function cannot parse is
    still evidence of *something*, and discarding it would lose an observation. It simply will not
    match a normalized identifier, which is the correct outcome.
    """
    if identifier_type in (
        IdentifierType.WIFI_BSSID,
        IdentifierType.BLE_MAC_PUBLIC,
        IdentifierType.BLE_MAC_RANDOM,
    ):
        return normalize_mac(raw) or raw.strip().lower()
    if identifier_type in (IdentifierType.BLE_SERVICE_UUID, IdentifierType.BLE_IBEACON):
        return normalize_uuid(raw) or raw.strip().lower()
    return raw.strip()


def is_random_ble_address(normalized_mac: str) -> bool:
    """Locally administered bit (0x02) of the first octet: how both platforms present a rotating address."""
    head = normalized_mac.split(":", 1)[0]
    try:
        return bool(int(head, 16) & 0x02)
    except ValueError:
        return False


def offset_for(
    observer_id: str,
    calibration: dict[str, ObserverCalibration],
    max_spread_db: float,
) -> tuple[float, bool]:
    """The RSSI offset to apply for an observer, and whether one was actually applied.

    An offset whose per-source spread is wide is antenna-pattern difference rather than a scalar
    gain error (``docs/11-ground-truth-and-calibration-procedure.md`` §4). Applying it would make
    results worse, so it is stored, declined, and reported.
    """
    entry = calibration.get(observer_id)
    if entry is None:
        return 0.0, False
    if not entry.is_applicable(max_spread_db):
        return 0.0, False
    return float(entry.rssi_offset_db), True


def normalized_rssi(
    rssi: int | None,
    observer_id: str,
    calibration: dict[str, ObserverCalibration],
    max_spread_db: float,
) -> float | None:
    """``r~ = r + delta_o``. The raw value is always preserved alongside it."""
    if rssi is None:
        return None
    offset, _ = offset_for(observer_id, calibration, max_spread_db)
    return float(rssi) + offset


def window_ms(sensor: SensorType, fusion_params) -> int:
    """Fusion window width for a sensor type.

    Per-sensor because the cadences differ by an order of magnitude: a Wi-Fi scan arrives every few
    seconds, a BLE advertisement many times a second. A single window would either throw away most
    Wi-Fi evidence or fuse BLE readings that were never simultaneous.
    """
    if sensor in (SensorType.WIFI_SCAN, SensorType.WIFI_ASSOCIATION):
        return fusion_params.window_ms_wifi
    if sensor is SensorType.BLE:
        return fusion_params.window_ms_ble
    if sensor is SensorType.RTT:
        return fusion_params.window_ms_rtt
    return fusion_params.window_ms_default
