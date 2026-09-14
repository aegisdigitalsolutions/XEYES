"""Phase 2 drift detection, per ``docs/10-positioning-mathematical-architecture.md`` §12.

Today's distributions are compared against the fingerprint baseline. Nothing retrains. Every
finding is advisory and requires administrator acknowledgement, which is the same rule that keeps
ordinary observations from redefining ground truth: the system reports that the RF environment
appears to have changed, and a human decides whether that means resurveying, relabelling, or
nothing at all.

Automatic retraining would be the obvious feature and the wrong one. An access point moved by a
contractor and an access point that merely had a quiet afternoon look similar in one day of data,
and silently folding either into the baseline would destroy the reference the system measures drift
against.
"""

from __future__ import annotations

import statistics
from typing import Sequence

from ..fingerprint.engine import FingerprintSet
from ..jsonio import as_evidence, derive_id
from ..models import Observation, QualityFlag, ReferenceModel, Severity
from ..params import ParameterSet


def detect_drift(
    observations: Sequence[Observation],
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    params: ParameterSet,
    generated_at_utc: str,
) -> tuple[QualityFlag, ...]:
    """Compare the day against the fingerprint baseline and report what changed."""
    quality = params.quality
    today = _daily_statistics(observations)
    flags: list[QualityFlag] = []

    baseline = fingerprints.promoted or fingerprints.points

    disappeared: list[str] = []
    shifted: dict[str, list[str]] = {}
    interference: list[str] = []

    for fingerprint in baseline:
        for entry in fingerprint.entries:
            identifier = entry.radio_identifier
            observed = today.get(identifier)

            if entry.visibility_probability > quality.ap_disappeared_visibility and observed is None:
                if identifier not in disappeared:
                    disappeared.append(identifier)
                continue
            if observed is None:
                continue

            shift = abs(observed.median - entry.rssi_median)
            if shift >= quality.ap_relocated_shift_db:
                shifted.setdefault(identifier, []).append(fingerprint.zone_id)

            baseline_sigma = entry.rssi_stddev or 0.0
            if (
                baseline_sigma > 0.5
                and observed.stddev >= quality.interference_variance_ratio * baseline_sigma
                and shift < quality.ap_relocated_shift_db / 2
                and identifier not in interference
            ):
                # Variance inflation without a median shift: the source is where it was and
                # something new is competing with it.
                interference.append(identifier)

    for identifier in sorted(disappeared):
        flags.append(
            _flag(
                code="AP_DISAPPEARED",
                severity=Severity.WARNING,
                scope="IDENTIFIER",
                scope_id=identifier,
                message=(
                    f"{identifier} is reliably visible in ground truth but was not observed at all "
                    f"in this run; the fingerprints that depend on it are weakened"
                ),
                evidence={"visibility_threshold": quality.ap_disappeared_visibility},
                generated_at_utc=generated_at_utc,
            )
        )

    for identifier, zones in sorted(shifted.items()):
        # A shift at several locations at once is the signature of the source moving. A shift at
        # one location is more likely to be something changing in that room.
        relocated = len(set(zones)) >= 2
        flags.append(
            _flag(
                code="AP_RELOCATED" if relocated else "AP_LEVEL_SHIFT",
                severity=Severity.WARNING if relocated else Severity.INFO,
                scope="IDENTIFIER",
                scope_id=identifier,
                message=(
                    f"{identifier} shifted by at least {quality.ap_relocated_shift_db:.0f} dB at "
                    f"{len(set(zones))} location(s): {', '.join(sorted(set(zones)))}"
                ),
                evidence={"zones": sorted(set(zones)), "threshold_db": quality.ap_relocated_shift_db},
                generated_at_utc=generated_at_utc,
            )
        )

    for identifier in sorted(interference):
        flags.append(
            _flag(
                code="NEW_INTERFERENCE",
                severity=Severity.INFO,
                scope="IDENTIFIER",
                scope_id=identifier,
                message=(
                    f"{identifier} shows inflated variance without a median shift, which is the "
                    f"signature of new interference rather than a moved source"
                ),
                evidence={"variance_ratio_threshold": quality.interference_variance_ratio},
                generated_at_utc=generated_at_utc,
            )
        )

    flags.extend(_observer_offsets(observations, params, generated_at_utc))
    flags.extend(_observer_relocation(observations, fingerprints, model, params, generated_at_utc))
    flags.extend(_sparse_calibration(observations, fingerprints, model, params, generated_at_utc))

    codes = {flag.code for flag in flags}
    if len(codes & {"AP_DISAPPEARED", "AP_RELOCATED", "NEW_INTERFERENCE", "OBSERVER_RSSI_OFFSET"}) >= 3:
        flags.append(
            _flag(
                code="POSSIBLE_RF_ENVIRONMENT_CHANGE",
                severity=Severity.WARNING,
                scope="SITE",
                scope_id=model.reference_model_id,
                message=(
                    "several drift indicators fired together, which suggests the RF environment "
                    "changed rather than one source misbehaving; consider resurveying the affected zones"
                ),
                evidence={"codes": sorted(codes)},
                generated_at_utc=generated_at_utc,
            )
        )

    return tuple(flags)


