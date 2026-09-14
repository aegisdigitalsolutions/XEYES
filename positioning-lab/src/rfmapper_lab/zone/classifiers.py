"""Zone classifiers, per ``docs/10-positioning-mathematical-architecture.md`` §6.

Every classifier returns a *ranked list*, never a winner. The margin between the best and
second-best candidate is what indicates whether the site can actually discriminate those zones, and
it is an input to the confidence calculation — so discarding the runners-up at the point of decision
would throw away the only signal that says "these two zones look identical from here".

The shared distance function is where the specification's sharpest requirement lives: a source that
a location reliably sees, absent from the live vector, is strong evidence *against* that location.
The mismatch term implements that. Naive fingerprint matchers omit it, or paper over it by
substituting a floor RSSI for the missing source, which invents a measurement that was never taken.
"""

from __future__ import annotations

import math
from typing import Mapping, Sequence

from ..models import (
    FingerprintPoint,
    LiveVector,
    ReferenceModel,
    SensorType,
    ZoneCandidate,
    ZoneResult,
)
from ..params import ParameterSet, ZoneParams
from ..registry import zone_classifier

#: dBm shift that makes a signal vector positive, so cosine similarity has a defined direction.
_DBM_FLOOR = 100.0

_EPSILON = 1e-9


def signal_distance(
    live: Mapping[str, float],
    fingerprint: FingerprintPoint,
    params: ZoneParams,
) -> float:
    r"""Signal-space distance with an explicit non-overlap penalty.

    .. math:: D = \sqrt{\frac{1}{|S_\cap|}\sum_{s\in S_\cap}(\tilde r_s-\mu_s)^2}
              + \lambda \sum_{s\in S_\triangle} \pi_{s,\ell}

    Returns ``inf`` when the overlap is too small to compare, which is a refusal rather than a bad
    score: two shared access points is the minimum at which the first term means anything, and a
    single coincidence would otherwise rank as a confident match.

    A source present live but unknown to the fingerprint contributes nothing, because the
    fingerprint assigns it no visibility probability — the formula has no term for "a location
    never saw this", and inventing one here would be a different algorithm.
    """
    entries = fingerprint.by_identifier()
    shared = [identifier for identifier in live if identifier in entries]
    if len(shared) < params.min_shared_sources:
        return math.inf

    squared = 0.0
    for identifier in shared:
        delta = live[identifier] - entries[identifier].rssi_median
        squared += delta * delta
    rms = math.sqrt(squared / len(shared))

    missing_penalty = sum(
        entries[identifier].visibility_probability
        for identifier in entries
        if identifier not in live
    )
    return rms + params.mismatch_penalty * missing_penalty


def _observed(vector: LiveVector) -> dict[str, float]:
    """One normalized RSSI per source for this window."""
    return {
        identifier: float(measurement.rssi_normalized or 0.0)
        for identifier, measurement in vector.strongest_by_source().items()
    }


def _ranked(scores: Mapping[str, float], model: ReferenceModel, method: str, extra=None) -> ZoneResult:
    """Sort zones by score, breaking ties on zone id so the output is deterministic."""
    candidates = []
    for zone_id in sorted(scores, key=lambda key: (-scores[key], key)):
        zone = model.zones.get(zone_id)
        if zone is None:
            continue
        details = (extra or {}).get(zone_id, {})
        candidates.append(
            ZoneCandidate(
                zone_id=zone_id,
                building_id=zone.building_id,
                score=scores[zone_id],
                distance=details.get("distance"),
                fingerprint_ids=tuple(details.get("fingerprint_ids", ())),
            )
        )
    return ZoneResult(candidates=tuple(candidates), method=method)


