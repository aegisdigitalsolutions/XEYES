"""Live-vector assembly and multi-observer fusion.

Two rules from ``docs/10-positioning-mathematical-architecture.md`` §6 shape this module.

**Fuse, never argmax.** Picking the observer with the strongest RSSI is explicitly not a method in
this system. Each observer produces its own likelihood over zones and they are combined with
weights, so three consistent weak observers can outvote one loud one.

**Two observations are never simultaneous merely because they are adjacent in a file.** Window
membership is decided by timestamp against a per-sensor width, and a measurement's age inside its
window decays its weight.

Attribution follows ``docs/17-identity-and-attribution-policy.md`` §3 exactly: an exact identifier
match against the enrolled registry, or the Master's own verdict, and nothing else. No co-location
inference, no linking of randomized addresses, no "probably the same device".
"""

from __future__ import annotations

import math
from typing import Mapping, Sequence

from ..models import (
    IdentifierType,
    LiveVector,
    Measurement,
    Observation,
    RECIPROCAL_FRAME,
    ReferenceModel,
    SensorType,
    ZoneCandidate,
    ZoneResult,
    self_frame,
)
from ..params import ParameterSet
from ..parsing.normalize import normalize_identifier, normalized_rssi, offset_for, window_ms
from ..timeutil import format_ms

#: Probability floor for a zone an observer did not rank at all. A zone one observer cannot see
#: must be weakened, not eliminated: an observer with no view of a zone has no opinion about it,
#: and treating silence as a veto would let the narrowest observer decide.
UNRANKED_FLOOR = 1e-4


def attribute_device(observation: Observation, model: ReferenceModel) -> str | None:
    """The managed device this observation belongs to, or ``None``.

    ``None`` is the normal answer. Most rows are environmental observations, and the system has no
    authority to claim otherwise.
    """
    if observation.identifier_type.is_ephemeral:
        # A resolvable random address is not an identity. It may well be an enrolled device, and
        # the administrator can enroll its service UUID to make it attributable — the software does
        # not make that leap on its own.
        return None

    claimed = observation.target_device_id
    if claimed and claimed in model.devices:
        return claimed

    normalized = normalize_identifier(observation.radio_identifier, observation.identifier_type)
    by_identifier = model.device_for_identifier(normalized)
    if by_identifier:
        return by_identifier

    if observation.ble_service_uuid:
        # The recommended rule type: a dedicated service UUID is unambiguous, works across
        # platforms and survives MAC randomization.
        uuid_match = model.device_for_identifier(
            normalize_identifier(observation.ble_service_uuid, IdentifierType.BLE_SERVICE_UUID)
        )
        if uuid_match:
            return uuid_match
    return None