class _Daily:
    __slots__ = ("median", "stddev", "count")

    def __init__(self, median: float, stddev: float, count: int):
        self.median = median
        self.stddev = stddev
        self.count = count


def _daily_statistics(observations: Sequence[Observation]) -> dict[str, _Daily]:
    grouped: dict[str, list[float]] = {}
    for observation in observations:
        if observation.rssi is None:
            continue
        grouped.setdefault(observation.radio_identifier, []).append(float(observation.rssi))
    return {
        identifier: _Daily(
            median=statistics.median(values),
            stddev=statistics.stdev(values) if len(values) > 1 else 0.0,
            count=len(values),
        )
        for identifier, values in grouped.items()
        if len(values) >= 3
    }


def _observer_offsets(
    observations: Sequence[Observation],
    params: ParameterSet,
    generated_at_utc: str,
) -> list[QualityFlag]:
    """One observer's shared-source RSSI differing systematically from the others.

    Estimated from sources several observers saw on the same day, which is a weaker measurement
    than the deliberate co-location procedure in ``docs/11`` §4 — the devices were in different
    places. So it is reported as a recommendation to run that procedure, never applied as an offset.
    """
    per_observer: dict[str, dict[str, list[float]]] = {}
    for observation in observations:
        if observation.rssi is None:
            continue
        per_observer.setdefault(observation.observer_id, {}).setdefault(
            observation.radio_identifier, []
        ).append(float(observation.rssi))

    if len(per_observer) < 2:
        return []

    medians = {
        observer: {
            identifier: statistics.median(values)
            for identifier, values in sources.items()
            if len(values) >= 5
        }
        for observer, sources in per_observer.items()
    }

    flags: list[QualityFlag] = []
    for observer in sorted(medians):
        differences: list[float] = []
        for identifier, value in medians[observer].items():
            others = [
                medians[peer][identifier]
                for peer in medians
                if peer != observer and identifier in medians[peer]
            ]
            if others:
                differences.append(value - statistics.median(others))
        if len(differences) < 3:
            continue
        offset = statistics.median(differences)
        spread = statistics.stdev(differences) if len(differences) > 1 else 0.0
        if abs(offset) < params.quality.observer_offset_db:
            continue
        flags.append(
            _flag(
                code="OBSERVER_RSSI_OFFSET",
                severity=Severity.WARNING,
                scope="OBSERVER",
                scope_id=observer,
                message=(
                    f"{observer} reads {offset:+.1f} dB relative to its peers on {len(differences)} "
                    f"shared sources (spread {spread:.1f} dB); run the co-location calibration "
                    f"procedure rather than trusting this estimate"
                ),
                evidence={
                    "offset_db": offset,
                    "spread_db": spread,
                    "shared_sources": len(differences),
                    "applied": False,
                },
                generated_at_utc=generated_at_utc,
            )
        )
    return flags


