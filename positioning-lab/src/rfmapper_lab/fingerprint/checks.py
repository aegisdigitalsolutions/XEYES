"""Ground-truth quality checks, per ``docs/11-ground-truth-and-calibration-procedure.md`` §6.

These run before a candidate fingerprint can be promoted. The Lab computes them and reports; the
promotion itself is an administrator action in the Master, because there is no automatic path into
``GROUND_TRUTH`` and adding one here would defeat two independent checks that exist to prevent it.

The strongest check is the last one. If a survey point's own sessions cannot identify that point,
the point is not discriminable, and no downstream algorithm will repair it — which is worth knowing
before the point is trusted as truth rather than after an accuracy number disappoints.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import Sequence

from ..models import FingerprintPoint, Observation, ReferenceModel, Severity
from ..params import ParameterSet

#: Deliverable 11 §6 thresholds.
MIN_SAMPLES_PER_SESSION = 30
MIN_SESSIONS = 3
MIN_SOURCE_JACCARD = 0.6
MAX_MEDIAN_DISAGREEMENT_DB = 8.0
STABLE_VISIBILITY = 0.5


@dataclass(frozen=True, slots=True)
class PromotionCheck:
    code: str
    passed: bool
    detail: str
    severity: Severity = Severity.WARNING

    def as_dict(self) -> dict[str, object]:
        return {
            "code": self.code,
            "passed": self.passed,
            "detail": self.detail,
            "severity": self.severity.value,
        }


@dataclass(frozen=True, slots=True)
class PromotionReport:
    survey_point_id: str
    fingerprint_id: str
    checks: tuple[PromotionCheck, ...]

    @property
    def promotable(self) -> bool:
        return all(check.passed for check in self.checks)

    @property
    def failures(self) -> tuple[PromotionCheck, ...]:
        return tuple(check for check in self.checks if not check.passed)

    def as_dict(self) -> dict[str, object]:
        return {
            "survey_point_id": self.survey_point_id,
            "fingerprint_id": self.fingerprint_id,
            "promotable": self.promotable,
            "checks": [check.as_dict() for check in self.checks],
        }


def evaluate_promotion(
    fingerprint: FingerprintPoint,
    samples: Sequence[Observation],
    model: ReferenceModel,
    params: ParameterSet,
    peers: Sequence[FingerprintPoint] = (),
) -> PromotionReport:
    """Run every promotion check for one candidate fingerprint."""
    by_session: dict[str, list[Observation]] = {}
    for sample in samples:
        by_session.setdefault(sample.survey_session_id or "", []).append(sample)

    checks: list[PromotionCheck] = [
        _sample_count(by_session),
        _session_count(by_session),
        _source_overlap(by_session, fingerprint),
        _median_agreement(by_session),
        _position_plausibility(fingerprint, model),
        _self_consistency(fingerprint, peers, params),
    ]
    return PromotionReport(
        survey_point_id=fingerprint.survey_point_id,
        fingerprint_id=fingerprint.fingerprint_id,
        checks=tuple(checks),
    )


def _sample_count(by_session: dict[str, list[Observation]]) -> PromotionCheck:
    thin = {
        session: len(rows)
        for session, rows in sorted(by_session.items())
        if len(rows) < MIN_SAMPLES_PER_SESSION
    }
    return PromotionCheck(
        code="SAMPLE_COUNT",
        passed=not thin,
        detail=(
            f"every session has at least {MIN_SAMPLES_PER_SESSION} samples"
            if not thin
            else f"sessions below {MIN_SAMPLES_PER_SESSION} samples: {thin}"
        ),
    )


def _session_count(by_session: dict[str, list[Observation]]) -> PromotionCheck:
    count = len([session for session in by_session if session])
    return PromotionCheck(
        code="SESSION_COUNT",
        passed=count >= MIN_SESSIONS,
        detail=(
            f"{count} survey sessions"
            if count >= MIN_SESSIONS
            else f"{count} session(s); {MIN_SESSIONS} are recommended, and a single session "
            f"captures one moment's RF environment and one chipset's bias"
        ),
    )


def _source_overlap(
    by_session: dict[str, list[Observation]], fingerprint: FingerprintPoint
) -> PromotionCheck:
    """Jaccard overlap of reliably visible sources between sessions.

    Low overlap means the sessions did not see the same radio environment, which usually means the
    operator stood somewhere different or the infrastructure changed between visits.
    """
    stable = {
        entry.radio_identifier
        for entry in fingerprint.entries
        if entry.visibility_probability > STABLE_VISIBILITY
    }
    sessions = [session for session in sorted(by_session) if session]
    if len(sessions) < 2 or not stable:
        return PromotionCheck(
            code="SOURCE_OVERLAP",
            passed=len(sessions) < 2,
            detail="not enough sessions to compare source overlap",
            severity=Severity.INFO,
        )

    per_session = [
        {row.radio_identifier for row in by_session[session]} & stable for session in sessions
    ]
    worst = 1.0
    for index, left in enumerate(per_session):
        for right in per_session[index + 1 :]:
            union = left | right
            if not union:
                continue
            worst = min(worst, len(left & right) / len(union))

    return PromotionCheck(
        code="SOURCE_OVERLAP",
        passed=worst >= MIN_SOURCE_JACCARD,
        detail=f"worst pairwise Jaccard over stable sources is {worst:.2f} (need {MIN_SOURCE_JACCARD})",
    )


def _median_agreement(by_session: dict[str, list[Observation]]) -> PromotionCheck:
    """Per-source median RSSI must agree between sessions to within 8 dB."""
    per_session: dict[str, dict[str, float]] = {}
    for session, rows in by_session.items():
        if not session:
            continue
        grouped: dict[str, list[float]] = {}
        for row in rows:
            if row.rssi is not None:
                grouped.setdefault(row.radio_identifier, []).append(float(row.rssi))
        per_session[session] = {
            identifier: statistics.median(values) for identifier, values in grouped.items()
        }

    if len(per_session) < 2:
        return PromotionCheck(
            code="MEDIAN_AGREEMENT",
            passed=True,
            detail="only one session; cross-session agreement not measurable",
            severity=Severity.INFO,
        )

    common = set.intersection(*(set(medians) for medians in per_session.values()))
    worst_identifier, worst_spread = None, 0.0
    for identifier in sorted(common):
        values = [medians[identifier] for medians in per_session.values()]
        spread = max(values) - min(values)
        if spread > worst_spread:
            worst_identifier, worst_spread = identifier, spread

    return PromotionCheck(
        code="MEDIAN_AGREEMENT",
        passed=worst_spread <= MAX_MEDIAN_DISAGREEMENT_DB,
        detail=(
            f"largest cross-session median spread is {worst_spread:.1f} dB"
            + (f" on {worst_identifier}" if worst_identifier else "")
        ),
    )


def _position_plausibility(fingerprint: FingerprintPoint, model: ReferenceModel) -> PromotionCheck:
    """The survey point must lie inside the zone polygon it claims."""
    zone = model.zones.get(fingerprint.zone_id)
    if zone is None or not zone.polygon or not fingerprint.has_coordinates:
        return PromotionCheck(
            code="POSITION_PLAUSIBILITY",
            passed=True,
            detail="no polygon or no coordinates to check against",
            severity=Severity.INFO,
        )
    inside = point_in_polygon(float(fingerprint.x or 0.0), float(fingerprint.y or 0.0), zone.polygon)
    return PromotionCheck(
        code="POSITION_PLAUSIBILITY",
        passed=inside,
        detail=(
            f"point lies inside {zone.zone_id}"
            if inside
            else f"point ({fingerprint.x}, {fingerprint.y}) lies outside its declared zone {zone.zone_id}"
        ),
        severity=Severity.WARNING if inside else Severity.ERROR,
    )


def _self_consistency(
    fingerprint: FingerprintPoint,
    peers: Sequence[FingerprintPoint],
    params: ParameterSet,
) -> PromotionCheck:
    """Does this point's own fingerprint identify its own zone against its neighbours?

    A cheap stand-in for the full leave-one-session-out procedure: classify the fingerprint's own
    median vector against every *other* point. If a different zone wins, the point is not
    discriminable from its neighbours and promoting it would encode a confusion as truth.
    """
    others = [peer for peer in peers if peer.fingerprint_id != fingerprint.fingerprint_id]
    if not others:
        return PromotionCheck(
            code="SELF_CONSISTENCY",
            passed=True,
            detail="no peer fingerprints to be confused with",
            severity=Severity.INFO,
        )

    from ..zone.classifiers import signal_distance

    own = {entry.radio_identifier: entry.rssi_median for entry in fingerprint.entries}
    ranked = sorted(
        (
            (signal_distance(own, peer, params.zone), peer)
            for peer in [fingerprint, *others]
        ),
        key=lambda item: (item[0], item[1].fingerprint_id),
    )
    best_distance, best = ranked[0]
    passed = best.zone_id == fingerprint.zone_id
    return PromotionCheck(
        code="SELF_CONSISTENCY",
        passed=passed,
        detail=(
            f"own vector matches {best.zone_id} at distance {best_distance:.1f}"
            if passed
            else f"own vector matches {best.zone_id} (survey point {best.survey_point_id}) rather "
            f"than its own zone {fingerprint.zone_id}: this point is not discriminable"
        ),
        severity=Severity.WARNING if passed else Severity.ERROR,
    )


def point_in_polygon(x: float, y: float, polygon: Sequence) -> bool:
    """Ray casting, with vertices counted as inside."""
    if len(polygon) < 3:
        return False
    inside = False
    count = len(polygon)
    for index in range(count):
        a = polygon[index]
        b = polygon[(index + 1) % count]
        if abs(a.x - x) < 1e-9 and abs(a.y - y) < 1e-9:
            return True
        if (a.y > y) != (b.y > y):
            crossing = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
            if x < crossing:
                inside = not inside
    return inside
