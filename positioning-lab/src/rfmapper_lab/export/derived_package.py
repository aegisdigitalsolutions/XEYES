"""Writes ``DERIVED_<YYYY-MM-DD>.zip``, per ``docs/13-derived-output-schema.md``.

The package is deterministic: fixed entry order, fixed zip timestamps, sorted maps and stable record
ids. Two runs over the same inputs produce identical bytes, which is what makes ``checksum.txt``
worth having and what lets two generations be diffed rather than merely compared.

``checksum.txt`` is written last, so its presence means every other entry completed. A truncated
package is then detectable as a missing checksum file rather than as a subtly short CSV.
"""

from __future__ import annotations

import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

from ..jsonio import checksum_file, clean, encode, sha256_hex
from ..pipeline import PipelineResult, generator_block
from ..timeutil import date_stamp, format_ms
from ..version import SCHEMA_VERSION, engine_versions
from .serialize import (
    ESTIMATE_COLUMNS,
    TRANSITION_COLUMNS,
    estimate_csv_row,
    estimate_to_dict,
    movement_to_dict,
    transition_csv_row,
    transition_to_dict,
)

MANIFEST = "manifest.json"
ESTIMATES_JSON = "position_estimates.json"
ESTIMATES_CSV = "position_estimates.csv"
TRANSITIONS_JSON = "zone_transitions.json"
TRANSITIONS_CSV = "zone_transitions.csv"
MOVEMENTS_JSON = "movement_estimates.json"
QUALITY_JSON = "quality_report.json"
ALGORITHM_JSON = "algorithm_report.json"
CHECKSUM = "checksum.txt"

#: Fixed zip member timestamp. A wall-clock mtime would change the archive bytes on every run and
#: make byte-identical reproduction impossible for a reason unrelated to the data.
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


@dataclass(frozen=True, slots=True)
class DerivedPackage:
    path: Path
    package_name: str
    package_sha256: str
    entry_digests: dict[str, str]
    counts: dict[str, int]

    def as_dict(self) -> dict[str, Any]:
        return {
            "package_name": self.package_name,
            "package_sha256": self.package_sha256,
            "counts": dict(sorted(self.counts.items())),
            "entries": dict(sorted(self.entry_digests.items())),
        }


def write_derived_package(
    result: PipelineResult,
    destination: Path,
    algorithm_report: dict[str, Any] | None = None,
    package_name: str | None = None,
) -> DerivedPackage:
    """Write one derived package for a completed pipeline run."""
    destination.mkdir(parents=True, exist_ok=True)

    span = result.dataset.time_range()
    stamp = date_stamp(span[0]) if span else date_stamp(result.config.computed_at_ms)
    name = package_name or f"DERIVED_{stamp}.zip"
    path = destination / name

    entries: list[tuple[str, bytes]] = [
        (MANIFEST, encode(_manifest(result, stamp))),
        (ESTIMATES_JSON, encode([estimate_to_dict(e) for e in result.estimates])),
        (ESTIMATES_CSV, _csv(ESTIMATE_COLUMNS, (estimate_csv_row(e) for e in result.estimates))),
        (TRANSITIONS_JSON, encode([transition_to_dict(t) for t in result.transitions])),
        (
            TRANSITIONS_CSV,
            _csv(TRANSITION_COLUMNS, (transition_csv_row(t) for t in result.transitions)),
        ),
        (MOVEMENTS_JSON, encode([movement_to_dict(m) for m in result.movements])),
        (QUALITY_JSON, encode(result.quality.as_dict())),
        (ALGORITHM_JSON, encode(clean(algorithm_report or _minimal_algorithm_report(result)))),
    ]

    digests = {name: sha256_hex(payload) for name, payload in entries}
    entries.append((CHECKSUM, checksum_file(digests).encode("utf-8")))

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for entry_name, payload in entries:
            info = zipfile.ZipInfo(entry_name, date_time=FIXED_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)

    return DerivedPackage(
        path=path,
        package_name=name,
        package_sha256=sha256_hex(path.read_bytes()),
        entry_digests=digests,
        counts=result.counts(),
    )


def _manifest(result: PipelineResult, stamp: str) -> dict[str, Any]:
    span = result.dataset.time_range()
    config = result.config
    return {
        "schema_version": SCHEMA_VERSION,
        "package_type": "DERIVED",
        "export_id": _export_id(result),
        "created_at": format_ms(config.computed_at_ms),
        # Absent rather than a pair of nulls: the Kotlin model declares the bounds non-null inside
        # an optional range, so a half-populated object fails to decode.
        "date_range": (
            {"from": format_ms(span[0]), "to": format_ms(span[1])} if span else None
        ),
        "algorithm_version": config.algorithm_version,
        "engine_versions": dict(
            sorted((engine_versions() | {"zone_engine_id": config.zone_engine}).items())
        ),
        "parameter_set_sha256": config.params.sha256(),
        "random_seed": config.params.random_seed,
        # The Master rejects a package citing data it has never imported, which is what keeps the
        # derived layer traceable back to raw evidence rather than to a file somebody produced.
        "source_dataset_ids": list(result.dataset.source_dataset_ids),
        "reference_model_id": result.dataset.reference.reference_model_id,
        "calibration_set_id": result.dataset.reference.calibration_set_id,
        "dataset_kind": config.dataset_kind.value,
        "counts": {key: int(value) for key, value in sorted(result.counts().items())},
        "generator": generator_block(),
    }


def _export_id(result: PipelineResult) -> str:
    from ..jsonio import derive_id

    return derive_id(
        "derived_export",
        result.config.algorithm_version,
        result.dataset.content_sha256,
        result.config.params.sha256(),
    )


def _minimal_algorithm_report(result: PipelineResult) -> dict[str, Any]:
    """The report written when a package is produced without a benchmark run.

    It states plainly that no held-out accuracy was measured. Omitting the file would leave the
    absence of evidence looking like an oversight; saying so explicitly means an operator reading
    the package cannot mistake these estimates for validated ones.
    """
    return {
        "report_id": _export_id(result),
        "generated_at_utc": format_ms(result.config.computed_at_ms),
        "dataset_kind": result.config.dataset_kind.value,
        "dataset_content_sha256": result.dataset.content_sha256,
        "algorithm_version": result.config.algorithm_version,
        "zone_engine": result.config.zone_engine,
        "positioning_strategies": list(result.config.strategies),
        "engine_versions": dict(sorted(engine_versions().items())),
        "parameter_set_sha256": result.config.params.sha256(),
        "parameter_set": result.config.params.to_dict(),
        "empirical_error_model": result.config.empirical.as_dict(),
        "benchmark": None,
        "accuracy_claim": (
            "No held-out accuracy was measured for this run. Run `rfmapper-lab benchmark` against "
            "survey ground truth before any accuracy figure is quoted from these estimates."
        ),
        "run_statistics": {
            "tier_breakdown": result.tier_breakdown(),
            "method_breakdown": result.method_breakdown(),
            "oscillations": result.oscillations,
            "topology_violations": result.topology_violations,
            "devices": len({estimate.device_id for estimate in result.estimates}),
            "promoted_fingerprints": len(result.fingerprints.promoted),
            "candidate_fingerprints": len(result.fingerprints.candidates),
        },
    }


def _csv(columns: Sequence[str], rows: Iterable[str]) -> bytes:
    body = "".join(f"{row}\n" for row in rows)
    return (",".join(columns) + "\n" + body).encode("utf-8")


__all__ = ["DerivedPackage", "write_derived_package"]