def _observer_relocation(
    observations: Sequence[Observation],
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    params: ParameterSet,
    generated_at_utc: str,
) -> list[QualityFlag]:
    """A fixed observer whose own RF view no longer matches its declared zone."""
    from ..zone.classifiers import signal_distance

    flags: list[QualityFlag] = []
    for observer_id, observer in sorted(model.observers.items()):
        if not observer.fixed_observer or observer.default_zone_id is None:
            continue
        rows = [o for o in observations if o.observer_id == observer_id and o.rssi is not None]
        if len(rows) < 20:
            continue

        strongest: dict[str, float] = {}
        for row in rows:
            value = float(row.rssi or 0)
            if value > strongest.get(row.radio_identifier, -999.0):
                strongest[row.radio_identifier] = value

        ranked = sorted(
            (
                (signal_distance(strongest, fingerprint, params.zone), fingerprint)
                for fingerprint in fingerprints.points
            ),
            key=lambda item: (item[0], item[1].fingerprint_id),
        )
        usable = [item for item in ranked if item[0] != float("inf")]
        if not usable:
            continue
        best = usable[0][1]
        if best.zone_id == observer.default_zone_id:
            continue
        flags.append(
            _flag(
                code="OBSERVER_RELOCATED",
                severity=Severity.WARNING,
                scope="OBSERVER",
                scope_id=observer_id,
                message=(
                    f"{observer_id} is declared fixed in {observer.default_zone_id} but its own RF "
                    f"view best matches {best.zone_id}; either it was moved or the site changed "
                    f"around it"
                ),
                evidence={
                    "declared_zone": observer.default_zone_id,
                    "matched_zone": best.zone_id,
                    "signal_distance": usable[0][0],
                },
                generated_at_utc=generated_at_utc,
            )
        )
    return flags


def _sparse_calibration(
    observations: Sequence[Observation],
    fingerprints: FingerprintSet,
    model: ReferenceModel,
    params: ParameterSet,
    generated_at_utc: str,
) -> list[QualityFlag]:
    """Zones with observations but no nearby ground truth.

    This is the flag that turns into the recommendation the specification asks for — "Building 4
    fingerprint could be improved" — and it is the legitimate use of ordinary observations: they
    can tell us calibration is weak, and they can never redefine it.
    """
    per_zone = fingerprints.by_zone()
    observed_zones = {o.zone_id for o in observations if o.zone_id}
    flags: list[QualityFlag] = []

    by_building: dict[str, list[str]] = {}
    for zone_id, zone in sorted(model.zones.items()):
        count = len(per_zone.get(zone_id, ()))
        if count >= params.quality.min_fingerprints_per_zone:
            continue
        by_building.setdefault(zone.building_id, []).append(zone_id)

    for building_id, zones in sorted(by_building.items()):
        relevant = [zone for zone in zones if zone in observed_zones] or zones
        sessions = len(
            {
                o.survey_session_id
                for o in observations
                if o.is_ground_truth and o.building_id == building_id and o.survey_session_id
            }
        )
        flags.append(
            _flag(
                code="SPARSE_CALIBRATION",
                severity=Severity.WARNING,
                scope="BUILDING",
                scope_id=building_id,
                message=(
                    f"{model.buildings.get(building_id, building_id)} fingerprint could be improved: "
                    f"{sessions} survey session(s), {len(zones)} zone(s) uncovered "
                    f"({', '.join(sorted(relevant)[:6])})"
                ),
                evidence={
                    "sessions": sessions,
                    "required": 3,
                    "uncovered_zones": sorted(zones),
                },
                generated_at_utc=generated_at_utc,
            )
        )
    return flags


def _flag(
    code: str,
    severity: Severity,
    scope: str,
    scope_id: str | None,
    message: str,
    evidence: dict,
    generated_at_utc: str,
) -> QualityFlag:
    return QualityFlag(
        flag_id=derive_id("flag", code, scope, scope_id or "", generated_at_utc),
        created_at_utc=generated_at_utc,
        severity=severity,
        code=code,
        scope=scope,
        scope_id=scope_id,
        message=message,
        evidence=as_evidence(evidence),
    )
