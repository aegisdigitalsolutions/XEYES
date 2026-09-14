"""Derived-record serialization, matching ``core-model/DerivedModels.kt`` field for field.

Field names, nullability and types are the contract with the Kotlin decoder, and the decoder is
strict where it matters: ``QualityFlag.evidence`` is ``Map<String, String>`` there, so a numeric
evidence value written here would fail an import rather than round-trip. Every mapping is emitted
in schema order and every list is sorted, because the package has to be byte-identical on a re-run.

CSV forms follow ``docs/13-derived-output-schema.md`` §8: list columns are ``;``-separated inside
one quoted field, object columns are compact JSON with sorted keys.
"""

from __future__ import annotations

import csv
import io
from typing import Any, Mapping, Sequence

from ..jsonio import dumps_sorted, format_decimal
from ..models import MovementEstimate, PositionEstimate, QualityFlag, ZoneTransition

ESTIMATE_COLUMNS: tuple[str, ...] = (
    "estimate_id",
    "algorithm_version",
    "device_id",
    "timestamp_utc",
    "computed_at_utc",
    "precision_tier",
    "building_id",
    "zone_id",
    "x",
    "y",
    "horizontal_uncertainty_m",
    "confidence",
    "method",
    "supporting_observer_ids",
    "supporting_observation_ids",
    "source_dataset_ids",
    "calibration_set_id",
    "quality_flags",
    "engine_versions_json",
    "confidence_factors_json",
)

TRANSITION_COLUMNS: tuple[str, ...] = (
    "transition_id",
    "algorithm_version",
    "device_id",
    "event_type",
    "origin_zone_id",
    "destination_zone_id",
    "transition_start_utc",
    "transition_confirmed_utc",
    "confidence",
    "topology_status",
    "supporting_observer_ids",
    "supporting_estimate_ids",
    "quality_flags",
    "engine_versions_json",
)


def estimate_to_dict(estimate: PositionEstimate) -> dict[str, Any]:
    return {
        "estimate_id": estimate.estimate_id,
        "algorithm_version": estimate.algorithm_version,
        "engine_versions": _sorted_strings(estimate.engine_versions),
        "parameter_set_sha256": estimate.parameter_set_sha256,
        "device_id": estimate.device_id,
        "timestamp_utc": estimate.timestamp_utc,
        "computed_at_utc": estimate.computed_at_utc,
        "precision_tier": estimate.precision_tier.value,
        "building_id": estimate.building_id,
        "zone_id": estimate.zone_id,
        "x": _round(estimate.x),
        "y": _round(estimate.y),
        "horizontal_uncertainty_m": _round(estimate.horizontal_uncertainty_m),
        "confidence": _round(estimate.confidence, 4),
        "confidence_factors": {
            key: _round(value, 4) for key, value in sorted(estimate.confidence_factors.items())
        },
        "method": estimate.method,
        "supporting_observer_ids": list(estimate.supporting_observer_ids),
        "supporting_observation_ids": list(estimate.supporting_observation_ids),
        "source_dataset_ids": list(estimate.source_dataset_ids),
        "reference_model_id": estimate.reference_model_id,
        "calibration_set_id": estimate.calibration_set_id,
        "quality_flags": list(estimate.quality_flags),
    }


def transition_to_dict(transition: ZoneTransition) -> dict[str, Any]:
    return {
        "transition_id": transition.transition_id,
        "algorithm_version": transition.algorithm_version,
        "engine_versions": _sorted_strings(transition.engine_versions),
        "device_id": transition.device_id,
        "event_type": transition.event_type.value,
        "origin_zone_id": transition.origin_zone_id,
        "destination_zone_id": transition.destination_zone_id,
        "transition_start_utc": transition.transition_start_utc,
        "transition_confirmed_utc": transition.transition_confirmed_utc,
        "confidence": _round(transition.confidence, 4),
        "topology_status": transition.topology_status.value,
        "supporting_observer_ids": list(transition.supporting_observer_ids),
        "supporting_estimate_ids": list(transition.supporting_estimate_ids),
        "quality_flags": list(transition.quality_flags),
    }


