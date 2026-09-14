"""Canonical serialization, digests and deterministic identifiers.

Two properties are load-bearing here.

*Byte-identical re-runs.* ``docs/14-algorithm-versioning-strategy.md`` §5 requires that the same
inputs, versions and parameters produce the same output bytes, so ``checksum.txt`` means something
and two generations can be diffed. That rules out ``uuid4`` and dictionary iteration order as
sources of variation, so record ids are content-derived (``uuid5``) and every mapping is written in
a fixed order.

*Matching the Kotlin writer.* ``core-model/RfMapperJson.kt`` emits explicit nulls with two-space
indentation, so a package produced here and one produced there are the same shape and a reviewer
can diff them.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any, Mapping

#: Namespace for content-derived record ids. Fixed forever: changing it would renumber every
#: historical record and break the idempotency the Master relies on.
NAMESPACE = uuid.UUID("6f1c8a6e-0f2a-5b3c-9d44-7e1b2c3d4e5f")


def dumps(value: Any, *, pretty: bool = True) -> str:
    """Serialize with the project's dialect: UTF-8, LF, explicit nulls, stable key order."""
    if pretty:
        return json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def dumps_sorted(value: Any) -> str:
    """Compact form with sorted keys, for hashing and for CSV object columns.

    Sorting is what makes ``metadata_json`` and ``row_checksum`` reproducible
    (``docs/03-csv-column-specification.md`` §3).
    """
    return json.dumps(value, separators=(",", ":"), sort_keys=True, ensure_ascii=False, allow_nan=False)


def encode(value: Any, *, pretty: bool = True) -> bytes:
    """JSON bytes with the trailing newline the Kotlin writer also emits."""
    return (dumps(value, pretty=pretty) + "\n").encode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def checksum_file(digests: Mapping[str, str]) -> str:
    """``checksum.txt`` in ``sha256sum`` format, sorted by filename.

    Byte-compatible with ``core-export/Sha256.kt`` so ``sha256sum -c`` works on either package.
    """
    lines = [f"{digests[name]}  {name}" for name in sorted(digests)]
    return "\n".join(lines) + "\n"


def parse_checksum_file(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        separator = line.find("  ")
        if separator <= 0:
            continue
        digest, name = line[:separator].strip(), line[separator + 2 :].strip()
        if digest and name:
            result[name] = digest
    return result


def derive_id(kind: str, *parts: Any) -> str:
    """A stable uuid derived from the content that justifies the record.

    Re-running the pipeline over the same evidence with the same versions yields the same id, which
    is what makes the Master's import idempotent on ``estimate_id`` rather than merely deduplicated
    by luck.
    """
    key = "|".join([kind, *(str(part) for part in parts)])
    return str(uuid.uuid5(NAMESPACE, key))


def clean(value: Any) -> Any:
    """Coerce numpy scalars and sets into plain JSON types.

    NumPy leaks ``float64`` and ``int64`` through every statistical function, and ``json`` refuses
    them. Doing the conversion in one place beats casting at 40 call sites and missing three.
    """
    if isinstance(value, dict):
        return {str(k): clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        items = sorted(value) if isinstance(value, (set, frozenset)) else value
        return [clean(item) for item in items]
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return value
    if hasattr(value, "item") and not isinstance(value, (int, float)):
        return clean(value.item())
    if isinstance(value, float):
        return round(float(value), 6)
    if isinstance(value, int):
        return int(value)
    if hasattr(value, "value"):  # enum
        return value.value
    return value


def as_evidence(data: Mapping[str, Any]) -> dict[str, str]:
    """Render quality-flag evidence as strings.

    ``QualityFlag.evidence`` is ``Map<String, String>`` on the Kotlin side, so a numeric value here
    would fail to decode there. Stringifying at the boundary keeps the contract honest instead of
    discovering the mismatch during an import.
    """
    rendered: dict[str, str] = {}
    for key in sorted(data):
        value = data[key]
        if isinstance(value, bool):
            rendered[key] = "true" if value else "false"
        elif isinstance(value, float):
            rendered[key] = format_decimal(value)
        elif value is None:
            rendered[key] = ""
        elif isinstance(value, (list, tuple, set, frozenset)):
            rendered[key] = ";".join(str(item) for item in clean(value))
        else:
            rendered[key] = str(value)
    return rendered


def format_decimal(value: float, places: int = 6) -> str:
    """Decimal text per the CSV dialect: no exponent, trailing zeros trimmed."""
    text = f"{value:.{places}f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"
