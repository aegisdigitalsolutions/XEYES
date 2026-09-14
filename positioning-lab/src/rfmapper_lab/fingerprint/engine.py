"""Phase 4 — statistical fingerprints per survey point.

Three decisions here carry most of the accuracy of the whole system.

**Distributions, not point values.** A source seen at −60 dBm with σ = 2 dB says something quite
different from the same median with σ = 11 dB, and a fingerprint that stores only the median throws
that difference away (``docs/10-positioning-mathematical-architecture.md`` §2).

**Visibility probability, not a substituted floor RSSI.** When a source is missing from a live
vector, the honest statement is "this location sees it in 94% of samples and it is not here now",
which is strong evidence against the location. Substituting −100 dBm would invent a measurement
that was never taken.

**Bursts, not rows, as the sample unit.** One Wi-Fi scan yields twenty rows and one BLE second
yields hundreds. Counting rows would make visibility probability a function of scan cadence rather
than of coverage, so rows are grouped into bursts per observer and session first.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import Iterable, Sequence

from ..jsonio import derive_id
from ..models import (
    FingerprintEntry,
    FingerprintPoint,
    IdentifierType,
    Observation,
    ObserverCalibration,
    ReferenceModel,
    SensorType,
)
from ..params import FingerprintParams, ParameterSet
from ..parsing.normalize import normalize_identifier, normalized_rssi
from ..timeutil import format_ms
from ..version import FINGERPRINT_ENGINE_VERSION

#: Rows from one observer within this many milliseconds are one observation burst. Sized above a
#: Wi-Fi scan's own result spread and below the survey cadence, so a burst is one look around.
BURST_MS = 1_500

#: Statuses that mean an administrator has actually approved the fingerprint. Only these are
#: ground truth; everything else is a candidate the Lab built and flagged.
PROMOTED = frozenset({"GROUND_TRUTH"})


@dataclass(frozen=True, slots=True)
class FingerprintSet:
    """The fingerprints available to a run, and where each came from."""

    points: tuple[FingerprintPoint, ...]
    promoted_ids: frozenset[str]
    engine_version: str = FINGERPRINT_ENGINE_VERSION

    @property
    def promoted(self) -> tuple[FingerprintPoint, ...]:
        return tuple(p for p in self.points if p.fingerprint_id in self.promoted_ids)

    @property
    def candidates(self) -> tuple[FingerprintPoint, ...]:
        return tuple(p for p in self.points if p.fingerprint_id not in self.promoted_ids)

    def is_promoted(self, fingerprint_id: str) -> bool:
        return fingerprint_id in self.promoted_ids

    def by_zone(self) -> dict[str, tuple[FingerprintPoint, ...]]:
        grouped: dict[str, list[FingerprintPoint]] = {}
        for point in self.points:
            grouped.setdefault(point.zone_id, []).append(point)
        return {zone: tuple(points) for zone, points in grouped.items()}

    def with_points(self, points: Iterable[FingerprintPoint]) -> "FingerprintSet":
        kept = tuple(points)
        return FingerprintSet(
            points=kept,
            promoted_ids=frozenset(p.fingerprint_id for p in kept if p.fingerprint_id in self.promoted_ids),
            engine_version=self.engine_version,
        )


class FingerprintEngineV1:
    """Median/percentile fingerprints with explicit visibility probabilities."""

    id = "fingerprint_stats_v1"
    version = FINGERPRINT_ENGINE_VERSION

    def build(
        self,
        observations: Sequence[Observation],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> tuple[FingerprintPoint, ...]:
        survey_samples = [o for o in observations if o.is_ground_truth and o.survey_point_id]
        by_point: dict[str, list[Observation]] = {}
        for sample in survey_samples:
            by_point.setdefault(str(sample.survey_point_id), []).append(sample)

        built: list[FingerprintPoint] = []
        for survey_point_id in sorted(by_point):
            point = model.survey_points.get(survey_point_id)
            if point is None:
                # A survey sample citing a point the site model does not define cannot be placed.
                # Reported by the quality phase as UNKNOWN_SURVEY_POINT rather than guessed at.
                continue
            fingerprint = self._build_point(
                point_samples=by_point[survey_point_id],
                survey_point=point,
                model=model,
                params=params.fingerprint,
                calibration=model.calibration,
                max_spread_db=params.fusion.max_calibration_spread_db,
            )
            if fingerprint is not None:
                built.append(fingerprint)
        return tuple(built)

    def _build_point(
        self,
        point_samples: list[Observation],
        survey_point,
        model: ReferenceModel,
        params: FingerprintParams,
        calibration: dict[str, ObserverCalibration],
        max_spread_db: float,
    ) -> FingerprintPoint | None:
        if len(point_samples) < params.min_samples_per_source:
            return None

        bursts = _bursts(point_samples)
        sessions = tuple(sorted({s.survey_session_id or "" for s in point_samples} - {""}))

        # Denominators are per sensor class, because absence of evidence is not evidence of
        # absence: a burst from a BLE-only observer says nothing about which access points were
        # audible, and counting it against a Wi-Fi source would deflate that source's visibility.
        burst_totals: dict[str, int] = {}
        for burst in bursts:
            observer = model.observers.get(burst.observer_id)
            for sensor in _SENSOR_CLASSES:
                if observer is None or observer.can_see(sensor):
                    burst_totals[sensor.value] = burst_totals.get(sensor.value, 0) + 1

        readings: dict[str, list[tuple[str, float]]] = {}
        sensor_of: dict[str, SensorType] = {}
        types: dict[str, IdentifierType] = {}
        seen_in_burst: dict[str, set[int]] = {}

        for index, burst in enumerate(bursts):
            for sample in burst.samples:
                if sample.rssi is None:
                    continue
                key = normalize_identifier(sample.radio_identifier, sample.identifier_type)
                value = normalized_rssi(sample.rssi, sample.observer_id, calibration, max_spread_db)
                if value is None:
                    continue
                readings.setdefault(key, []).append((sample.survey_session_id or "", value))
                sensor_of.setdefault(key, sample.sensor_type)
                types.setdefault(key, sample.identifier_type)
                seen_in_burst.setdefault(key, set()).add(index)

        entries: list[FingerprintEntry] = []
        for identifier in sorted(readings):
            values = [value for _, value in readings[identifier]]
            if len(values) < params.min_samples_per_source:
                continue
            sensor = sensor_of[identifier]
            denominator = burst_totals.get(_sensor_class(sensor).value, len(bursts)) or 1
            visibility = min(1.0, len(seen_in_burst[identifier]) / denominator)
            entries.append(
                FingerprintEntry(
                    radio_identifier=identifier,
                    identifier_type=types[identifier],
                    sample_count=len(values),
                    visibility_probability=visibility,
                    rssi_median=float(statistics.median(values)),
                    rssi_mean=float(statistics.fmean(values)),
                    rssi_stddev=float(statistics.stdev(values)) if len(values) > 1 else 0.0,
                    rssi_p10=_percentile(values, 0.10),
                    rssi_p90=_percentile(values, 0.90),
                    rssi_min=float(min(values)),
                    rssi_max=float(max(values)),
                    temporal_stability=_temporal_stability(readings[identifier]),
                )
            )

        if not entries:
            return None

        last_ms = max(sample.timestamp_ms for sample in point_samples)
        return FingerprintPoint(
            fingerprint_id=derive_id(
                "fingerprint", survey_point.survey_point_id, self.version, *sessions
            ),
            survey_point_id=survey_point.survey_point_id,
            building_id=survey_point.building_id,
            zone_id=survey_point.zone_id,
            x=survey_point.x,
            y=survey_point.y,
            observer_id=_dominant_observer(point_samples),
            status="CANDIDATE",
            sample_count=len(point_samples),
            session_count=len(sessions),
            source_survey_session_ids=sessions,
            engine_version=self.version,
            created_at_utc=format_ms(last_ms),
            entries=tuple(entries),
        )


def build_fingerprints(
    observations: Sequence[Observation],
    model: ReferenceModel,
    params: ParameterSet,
    engine: FingerprintEngineV1 | None = None,
) -> FingerprintSet:
    """Assemble the fingerprint set for a run.

    Promoted REFERENCE fingerprints win over freshly built ones for the same survey point. An
    administrator's promoted fingerprint may rest on months of sessions, while today's packages
    carry one; preferring today's because it is newer would be a downgrade disguised as an update.

    Candidates are kept, used, and marked. Estimates that lean on one carry
    ``CANDIDATE_FINGERPRINT``, so a site that has not been through promotion still produces usable
    output that is visibly provisional.
    """
    engine = engine or FingerprintEngineV1()
    promoted = {
        fingerprint.survey_point_id: fingerprint
        for fingerprint in model.fingerprints
        if fingerprint.status in PROMOTED
    }
    built = {
        fingerprint.survey_point_id: fingerprint
        for fingerprint in engine.build(observations, model, params)
    }

    merged: dict[str, FingerprintPoint] = {**built, **promoted}
    points = tuple(merged[key] for key in sorted(merged))
    return FingerprintSet(
        points=points,
        promoted_ids=frozenset(
            point.fingerprint_id for point in points if point.status in PROMOTED
        ),
        engine_version=engine.version,
    )


# -- internals ------------------------------------------------------------------------------------

_SENSOR_CLASSES = (SensorType.WIFI_SCAN, SensorType.BLE, SensorType.RTT)


def _sensor_class(sensor: SensorType) -> SensorType:
    if sensor is SensorType.WIFI_ASSOCIATION:
        return SensorType.WIFI_SCAN
    if sensor in _SENSOR_CLASSES:
        return sensor
    return SensorType.WIFI_SCAN


@dataclass(frozen=True, slots=True)
class _Burst:
    observer_id: str
    session_id: str
    samples: tuple[Observation, ...]


def _bursts(samples: Sequence[Observation]) -> tuple[_Burst, ...]:
    """Group one survey point's rows into per-observer, per-session bursts."""
    grouped: dict[tuple[str, str], list[Observation]] = {}
    for sample in samples:
        key = (sample.observer_id, sample.survey_session_id or "")
        grouped.setdefault(key, []).append(sample)

    bursts: list[_Burst] = []
    for (observer_id, session_id) in sorted(grouped):
        ordered = sorted(grouped[(observer_id, session_id)], key=lambda o: o.timestamp_ms)
        current: list[Observation] = []
        anchor = ordered[0].timestamp_ms
        for sample in ordered:
            if sample.timestamp_ms - anchor > BURST_MS and current:
                bursts.append(_Burst(observer_id, session_id, tuple(current)))
                current = []
                anchor = sample.timestamp_ms
            current.append(sample)
        if current:
            bursts.append(_Burst(observer_id, session_id, tuple(current)))
    return tuple(bursts)