def movement_to_dict(movement: MovementEstimate) -> dict[str, Any]:
    return {
        "movement_id": movement.movement_id,
        "algorithm_version": movement.algorithm_version,
        "engine_versions": _sorted_strings(movement.engine_versions),
        "device_id": movement.device_id,
        "timestamp_utc": movement.timestamp_utc,
        "state": movement.state.value,
        "origin_zone_id": movement.origin_zone_id,
        "candidate_destination_zone_id": movement.candidate_destination_zone_id,
        "confirmed_destination_zone_id": movement.confirmed_destination_zone_id,
        "direction": movement.direction,
        "confidence": _round(movement.confidence, 4),
        "supporting_estimate_ids": list(movement.supporting_estimate_ids),
        "quality_flags": list(movement.quality_flags),
    }


def flag_to_dict(flag: QualityFlag) -> dict[str, Any]:
    return {
        "flag_id": flag.flag_id,
        "algorithm_version": flag.algorithm_version,
        "created_at_utc": flag.created_at_utc,
        "severity": flag.severity.value,
        "code": flag.code,
        "scope": flag.scope,
        "scope_id": flag.scope_id,
        "message": flag.message,
        # Values are strings because the Kotlin side declares Map<String, String>. Numbers here
        # would be a decode failure at import time rather than a schema discussion.
        "evidence": {key: str(value) for key, value in sorted(flag.evidence.items())},
        "acknowledged_at_utc": flag.acknowledged_at_utc,
        "acknowledged_by": flag.acknowledged_by,
    }


def estimate_csv_row(estimate: PositionEstimate) -> str:
    return _csv_row(
        [
            estimate.estimate_id,
            estimate.algorithm_version,
            estimate.device_id,
            estimate.timestamp_utc,
            estimate.computed_at_utc,
            estimate.precision_tier.value,
            estimate.building_id or "",
            estimate.zone_id or "",
            format_decimal(estimate.x) if estimate.x is not None else "",
            format_decimal(estimate.y) if estimate.y is not None else "",
            format_decimal(estimate.horizontal_uncertainty_m)
            if estimate.horizontal_uncertainty_m is not None
            else "",
            format_decimal(estimate.confidence),
            estimate.method,
            _list(estimate.supporting_observer_ids),
            _list(estimate.supporting_observation_ids),
            _list(estimate.source_dataset_ids),
            estimate.calibration_set_id or "",
            _list(estimate.quality_flags),
            dumps_sorted(_sorted_strings(estimate.engine_versions)),
            dumps_sorted({k: round(v, 4) for k, v in estimate.confidence_factors.items()}),
        ]
    )


def transition_csv_row(transition: ZoneTransition) -> str:
    return _csv_row(
        [
            transition.transition_id,
            transition.algorithm_version,
            transition.device_id,
            transition.event_type.value,
            transition.origin_zone_id or "",
            transition.destination_zone_id or "",
            transition.transition_start_utc,
            transition.transition_confirmed_utc,
            format_decimal(transition.confidence),
            transition.topology_status.value,
            _list(transition.supporting_observer_ids),
            _list(transition.supporting_estimate_ids),
            _list(transition.quality_flags),
            dumps_sorted(_sorted_strings(transition.engine_versions)),
        ]
    )


def _csv_row(cells: Sequence[str]) -> str:
    buffer = io.StringIO()
    csv.writer(buffer, lineterminator="", quoting=csv.QUOTE_MINIMAL).writerow(list(cells))
    return buffer.getvalue()


def _list(values: Sequence[str]) -> str:
    """``;``-separated inside one field: a semicolon cannot appear in a uuid or an id."""
    return ";".join(values)


def _sorted_strings(mapping: Mapping[str, str]) -> dict[str, str]:
    return {key: str(mapping[key]) for key in sorted(mapping)}


def _round(value: float | None, places: int = 3) -> float | None:
    return None if value is None else round(float(value), places)
