"""ISO-8601 UTC handling, matching ``core-model/Iso8601.kt`` exactly.

One format, everywhere: ``YYYY-MM-DDTHH:MM:SS.sssZ``. Local time never enters this process — a
timestamp that has been through a local zone cannot be compared against one that has not, and the
two are indistinguishable once written.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"

EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def format_ms(epoch_ms: int) -> str:
    """Render epoch milliseconds as the canonical instant string."""
    moment = EPOCH + timedelta(milliseconds=int(epoch_ms))
    return f"{moment.strftime('%Y-%m-%dT%H:%M:%S')}.{moment.microsecond // 1000:03d}Z"


def parse_ms(text: str) -> int:
    """Parse a canonical instant to epoch milliseconds.

    Tolerant on input — a package may legitimately carry ``Z``, ``+00:00`` or no sub-second part —
    and strict on output, because everything downstream compares integers.
    """
    value = text.strip()
    if not value:
        raise ValueError("empty timestamp")
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    try:
        moment = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"not an ISO-8601 instant: {text!r}") from exc
    if moment.tzinfo is None:
        # A naive timestamp in this system is a bug upstream, but assuming UTC is the only reading
        # consistent with the contract, and silently shifting it by a local offset would be worse.
        moment = moment.replace(tzinfo=timezone.utc)
    return int((moment - EPOCH).total_seconds() * 1000)


def date_stamp(epoch_ms: int) -> str:
    """``YYYY-MM-DD`` in UTC, used for package names and daily partitioning."""
    return format_ms(epoch_ms)[:10]


def day_bounds(date_stamp_utc: str) -> tuple[int, int]:
    """Inclusive epoch-millisecond bounds of one UTC day."""
    start = parse_ms(f"{date_stamp_utc}T00:00:00.000Z")
    return start, start + 86_400_000 - 1


def is_instant(text: str) -> bool:
    try:
        parse_ms(text)
        return True
    except ValueError:
        return False
