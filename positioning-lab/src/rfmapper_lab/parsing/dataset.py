"""Global deduplication and the immutable dataset every later phase reads.

Deduplication is on ``observation_id`` and the first occurrence wins
(``docs/10-positioning-mathematical-architecture.md`` §3.2). Re-exports overlap by design — the
Collector's day export and its session export contain the same rows — so duplicates are routine and
counted, not reported as errors.

A duplicate id whose *content* differs is the opposite: it means two different measurements were
assigned the same id, which is a real anomaly worth a human's attention. It gets its own counter
and its own flag, ``DUPLICATE_ID_CONTENT_MISMATCH``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

from ..jsonio import dumps_sorted, sha256_hex
from ..models import Observation, ReferenceModel
from .package_reader import Issue, PackageContents, read_package


@dataclass(frozen=True, slots=True)
class IngestStats:
    packages: int = 0
    rows_read: int = 0
    rows_accepted: int = 0
    duplicates: int = 0
    invalid: int = 0
    duplicate_id_content_mismatch: int = 0
    rejected_packages: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, object]:
        return {
            "packages": self.packages,
            "rows_read": self.rows_read,
            "rows_accepted": self.rows_accepted,
            "duplicates": self.duplicates,
            "invalid": self.invalid,
            "duplicate_id_content_mismatch": self.duplicate_id_content_mismatch,
            "rejected_packages": list(self.rejected_packages),
        }


@dataclass(frozen=True, slots=True)
class Dataset:
    """The deduplicated RAW layer for a run, plus the REFERENCE model it is interpreted against."""

    observations: tuple[Observation, ...]
    reference: ReferenceModel
    source_dataset_ids: tuple[str, ...]
    stats: IngestStats
    issues: tuple[Issue, ...] = ()
    observer_declarations: dict[str, dict] = field(default_factory=dict)
    sessions: tuple[dict, ...] = ()

    @property
    def content_sha256(self) -> str:
        """Content hash over the accepted rows and the reference model id.

        Recorded in every benchmark report so two runs over different data cannot be compared by
        accident (``docs/12-benchmark-methodology-and-error-metrics.md`` §2.1).
        """
        digest_input = dumps_sorted(
            {
                "reference_model_id": self.reference.reference_model_id,
                "observation_ids": [o.observation_id for o in self.observations],
            }
        )
        return sha256_hex(digest_input.encode("utf-8"))

    def time_range(self) -> tuple[int, int] | None:
        if not self.observations:
            return None
        return self.observations[0].timestamp_ms, self.observations[-1].timestamp_ms

    def ground_truth(self) -> tuple[Observation, ...]:
        """Survey samples only.

        Phase 3 reads nothing else. An ordinary daily observation can report that calibration is
        weak, but it can never redefine ground truth
        (``docs/11-ground-truth-and-calibration-procedure.md`` §1).
        """
        return tuple(o for o in self.observations if o.is_ground_truth)

    def ordinary(self) -> tuple[Observation, ...]:
        return tuple(o for o in self.observations if not o.is_ground_truth)

    def within(self, from_ms: int, to_ms: int) -> "Dataset":
        kept = tuple(o for o in self.observations if from_ms <= o.timestamp_ms <= to_ms)
        return Dataset(
            observations=kept,
            reference=self.reference,
            source_dataset_ids=self.source_dataset_ids,
            stats=self.stats,
            issues=self.issues,
            observer_declarations=self.observer_declarations,
            sessions=self.sessions,
        )


def _content_key(observation: Observation) -> str:
    """Everything that makes a measurement itself, excluding its id."""
    return dumps_sorted(
        {
            "timestamp_utc": observation.timestamp_utc,
            "observer_id": observation.observer_id,
            "sensor_type": observation.sensor_type.value,
            "radio_identifier": observation.radio_identifier,
            "identifier_type": observation.identifier_type.value,
            "rssi": observation.rssi,
            "rtt_distance_mm": observation.rtt_distance_mm,
            "rtt_stddev_mm": observation.rtt_stddev_mm,
        }
    )


def deduplicate(
    batches: Iterable[Sequence[Observation]],
) -> tuple[tuple[Observation, ...], int, int]:
    """First occurrence of an id wins. Returns the kept rows, duplicate and mismatch counts."""
    kept: dict[str, Observation] = {}
    content: dict[str, str] = {}
    duplicates = 0
    mismatches = 0

    for batch in batches:
        for observation in batch:
            existing = kept.get(observation.observation_id)
            if existing is None:
                kept[observation.observation_id] = observation
                content[observation.observation_id] = _content_key(observation)
                continue
            duplicates += 1
            if content[observation.observation_id] != _content_key(observation):
                mismatches += 1

    ordered = tuple(
        sorted(kept.values(), key=lambda o: (o.timestamp_ms, o.observation_id))
    )
    return ordered, duplicates, mismatches


def load_packages(paths: Sequence[Path]) -> tuple[PackageContents, ...]:
    return tuple(read_package(path) for path in sorted(paths))


def load_dataset(
    packages: Sequence[PackageContents],
    reference: ReferenceModel,
) -> Dataset:
    """Assemble the RAW layer from already-read packages.

    A package with a blocking problem contributes no rows. It is named in ``rejected_packages`` so
    a missing observer in the day's output has a traceable cause rather than looking like an
    observer that simply collected nothing.
    """
    usable = [package for package in packages if package.usable]
    rejected = tuple(sorted(p.dataset_id for p in packages if not p.usable))

    observations, duplicates, mismatches = deduplicate(p.observations for p in usable)

    stats = IngestStats(
        packages=len(packages),
        rows_read=sum(max(p.rows_read, len(p.observations)) for p in packages),
        rows_accepted=len(observations),
        duplicates=duplicates,
        invalid=sum(p.invalid_rows for p in packages),
        duplicate_id_content_mismatch=mismatches,
        rejected_packages=rejected,
    )

    issues = tuple(issue for package in packages for issue in package.issues)
    declarations = {
        package.observer_id: package.observer
        for package in usable
        if package.observer_id and package.observer
    }
    sessions = tuple(session for package in usable for session in package.sessions)

    return Dataset(
        observations=observations,
        reference=reference,
        source_dataset_ids=tuple(sorted(p.dataset_id for p in usable)),
        stats=stats,
        issues=issues,
        observer_declarations=declarations,
        sessions=sessions,
    )