def _percentile(values: Sequence[float], fraction: float) -> float:
    """Linear-interpolated percentile, defined for a single value as that value."""
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    position = fraction * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    weight = position - low
    return float(ordered[low] * (1 - weight) + ordered[high] * weight)


def _temporal_stability(readings: Sequence[tuple[str, float]]) -> float | None:
    """Agreement of a source's median across survey sessions, in [0, 1].

    Cross-session variance is the thing a single survey cannot see, and it is the reason the
    procedure asks for three visits on different days. ``None`` when only one session exists —
    reporting 1.0 would claim stability that was never measured.
    """
    per_session: dict[str, list[float]] = {}
    for session, value in readings:
        per_session.setdefault(session, []).append(value)
    if len(per_session) < 2:
        return None
    medians = [statistics.median(values) for values in per_session.values()]
    spread = statistics.stdev(medians)
    # 10 dB of cross-session disagreement is treated as fully unstable: beyond that the median is
    # not describing one RF environment.
    return max(0.0, min(1.0, 1.0 - spread / 10.0))


def _dominant_observer(samples: Sequence[Observation]) -> str | None:
    counts: dict[str, int] = {}
    for sample in samples:
        counts[sample.observer_id] = counts.get(sample.observer_id, 0) + 1
    if not counts:
        return None
    return max(sorted(counts), key=lambda observer: counts[observer])
