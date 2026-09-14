"""``quality_report.json`` — the administrator-facing view of a run.

Shape defined by ``docs/13-derived-output-schema.md`` §4. Everything the run noticed about its own
inputs goes here: how much data arrived, how fresh it was, which observers are drifting, which
zones have no ground truth, and every advisory flag with the evidence that produced it.

The evidence is the point. A flag an administrator can only trust is worse than no flag, because it
cannot be argued with. A flag that carries the numbers behind it can be judged, acted on, or
dismissed.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field
from typing import Any, Sequence

from ..fingerprint.engine import FingerprintSet
from ..jsonio import as_evidence, clean, derive_id
from ..models import DatasetKind, Observation, QualityFlag, ReferenceModel, SensorType, Severity
from ..params import ParameterSet
from ..parsing.dataset import Dataset
from ..timeutil import format_ms
from .drift import detect_drift


@dataclass(frozen=True, slots=True)
class QualityReport:
    report_id: str
    generated_at_utc: str
    date_range: dict[str, str | None]
    dataset_kind: DatasetKind
    ingest: dict[str, Any]
    observers: tuple[dict[str, Any], ...]
    coverage: dict[str, Any]
    flags: tuple[QualityFlag, ...]
    attribution: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        from ..export.serialize import flag_to_dict

        return {
            "report_id": self.report_id,
            "generated_at_utc": self.generated_at_utc,
            "date_range": self.date_range,
            "dataset_kind": self.dataset_kind.value,
            "ingest": clean(self.ingest),
            "observers": [clean(observer) for observer in self.observers],
            "coverage": clean(self.coverage),
            "attribution": clean(self.attribution),
            "flags": [flag_to_dict(flag) for flag in self.flags],
        }

    def by_severity(self, severity: Severity) -> tuple[QualityFlag, ...]:
        return tuple(flag for flag in self.flags if flag.severity is severity)


def build_quality_report(
    dataset: Dataset,
    fingerprints: FingerprintSet,
    params: ParameterSet,
    generated_at_ms: int,
    dataset_kind: DatasetKind = DatasetKind.REAL,
) -> QualityReport:
    generated_at_utc = format_ms(generated_at_ms)
    model = dataset.reference
    observations = dataset.observations

    span = dataset.time_range()
    date_range = {
        "from": format_ms(span[0]) if span else None,
        "to": format_ms(span[1]) if span else None,
    }

    flags: list[QualityFlag] = []
    observers = _observer_statistics(observations, model, flags, params, generated_at_utc)
    coverage = _coverage(observations, fingerprints, model)
    attribution = _attribution(observations, model)

    flags.extend(_ingest_flags(dataset, generated_at_utc))
    flags.extend(_survey_flags(observations, model, generated_at_utc))
    flags.extend(detect_drift(observations, fingerprints, model, params, generated_at_utc))
    flags.extend(_candidate_fingerprint_flags(fingerprints, generated_at_utc))

    ordered = tuple(sorted(flags, key=lambda flag: (flag.code, flag.scope_id or "", flag.flag_id)))

    return QualityReport(
        report_id=derive_id("quality_report", generated_at_utc, model.reference_model_id),
        generated_at_utc=generated_at_utc,
        date_range=date_range,
        dataset_kind=dataset_kind,
        ingest=dataset.stats.as_dict(),
        observers=observers,
        coverage=coverage,
        flags=ordered,
        attribution=attribution,
    )


# -- sections -------------------------------------------------------------------------------------


def _observer_statistics(
    observations: Sequence[Observation],
    model: ReferenceModel,
    flags: list[QualityFlag],
    params: ParameterSet,
    generated_at_utc: str,
) -> tuple[dict[str, Any], ...]:
    grouped: dict[str, list[Observation]] = {}
    for observation in observations:
        grouped.setdefault(observation.observer_id, []).append(observation)

    rows: list[dict[str, Any]] = []
    for observer_id in sorted(grouped):
        rows_for_observer = grouped[observer_id]
        span_ms = (
            rows_for_observer[-1].timestamp_ms - rows_for_observer[0].timestamp_ms
            if len(rows_for_observer) > 1
            else 0
        )
        hours = max(span_ms / 3_600_000, 1 / 60)
        fresh = sum(1 for row in rows_for_observer if row.freshness == "FRESH")
        cached = sum(1 for row in rows_for_observer if row.freshness == "CACHED")
        jumps = _clock_jumps(rows_for_observer, params.quality.clock_jump_ms)
        calibration = model.calibration.get(observer_id)
        observer = model.observers.get(observer_id)

        if jumps:
            flags.append(
                QualityFlag(
                    flag_id=derive_id("flag", "CLOCK_JUMP", observer_id, generated_at_utc),
                    created_at_utc=generated_at_utc,
                    severity=Severity.WARNING,
                    code="CLOCK_JUMP",
                    scope="OBSERVER",
                    scope_id=observer_id,
                    message=(
                        f"{observer_id} shows {len(jumps)} wall-clock discontinuity(ies) against its "
                        f"monotonic clock; the affected rows are down-weighted, not discarded"
                    ),
                    evidence=as_evidence(
                        {"jumps": len(jumps), "largest_ms": max(jumps), "sessions_affected": len(jumps)}
                    ),
                )
            )

        if observer is None:
            flags.append(
                QualityFlag(
                    flag_id=derive_id("flag", "UNENROLLED_OBSERVER", observer_id, generated_at_utc),
                    created_at_utc=generated_at_utc,
                    severity=Severity.WARNING,
                    code="UNENROLLED_OBSERVER",
                    scope="OBSERVER",
                    scope_id=observer_id,
                    message=(
                        f"{observer_id} appears in the data but not in the site model, so its "
                        f"capabilities and position are unknown and its evidence cannot be weighted"
                    ),
                    evidence=as_evidence({"observations": len(rows_for_observer)}),
                )
            )

        rows.append(
            {
                "observer_id": observer_id,
                "observations": len(rows_for_observer),
                "observations_per_hour": round(len(rows_for_observer) / hours, 1),
                "fresh_ratio": round(fresh / len(rows_for_observer), 3),
                "cached_ratio": round(cached / len(rows_for_observer), 3),
                "clock_jumps": len(jumps),
                "calibration_offset_db": calibration.rssi_offset_db if calibration else None,
                "calibration_applied": bool(
                    calibration
                    and calibration.is_applicable(params.fusion.max_calibration_spread_db)
                ),
                "capabilities": sorted(observer.capabilities) if observer else [],
                "fixed_observer": bool(observer and observer.fixed_observer),
                "counts_by_sensor_type": {
                    sensor.value: sum(
                        1 for row in rows_for_observer if row.sensor_type is sensor
                    )
                    for sensor in SensorType
                    if any(row.sensor_type is sensor for row in rows_for_observer)
                },
            }
        )
    return tuple(rows)


def _clock_jumps(rows: Sequence[Observation], threshold_ms: int) -> list[int]:
    r"""Wall-clock discontinuities detected against the monotonic clock.

    Within one session the residual :math:`\epsilon = (t_{wall}-t^0_{wall}) - (t_{mono}-t^0_{mono})`
    should stay near zero. A step change means the wall clock was adjusted mid-session. The rows
    are flagged and down-weighted rather than discarded — the measurement is still real, only its
    timestamp is suspect.
    """
    by_session: dict[str, list[Observation]] = {}
    for row in rows:
        if row.monotonic_ms is None:
            continue
        by_session.setdefault(row.session_id or "", []).append(row)

    jumps: list[int] = []
    for session_rows in by_session.values():
        ordered = sorted(session_rows, key=lambda row: row.monotonic_ms or 0)
        if len(ordered) < 3:
            continue
        base_wall = ordered[0].timestamp_ms
        base_mono = ordered[0].monotonic_ms or 0
        previous = 0
        for row in ordered[1:]:
            residual = (row.timestamp_ms - base_wall) - ((row.monotonic_ms or 0) - base_mono)
            if abs(residual - previous) > threshold_ms:
                jumps.append(int(abs(residual - previous)))
            previous = residual
    return jumps


def _coverage(
    observations: Sequence[Observation],
    fingerprints: FingerprintSet,
    model: ReferenceModel,
) -> dict[str, Any]:
    per_zone = fingerprints.by_zone()
    promoted_zones = {point.zone_id for point in fingerprints.promoted}
    with_truth = sorted(promoted_zones & set(model.zones))
    without_truth = sorted(set(model.zones) - promoted_zones)

    return {
        "zones_total": len(model.zones),
        "zones_with_ground_truth": len(with_truth),
        "zones_without_ground_truth": len(without_truth),
        "zones_missing_ground_truth": without_truth,
        "calibration_density_per_zone": {
            zone_id: len(per_zone.get(zone_id, ())) for zone_id in sorted(model.zones)
        },
        "promoted_fingerprints": len(fingerprints.promoted),
        "candidate_fingerprints": len(fingerprints.candidates),
        "sources_per_fingerprint": {
            point.fingerprint_id: len(point.entries) for point in fingerprints.points
        },
        "survey_sessions": len(
            {o.survey_session_id for o in observations if o.is_ground_truth and o.survey_session_id}
        ),
        "located_rtt_anchors": len(model.anchors_by_bssid()),
    }


def _attribution(observations: Sequence[Observation], model: ReferenceModel) -> dict[str, Any]:
    """How much of the day could be attributed, and how much is environmental.

    Reported because the unattributed share is the honest headline of a deployment: the system
    positions enrolled devices, and everything else is RF context by design rather than by failure.
    """
    from ..fusion.windows import attribute_device

    attributed = 0
    ephemeral = 0
    per_device: dict[str, int] = {}
    for observation in observations:
        if observation.identifier_type.is_ephemeral:
            ephemeral += 1
        device_id = attribute_device(observation, model)
        if device_id is not None:
            attributed += 1
            per_device[device_id] = per_device.get(device_id, 0) + 1

    total = len(observations) or 1
    return {
        "observations": len(observations),
        "attributed": attributed,
        "environmental": len(observations) - attributed,
        "unattributed_share": round(1 - attributed / total, 4),
        "randomized_identifier_rows": ephemeral,
        "observations_per_device": dict(sorted(per_device.items())),
        "managed_devices": len(model.devices),
        "devices_seen": len(per_device),
    }


def _ingest_flags(dataset: Dataset, generated_at_utc: str) -> list[QualityFlag]:
    flags: list[QualityFlag] = []
    stats = dataset.stats

    if stats.duplicate_id_content_mismatch:
        flags.append(
            QualityFlag(
                flag_id=derive_id("flag", "DUPLICATE_ID_CONTENT_MISMATCH", generated_at_utc),
                created_at_utc=generated_at_utc,
                severity=Severity.ERROR,
                code="DUPLICATE_ID_CONTENT_MISMATCH",
                scope="DATASET",
                scope_id=None,
                message=(
                    f"{stats.duplicate_id_content_mismatch} observation id(s) arrived twice with "
                    f"different content; a repeated id is routine but a repeated id with different "
                    f"measurements is a genuine anomaly"
                ),
                evidence=as_evidence({"count": stats.duplicate_id_content_mismatch}),
            )
        )

    for package in stats.rejected_packages:
        flags.append(
            QualityFlag(
                flag_id=derive_id("flag", "PACKAGE_REJECTED", package, generated_at_utc),
                created_at_utc=generated_at_utc,
                severity=Severity.ERROR,
                code="PACKAGE_REJECTED",
                scope="PACKAGE",
                scope_id=package,
                message=(
                    f"package '{package}' failed validation and contributed no observations; an "
                    f"observer missing from this run's output may simply be this package"
                ),
                evidence=as_evidence({"package": package}),
            )
        )

    if stats.invalid:
        flags.append(
            QualityFlag(
                flag_id=derive_id("flag", "INVALID_ROWS", generated_at_utc),
                created_at_utc=generated_at_utc,
                severity=Severity.WARNING,
                code="INVALID_ROWS",
                scope="DATASET",
                scope_id=None,
                message=f"{stats.invalid} row(s) could not be decoded and were excluded",
                evidence=as_evidence({"invalid": stats.invalid, "rows_read": stats.rows_read}),
            )
        )
    return flags


def _survey_flags(
    observations: Sequence[Observation],
    model: ReferenceModel,
    generated_at_utc: str,
) -> list[QualityFlag]:
    """Survey samples that cannot be placed, and ground-truth claims without a session."""
    flags: list[QualityFlag] = []

    unknown_points = sorted(
        {
            str(o.survey_point_id)
            for o in observations
            if o.is_ground_truth
            and o.survey_point_id
            and o.survey_point_id not in model.survey_points
        }
    )
    for point_id in unknown_points:
        flags.append(
            QualityFlag(
                flag_id=derive_id("flag", "UNKNOWN_SURVEY_POINT", point_id, generated_at_utc),
                created_at_utc=generated_at_utc,
                severity=Severity.WARNING,
                code="UNKNOWN_SURVEY_POINT",
                scope="SURVEY_POINT",
                scope_id=point_id,
                message=(
                    f"survey samples cite point '{point_id}', which the site model does not define; "
                    f"they cannot be placed and were excluded from fingerprints"
                ),
                evidence=as_evidence({"survey_point_id": point_id}),
            )
        )

    unsessioned = sum(
        1
        for o in observations
        if o.sample_kind == "GROUND_TRUTH" and not o.survey_session_id
    )
    if unsessioned:
        flags.append(
            QualityFlag(
                flag_id=derive_id("flag", "GROUND_TRUTH_WITHOUT_SESSION", generated_at_utc),
                created_at_utc=generated_at_utc,
                severity=Severity.ERROR,
                code="GROUND_TRUTH_WITHOUT_SESSION",
                scope="DATASET",
                scope_id=None,
                message=(
                    f"{unsessioned} row(s) claim sample_kind=GROUND_TRUTH with no survey session; "
                    f"a ground-truth claim without a session is just a claim and was ignored"
                ),
                evidence=as_evidence({"rows": unsessioned}),
            )
        )
    return flags


def _candidate_fingerprint_flags(
    fingerprints: FingerprintSet, generated_at_utc: str
) -> list[QualityFlag]:
    if not fingerprints.candidates:
        return []
    single_session = [
        point.survey_point_id
        for point in fingerprints.candidates
        if (point.session_count or 0) < 2
    ]
    return [
        QualityFlag(
            flag_id=derive_id("flag", "UNPROMOTED_FINGERPRINTS", generated_at_utc),
            created_at_utc=generated_at_utc,
            severity=Severity.WARNING,
            code="UNPROMOTED_FINGERPRINTS",
            scope="DATASET",
            scope_id=None,
            message=(
                f"{len(fingerprints.candidates)} fingerprint(s) used by this run have not been "
                f"promoted to ground truth by an administrator; estimates relying on them carry "
                f"CANDIDATE_FINGERPRINT"
            ),
            evidence=as_evidence(
                {
                    "candidates": len(fingerprints.candidates),
                    "promoted": len(fingerprints.promoted),
                    "single_session_points": sorted(set(single_session))[:10],
                }
            ),
        )
    ]


def _median(values: Sequence[float]) -> float:
    return statistics.median(values) if values else 0.0