def build_vectors(
    observations: Sequence[Observation],
    model: ReferenceModel,
    params: ParameterSet,
) -> tuple[LiveVector, ...]:
    """Group attributed observations into one live vector per device per window.

    Anchors are on a fixed grid derived from the epoch rather than from the first observation seen,
    so a reprocess over a wider date range produces the same windows for the overlapping days.
    Without that, every rerun would shift every window and no two generations would be comparable.

    The grid step is the *narrowest* per-sensor window, not the default one. A coarser grid puts
    every anchor up to half a step from the rows around it, so a sensor whose window is narrower
    than half the step has rows that fall between anchors and belong to no window at all. That
    discards evidence whose only fault is its arrival time, and it does so silently — with a 5 s
    grid and a 2 s BLE window, a tag advertising on a 10 s cadence contributes nothing whatsoever.
    """
    windows = (
        params.fusion.window_ms_wifi,
        params.fusion.window_ms_ble,
        params.fusion.window_ms_rtt,
        params.fusion.window_ms_default,
    )
    step = max(1, min(windows))
    widest = max(windows)

    # Two routes into a device's evidence, and they occupy different signal spaces.
    #
    #   *Heard*: an observer detected the device's radio. The reference point is the observer, by
    #   reciprocity, and the measurement carries no ranging.
    #
    #   *Self*: the observer is itself an enrolled device, so its own scans describe where it is.
    #   These keep the infrastructure source keys, match fingerprints directly, and are the only
    #   rows from which RTT can position anything — a range measures the distance from the radio
    #   that made it.
    by_device: dict[str, list[tuple[Observation, bool]]] = {}
    for observation in observations:
        if observation.rssi is None and observation.rtt_distance_mm is None:
            continue
        heard_device = attribute_device(observation, model)
        if heard_device is not None:
            by_device.setdefault(heard_device, []).append((observation, True))
            continue
        self_device = model.device_for_observer(observation.observer_id)
        if self_device is not None:
            by_device.setdefault(self_device, []).append((observation, False))

    vectors: list[LiveVector] = []
    for device_id in sorted(by_device):
        rows = sorted(by_device[device_id], key=lambda item: (item[0].timestamp_ms, item[0].observation_id))
        anchors = sorted({(row.timestamp_ms // step) * step + step // 2 for row, _ in rows})

        cursor = 0
        for anchor in anchors:
            # Rows are time-ordered, so the window's lower bound only ever moves forward.
            while cursor < len(rows) and rows[cursor][0].timestamp_ms < anchor - widest:
                cursor += 1

            measurements: list[Measurement] = []
            for row, heard in rows[cursor:]:
                age = row.timestamp_ms - anchor
                if age > widest:
                    break
                if abs(age) > window_ms(row.sensor_type, params.fusion):
                    continue
                measurements.append(
                    measurement_of(row, abs(age), model, params, measures_device=heard)
                )

            if measurements:
                vectors.append(
                    LiveVector(
                        device_id=device_id,
                        timestamp_ms=anchor,
                        timestamp_utc=format_ms(anchor),
                        measurements=tuple(measurements),
                    )
                )
    return tuple(vectors)


def signal_source_key(
    observation: Observation,
    model: ReferenceModel,
    measures_device: bool,
) -> str:
    """The identifier this measurement occupies in signal space.

    For a row that measures a *target device*, the reference point is the observer, not the device:
    the device's own identifier appears in no fingerprint, while the observer's advertised
    identifier does (see :meth:`ReferenceModel.source_for_observer`). For every other row the
    source is the source.

    When an observer has no registered identifier the key is namespaced instead of guessed. It then
    matches nothing, the fingerprint methods decline, and the estimate falls back to a coarser
    tier — which is the correct outcome, and better than matching against a signal space it does
    not belong to.
    """
    if measures_device:
        registered = model.source_for_observer(observation.observer_id)
        return registered or f"observer:{observation.observer_id}"
    return normalize_identifier(observation.radio_identifier, observation.identifier_type)


def measurement_of(
    observation: Observation,
    age_ms: int,
    model: ReferenceModel,
    params: ParameterSet,
    measures_device: bool,
) -> Measurement:
    """One row as a window member: normalized, keyed into signal space, framed.

    Shared with the benchmark harness, which assembles vectors from held-out survey samples. A
    second copy of this conversion would let the harness measure something the pipeline never
    computes.
    """
    return Measurement(
        observer_id=observation.observer_id,
        radio_identifier=signal_source_key(observation, model, measures_device),
        identifier_type=observation.identifier_type,
        sensor_type=observation.sensor_type,
        rssi_raw=observation.rssi,
        rssi_normalized=normalized_rssi(
            observation.rssi,
            observation.observer_id,
            model.calibration,
            params.fusion.max_calibration_spread_db,
        ),
        age_ms=age_ms,
        freshness=observation.freshness,
        observation_id=observation.observation_id,
        rtt_distance_mm=observation.rtt_distance_mm,
        rtt_stddev_mm=observation.rtt_stddev_mm,
        frame=RECIPROCAL_FRAME if measures_device else self_frame(observation.observer_id),
    )


def split_by_frame(vector: LiveVector) -> dict[str, LiveVector]:
    """One sub-vector per frame of reference, so each can form an independent opinion.

    A frame, not an observer. Splitting per observer is the intuitive decomposition and it is wrong
    for reciprocal evidence: an observer that merely *heard* the device contributes exactly one
    number, and one number can never reach the two shared sources a fingerprint match requires. Its
    reciprocal reading belongs alongside the other observers' readings in the device's own signal
    space, where together they form a vector with as many sources as there were observers. Held
    apart they are a set of unclassifiable singletons, and the device gets no zone however many
    observers heard it.
    """
    grouped: dict[str, list[Measurement]] = {}
    for measurement in vector.measurements:
        grouped.setdefault(measurement.frame, []).append(measurement)
    return {
        frame: LiveVector(
            device_id=vector.device_id,
            timestamp_ms=vector.timestamp_ms,
            timestamp_utc=vector.timestamp_utc,
            measurements=tuple(grouped[frame]),
        )
        for frame in sorted(grouped)
    }


def frame_weights(
    vector: LiveVector,
    model: ReferenceModel,
    params: ParameterSet,
) -> dict[str, float]:
    r"""The fusion weight per frame, :math:`W = w^{fresh} w^{cal} w^{sensor} w^{count}`.

    Each factor answers a specific failure. ``w_fresh`` stops a half-hour-old cached scan from
    weighing as much as a fresh one — the platform tells us which it is, so there is no excuse for
    treating them alike. ``w_cal`` discounts an observer whose chipset offset has never been
    measured. ``w_sensor`` ranks a genuine range above an RSSI guess. ``w_count`` grows as
    :math:`\sqrt{n}` and is capped, so one chatty observer cannot swamp three quiet ones.

    For a frame that pools several observers, ``w_cal`` is their mean rather than any one of them:
    the frame is only as trustworthy as its contributors on average. The per-observer offsets
    themselves are already applied to each measurement individually by ``normalized_rssi``, so
    pooling costs no calibration accuracy — only the ability to discount one bad observer inside an
    otherwise good frame, which is the price of being able to classify reciprocal evidence at all.
    """
    fusion = params.fusion
    weights: dict[str, float] = {}

    for frame, sub_vector in split_by_frame(vector).items():
        observers = sub_vector.observer_ids
        per_observer = []
        for observer_id in observers:
            _, calibrated = offset_for(
                observer_id, model.calibration, fusion.max_calibration_spread_db
            )
            factor = 1.0 if calibrated else fusion.uncalibrated_weight
            observer = model.observers.get(observer_id)
            if observer is not None and observer.fixed_observer:
                # A surveyed, stationary observer knows where it is. Its evidence is reference
                # quality in a way a phone in a pocket cannot be.
                factor *= 1.25
            per_observer.append(factor)
        w_cal = sum(per_observer) / len(per_observer) if per_observer else 1.0

        w_fresh = 0.0
        w_sensor = 0.0
        for measurement in sub_vector.measurements:
            decay = 0.5 ** (measurement.age_ms / max(1, fusion.freshness_half_life_ms))
            if measurement.freshness == "CACHED":
                decay *= fusion.cached_weight
            elif measurement.freshness not in ("FRESH", "REALTIME"):
                decay *= fusion.unknown_freshness_weight
            w_fresh = max(w_fresh, decay)
            w_sensor = max(
                w_sensor, fusion.sensor_weights.get(measurement.sensor_type.value, 0.2)
            )

        independent = len({m.radio_identifier for m in sub_vector.measurements})
        w_count = min(fusion.count_weight_cap, math.sqrt(independent))

        weights[frame] = max(0.0, w_fresh * w_cal * w_sensor * w_count)
    return weights


def fuse_zones(
    vector: LiveVector,
    classifier,
    fingerprints: Sequence,
    model: ReferenceModel,
    params: ParameterSet,
    prior: Mapping[str, float] | None = None,
) -> ZoneResult:
    r"""Combine per-frame zone likelihoods: :math:`P(\ell)\propto P_0(\ell)\prod_f L_f(\ell)^{W_f}`.

    With a single frame this reduces to that frame's own ranking, which is the correct degenerate
    case and not a special path through the code. A device that both scanned for itself and was
    heard by fixed observers contributes two frames, and they are combined exactly as two observers
    would have been.
    """
    weights = frame_weights(vector, model, params)
    per_frame = {
        frame: classifier.classify(sub_vector, fingerprints, model, params)
        for frame, sub_vector in split_by_frame(vector).items()
    }

    zone_ids = sorted(
        {candidate.zone_id for result in per_frame.values() for candidate in result.candidates}
    )
    if not zone_ids:
        return ZoneResult((), getattr(classifier, "id", "unknown"))

    log_posterior = {
        zone_id: math.log(max(UNRANKED_FLOOR, (prior or {}).get(zone_id, 1.0))) for zone_id in zone_ids
    }
    supporting: dict[str, set[str]] = {zone_id: set() for zone_id in zone_ids}
    distances: dict[str, float] = {}

    for frame, result in per_frame.items():
        weight = weights.get(frame, 0.0)
        if weight <= 0.0:
            continue
        scores = {candidate.zone_id: candidate.score for candidate in result.candidates}
        total = sum(scores.values()) or 1.0
        for zone_id in zone_ids:
            likelihood = max(UNRANKED_FLOOR, scores.get(zone_id, 0.0) / total)
            log_posterior[zone_id] += weight * math.log(likelihood)
        for candidate in result.candidates:
            supporting[candidate.zone_id].update(candidate.fingerprint_ids)
            if candidate.distance is not None:
                distances[candidate.zone_id] = min(
                    distances.get(candidate.zone_id, math.inf), candidate.distance
                )

    peak = max(log_posterior.values())
    unnormalized = {zone_id: math.exp(value - peak) for zone_id, value in log_posterior.items()}
    total = sum(unnormalized.values()) or 1.0

    candidates = []
    for zone_id in sorted(unnormalized, key=lambda key: (-unnormalized[key], key)):
        zone = model.zones.get(zone_id)
        if zone is None:
            continue
        candidates.append(
            ZoneCandidate(
                zone_id=zone_id,
                building_id=zone.building_id,
                score=unnormalized[zone_id] / total,
                distance=distances.get(zone_id),
                fingerprint_ids=tuple(sorted(supporting[zone_id])),
            )
        )
    return ZoneResult(tuple(candidates), getattr(classifier, "id", "unknown"))


def sensor_present(vector: LiveVector, sensor: SensorType) -> bool:
    return any(measurement.sensor_type is sensor for measurement in vector.measurements)