@zone_classifier
class ZoneNearestNeighbourV1:
    """The RADAR baseline: closest fingerprint in signal space wins its zone.

    Kept permanently as the floor every more elaborate method has to beat. A classifier that cannot
    beat nearest neighbour is not earning its complexity.
    """

    id = "zone_nn_v1"
    version = "1.0.0"

    def classify(
        self,
        vector: LiveVector,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult:
        live = _observed(vector)
        best: dict[str, tuple[float, str]] = {}
        for fingerprint in fingerprints:
            distance = signal_distance(live, fingerprint, params.zone)
            if not math.isfinite(distance):
                continue
            current = best.get(fingerprint.zone_id)
            if current is None or distance < current[0]:
                best[fingerprint.zone_id] = (distance, fingerprint.fingerprint_id)

        scores = {zone: 1.0 / (1.0 + distance) for zone, (distance, _) in best.items()}
        extra = {
            zone: {"distance": distance, "fingerprint_ids": (fingerprint_id,)}
            for zone, (distance, fingerprint_id) in best.items()
        }
        return _ranked(scores, model, self.id, extra)


@zone_classifier
class ZoneWeightedKnnV1:
    """Weighted k-nearest fingerprints voting on zone, weight ``1/(d+eps)``."""

    id = "zone_wknn_v1"
    version = "1.0.0"

    def classify(
        self,
        vector: LiveVector,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult:
        live = _observed(vector)
        ranked = sorted(
            (
                (signal_distance(live, fingerprint, params.zone), fingerprint)
                for fingerprint in fingerprints
            ),
            key=lambda item: (item[0], item[1].fingerprint_id),
        )
        neighbours = [item for item in ranked if math.isfinite(item[0])][: params.zone.k_neighbours]
        if not neighbours:
            return ZoneResult((), self.id)

        weights: dict[str, float] = {}
        supporting: dict[str, list[str]] = {}
        closest: dict[str, float] = {}
        for distance, fingerprint in neighbours:
            weight = 1.0 / (distance + 1.0)
            weights[fingerprint.zone_id] = weights.get(fingerprint.zone_id, 0.0) + weight
            supporting.setdefault(fingerprint.zone_id, []).append(fingerprint.fingerprint_id)
            closest[fingerprint.zone_id] = min(closest.get(fingerprint.zone_id, math.inf), distance)

        total = sum(weights.values()) or 1.0
        scores = {zone: weight / total for zone, weight in weights.items()}
        extra = {
            zone: {"distance": closest[zone], "fingerprint_ids": tuple(sorted(supporting[zone]))}
            for zone in weights
        }
        return _ranked(scores, model, self.id, extra)


@zone_classifier
class ZoneCosineV1:
    """Cosine similarity over shared sources.

    Scale-invariant, which is exactly the uncalibrated-chipset failure mode: a device reporting
    every RSSI 6 dB low still points in the same direction in signal space. dBm values are shifted
    positive first, because cosine similarity between two vectors of negative numbers is dominated
    by the shared offset rather than by the pattern.
    """

    id = "zone_cosine_v1"
    version = "1.0.0"

    def classify(
        self,
        vector: LiveVector,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult:
        live = _observed(vector)
        best: dict[str, tuple[float, str]] = {}
        for fingerprint in fingerprints:
            entries = fingerprint.by_identifier()
            shared = sorted(set(live) & set(entries))
            if len(shared) < params.zone.min_shared_sources:
                continue
            left = [max(0.0, live[identifier] + _DBM_FLOOR) for identifier in shared]
            right = [max(0.0, entries[identifier].rssi_median + _DBM_FLOOR) for identifier in shared]
            norm = math.sqrt(sum(v * v for v in left)) * math.sqrt(sum(v * v for v in right))
            if norm < _EPSILON:
                continue
            similarity = sum(a * b for a, b in zip(left, right)) / norm

            # The same absence penalty as the distance metric, expressed multiplicatively: a
            # location whose reliable sources are missing gets its similarity discounted, not
            # forgiven, and pure cosine would forgive it entirely.
            missing = sum(
                entries[identifier].visibility_probability
                for identifier in entries
                if identifier not in live
            )
            score = similarity / (1.0 + missing)
            current = best.get(fingerprint.zone_id)
            if current is None or score > current[0]:
                best[fingerprint.zone_id] = (score, fingerprint.fingerprint_id)

        scores = {zone: score for zone, (score, _) in best.items()}
        extra = {
            zone: {"distance": None, "fingerprint_ids": (fingerprint_id,)}
            for zone, (_, fingerprint_id) in best.items()
        }
        return _ranked(scores, model, self.id, extra)


@zone_classifier
class ZoneBayesV1:
    r"""Naive Bayes over per-source Gaussians, with an explicit absent-source term.

    .. math:: P(\ell \mid \mathbf v) \propto P_0(\ell)\prod_{s\in\text{seen}} N(\tilde r_s;\mu_s,\sigma_s)
              \prod_{s\notin\text{seen}} (1-\pi_{s,\ell})

    Two details make it work rather than merely look principled. The per-source sigma is floored,
    because a surveyed sigma of zero turns the Gaussian into a delta function and one dB of drift
    annihilates an otherwise perfect match. And the absent-source probability is floored too, so a
    single unexpected absence cannot zero out a location the rest of the evidence strongly supports.
    """

    id = "zone_bayes_v1"
    version = "1.0.0"

    def classify(
        self,
        vector: LiveVector,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult:
        live = _observed(vector)
        zone = params.zone

        log_likelihoods: dict[str, tuple[float, str]] = {}
        for fingerprint in fingerprints:
            entries = fingerprint.by_identifier()
            shared = sorted(set(live) & set(entries))
            if len(shared) < zone.min_shared_sources:
                continue

            total = 0.0
            for identifier in shared:
                entry = entries[identifier]
                sigma = max(entry.rssi_stddev or 0.0, zone.min_sigma_db)
                delta = live[identifier] - entry.rssi_median
                total += -0.5 * (delta / sigma) ** 2 - math.log(sigma * math.sqrt(2 * math.pi))
                # Seeing a source this location rarely sees is itself weak evidence against it.
                total += math.log(max(entry.visibility_probability, zone.absent_probability_floor))

            for identifier, entry in entries.items():
                if identifier in live:
                    continue
                absent = max(1.0 - entry.visibility_probability, zone.absent_probability_floor)
                total += math.log(absent)

            current = log_likelihoods.get(fingerprint.zone_id)
            if current is None or total > current[0]:
                log_likelihoods[fingerprint.zone_id] = (total, fingerprint.fingerprint_id)

        if not log_likelihoods:
            return ZoneResult((), self.id)

        # Softmax over zones, shifted by the maximum for numerical stability. The result is a
        # posterior over the zones that had enough overlap to be evaluated at all, which is not the
        # same thing as a posterior over the site — and the margin, not the absolute value, is what
        # the confidence calculation uses.
        peak = max(value for value, _ in log_likelihoods.values())
        weights = {
            zone_id: math.exp(value - peak) for zone_id, (value, _) in log_likelihoods.items()
        }
        total_weight = sum(weights.values()) or 1.0
        scores = {zone_id: weight / total_weight for zone_id, weight in weights.items()}
        extra = {
            zone_id: {"distance": None, "fingerprint_ids": (fingerprint_id,)}
            for zone_id, (_, fingerprint_id) in log_likelihoods.items()
        }
        return _ranked(scores, model, self.id, extra)


@zone_classifier
class ZoneAnchorV1:
    """Coarse zone evidence from ``ZONE_ANCHOR`` infrastructure visibility.

    For a site whose only fixed reference is a long-range router, this is the honest ceiling: a
    router's coverage tells you which area a device is probably in and nothing more. It never
    produces coordinates, and it deliberately cannot: a published coverage radius is a planning
    figure, not a distance measurement, and treating one as the other is the most likely route to
    fabricated precision in a system like this.
    """

    id = "zone_anchor_v1"
    version = "1.0.0"

    def classify(
        self,
        vector: LiveVector,
        fingerprints: Sequence[FingerprintPoint],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult:
        anchors: dict[str, str] = {}
        for node in model.infrastructure.values():
            if node.zone_id is None:
                continue
            for identifier in (node.known_bssid, node.known_ble_identifier):
                if identifier:
                    anchors[identifier] = node.zone_id

        weights: dict[str, float] = {}
        for measurement in vector.measurements:
            zone_id = anchors.get(measurement.radio_identifier)
            if zone_id is None or measurement.rssi_normalized is None:
                continue
            # Strength ordering only, deliberately crude: -40 dBm is nearer than -80 dBm, and
            # nothing here converts either into metres.
            strength = max(0.0, min(1.0, (measurement.rssi_normalized + _DBM_FLOOR) / 60.0))
            weights[zone_id] = weights.get(zone_id, 0.0) + strength

        if not weights:
            return ZoneResult((), self.id)
        total = sum(weights.values()) or 1.0
        return _ranked({zone: weight / total for zone, weight in weights.items()}, model, self.id)


def sensor_supports_zone_evidence(sensor: SensorType) -> bool:
    """Whether a sensor type carries usable zone evidence at all.

    A GNSS fix positions the *observer*, not the device it saw, and a manual entry is an assertion
    rather than a measurement. Neither belongs in a fingerprint match.
    """
    return sensor in (
        SensorType.WIFI_SCAN,
        SensorType.WIFI_ASSOCIATION,
        SensorType.BLE,
        SensorType.RTT,
        SensorType.ZONE_ANCHOR,
    )
