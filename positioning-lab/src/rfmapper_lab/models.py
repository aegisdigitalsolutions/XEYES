"""The Lab's in-memory types, mirroring the cross-component JSON contract.

These are the Python half of the same schema the Kotlin ``core-model`` implements. The invariants
are repeated here rather than trusted from the other side, because an estimate is written by this
process and a contract enforced only at the reader is not enforced at all.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Iterable


class SensorType(str, Enum):
    WIFI_SCAN = "WIFI_SCAN"
    WIFI_ASSOCIATION = "WIFI_ASSOCIATION"
    BLE = "BLE"
    RTT = "RTT"
    GPS = "GPS"
    ZONE_ANCHOR = "ZONE_ANCHOR"
    MANUAL = "MANUAL"
    IMPORT = "IMPORT"


class IdentifierType(str, Enum):
    WIFI_BSSID = "WIFI_BSSID"
    WIFI_SSID = "WIFI_SSID"
    BLE_MAC_PUBLIC = "BLE_MAC_PUBLIC"
    BLE_MAC_RANDOM = "BLE_MAC_RANDOM"
    BLE_SERVICE_UUID = "BLE_SERVICE_UUID"
    BLE_IBEACON = "BLE_IBEACON"
    GNSS_FIX = "GNSS_FIX"
    OBSERVER_SELF = "OBSERVER_SELF"
    OTHER = "OTHER"

    @property
    def is_ephemeral(self) -> bool:
        """A resolvable random address is not an identity and is never attributed to a device."""
        return self is IdentifierType.BLE_MAC_RANDOM


class PrecisionTier(str, Enum):
    SITE_PRESENCE = "SITE_PRESENCE"
    BUILDING = "BUILDING"
    ZONE = "ZONE"
    APPROXIMATE_POSITION = "APPROXIMATE_POSITION"
    PRECISION_RANGE = "PRECISION_RANGE"

    @property
    def has_coordinates(self) -> bool:
        return self in (PrecisionTier.APPROXIMATE_POSITION, PrecisionTier.PRECISION_RANGE)

    @property
    def depth(self) -> int:
        return list(PrecisionTier).index(self)


class MovementState(str, Enum):
    STATIONARY = "STATIONARY"
    MOVING = "MOVING"
    ZONE_TRANSITION = "ZONE_TRANSITION"
    LOST = "LOST"
    REAPPEARED = "REAPPEARED"
    UNCERTAIN = "UNCERTAIN"


class ZoneEventType(str, Enum):
    RF_ZONE_ENTER = "RF_ZONE_ENTER"
    RF_ZONE_EXIT = "RF_ZONE_EXIT"
    RF_ZONE_TRANSITION = "RF_ZONE_TRANSITION"


class TopologyStatus(str, Enum):
    ADJACENT = "ADJACENT"
    NON_ADJACENT = "NON_ADJACENT"
    RESTRICTED = "RESTRICTED"
    UNKNOWN_EDGE = "UNKNOWN_EDGE"


class DatasetKind(str, Enum):
    REAL = "REAL"
    SYNTHETIC = "SYNTHETIC"
    MIXED = "MIXED"


class Severity(str, Enum):
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"


@dataclass(frozen=True, slots=True)
class Observation:
    """One RAW row, exactly as it arrived. Never mutated by any phase.

    All 29 canonical columns are carried, including the ones no current algorithm reads. Dropping a
    field at parse time would make it unavailable to the next algorithm version without re-reading
    every package, and re-running history with a new engine is the whole point of the design.
    """

    observation_id: str
    timestamp_utc: str
    timestamp_ms: int
    observer_id: str
    sensor_type: SensorType
    radio_identifier: str
    identifier_type: IdentifierType
    schema_version: str = ""
    observer_device_type: str = ""
    rssi: int | None = None
    ssid: str | None = None
    bssid: str | None = None
    ble_service_uuid: str | None = None
    manufacturer_data: str | None = None
    frequency: int | None = None
    channel: int | None = None
    tx_power: int | None = None
    rtt_distance_mm: int | None = None
    rtt_stddev_mm: int | None = None
    latitude: float | None = None
    longitude: float | None = None
    location_accuracy_m: float | None = None
    building_id: str | None = None
    zone_id: str | None = None
    #: The *observer's* own site-frame position, not the target's. Confusing the two would turn an
    #: observer's location into a claim about the device it saw.
    observer_x: float | None = None
    observer_y: float | None = None
    target_device_id: str | None = None
    confidence: float | None = None
    metadata: dict[str, str] = field(default_factory=dict)

    def with_metadata(self, metadata: dict[str, str]) -> "Observation":
        return replace(self, metadata=metadata)

    @property
    def sample_kind(self) -> str:
        return self.metadata.get("sample_kind", "ORDINARY")

    @property
    def is_ground_truth(self) -> bool:
        """Ground truth requires a survey session. A claim without one is just a claim."""
        return self.sample_kind == "GROUND_TRUTH" and bool(self.metadata.get("survey_session_id"))

    @property
    def survey_point_id(self) -> str | None:
        return self.metadata.get("survey_point_id")

    @property
    def survey_session_id(self) -> str | None:
        return self.metadata.get("survey_session_id")

    @property
    def session_id(self) -> str | None:
        return self.metadata.get("session_id")

    @property
    def freshness(self) -> str:
        return self.metadata.get("result_freshness", "UNKNOWN")

    @property
    def monotonic_ms(self) -> int | None:
        raw = self.metadata.get("clock_elapsed_realtime_ms")
        try:
            return int(raw) if raw is not None else None
        except ValueError:
            return None


@dataclass(frozen=True, slots=True)
class FingerprintEntry:
    """One source's *distribution* at a surveyed location. Never a single RSSI."""

    radio_identifier: str
    identifier_type: IdentifierType
    sample_count: int
    visibility_probability: float
    rssi_median: float
    rssi_mean: float | None = None
    rssi_stddev: float | None = None
    rssi_p10: float | None = None
    rssi_p90: float | None = None
    rssi_min: float | None = None
    rssi_max: float | None = None
    temporal_stability: float | None = None

    def __post_init__(self) -> None:
        if not 0.0 <= self.visibility_probability <= 1.0:
            raise ValueError(
                f"visibility_probability {self.visibility_probability} outside [0,1]"
            )


@dataclass(frozen=True, slots=True)
class FingerprintPoint:
    fingerprint_id: str
    survey_point_id: str
    building_id: str
    zone_id: str
    x: float | None = None
    y: float | None = None
    observer_id: str | None = None
    status: str = "CANDIDATE"
    sample_count: int = 0
    session_count: int | None = None
    source_survey_session_ids: tuple[str, ...] = ()
    engine_version: str | None = None
    created_at_utc: str | None = None
    entries: tuple[FingerprintEntry, ...] = ()

    @property
    def has_coordinates(self) -> bool:
        return self.x is not None and self.y is not None

    def by_identifier(self) -> dict[str, FingerprintEntry]:
        return {entry.radio_identifier: entry for entry in self.entries}


@dataclass(frozen=True, slots=True)
class PositionEstimate:
    """A derived spatial claim, with the anti-fabrication rules enforced in the constructor.

    The two rules that matter:

    * a coordinate without an uncertainty cannot be constructed, because a bare point on a map
      reads as a fact;
    * ``PRECISION_RANGE`` requires genuine RTT evidence in the supporting set, so no amount of
      confident RSSI can be promoted into it.
    """

    estimate_id: str
    algorithm_version: str
    device_id: str
    timestamp_utc: str
    computed_at_utc: str
    precision_tier: PrecisionTier
    confidence: float
    method: str
    supporting_observer_ids: tuple[str, ...]
    supporting_observation_ids: tuple[str, ...]
    engine_versions: dict[str, str] = field(default_factory=dict)
    parameter_set_sha256: str | None = None
    building_id: str | None = None
    zone_id: str | None = None
    x: float | None = None
    y: float | None = None
    horizontal_uncertainty_m: float | None = None
    confidence_factors: dict[str, float] = field(default_factory=dict)
    source_dataset_ids: tuple[str, ...] = ()
    reference_model_id: str | None = None
    calibration_set_id: str | None = None
    quality_flags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not self.device_id:
            raise ValueError("device_id must not be blank")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"confidence {self.confidence} outside [0,1]")
        if (self.x is None) != (self.y is None):
            raise ValueError("x and y must both be present or both absent")
        if self.x is not None:
            if self.horizontal_uncertainty_m is None or self.horizontal_uncertainty_m <= 0:
                raise ValueError(
                    "a coordinate requires a positive horizontal_uncertainty_m: never emit a "
                    "position without uncertainty"
                )
            if not math.isfinite(self.x) or not math.isfinite(self.y or 0.0):
                raise ValueError("coordinates must be finite")
            if not self.precision_tier.has_coordinates:
                raise ValueError(
                    f"coordinates require APPROXIMATE_POSITION or PRECISION_RANGE, "
                    f"got {self.precision_tier}"
                )
        if self.precision_tier.has_coordinates and self.x is None:
            raise ValueError(f"tier {self.precision_tier} requires coordinates")
        if self.zone_id is not None and self.building_id is None:
            raise ValueError("zone_id requires building_id")
        if self.precision_tier is PrecisionTier.SITE_PRESENCE and self.zone_id is not None:
            raise ValueError("SITE_PRESENCE must not assert a zone")
        if not self.supporting_observation_ids:
            raise ValueError("an estimate with no supporting observation is not an estimate")
        if not self.supporting_observer_ids:
            raise ValueError("supporting_observer_ids must not be empty")


@dataclass(frozen=True, slots=True)
class ZoneTransition:
    transition_id: str
    algorithm_version: str
    device_id: str
    event_type: ZoneEventType
    transition_start_utc: str
    transition_confirmed_utc: str
    confidence: float
    topology_status: TopologyStatus
    supporting_observer_ids: tuple[str, ...]
    supporting_estimate_ids: tuple[str, ...]
    engine_versions: dict[str, str] = field(default_factory=dict)
    origin_zone_id: str | None = None
    destination_zone_id: str | None = None
    quality_flags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"confidence {self.confidence} outside [0,1]")
        if self.event_type is ZoneEventType.RF_ZONE_TRANSITION:
            if not (self.origin_zone_id and self.destination_zone_id):
                raise ValueError("RF_ZONE_TRANSITION requires both origin and destination")
        elif self.event_type is ZoneEventType.RF_ZONE_ENTER:
            if not self.destination_zone_id:
                raise ValueError("RF_ZONE_ENTER requires a destination")
        elif not self.origin_zone_id:
            raise ValueError("RF_ZONE_EXIT requires an origin")
        if not self.supporting_estimate_ids:
            raise ValueError("a transition requires supporting estimates")


@dataclass(frozen=True, slots=True)
class MovementEstimate:
    movement_id: str
    algorithm_version: str
    device_id: str
    timestamp_utc: str
    state: MovementState
    confidence: float
    engine_versions: dict[str, str] = field(default_factory=dict)
    origin_zone_id: str | None = None
    candidate_destination_zone_id: str | None = None
    confirmed_destination_zone_id: str | None = None
    direction: str | None = None
    supporting_estimate_ids: tuple[str, ...] = ()
    quality_flags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"confidence {self.confidence} outside [0,1]")
        if self.state is MovementState.ZONE_TRANSITION:
            if not (self.origin_zone_id and self.candidate_destination_zone_id):
                raise ValueError("ZONE_TRANSITION requires an origin and a candidate destination")
        if self.state not in (MovementState.LOST, MovementState.UNCERTAIN):
            if not self.supporting_estimate_ids:
                raise ValueError(f"state {self.state} requires supporting estimates")


@dataclass(frozen=True, slots=True)
class QualityFlag:
    """An advisory finding for a human. Nothing consumes one to change behaviour automatically."""

    flag_id: str
    created_at_utc: str
    severity: Severity
    code: str
    scope: str
    message: str
    scope_id: str | None = None
    algorithm_version: str | None = None
    evidence: dict[str, Any] = field(default_factory=dict)
    acknowledged_at_utc: str | None = None
    acknowledged_by: str | None = None


# -- reference model ------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Point:
    x: float
    y: float


@dataclass(frozen=True, slots=True)
class Zone:
    zone_id: str
    building_id: str
    name: str
    floor: int = 0
    zone_kind: str = "AREA"
    polygon: tuple[Point, ...] = ()
    centroid_x: float | None = None
    centroid_y: float | None = None
    enclosing_radius_m: float | None = None

    @property
    def centroid(self) -> Point | None:
        if self.centroid_x is not None and self.centroid_y is not None:
            return Point(self.centroid_x, self.centroid_y)
        if not self.polygon:
            return None
        return Point(
            sum(p.x for p in self.polygon) / len(self.polygon),
            sum(p.y for p in self.polygon) / len(self.polygon),
        )

    @property
    def radius_m(self) -> float | None:
        """The zone's own geometry, reported as the uncertainty of a zone-centroid estimate."""
        if self.enclosing_radius_m is not None:
            return self.enclosing_radius_m
        centre = self.centroid
        if centre is None or not self.polygon:
            return None
        return max(math.hypot(p.x - centre.x, p.y - centre.y) for p in self.polygon)


@dataclass(frozen=True, slots=True)
class ZoneEdge:
    from_zone_id: str
    to_zone_id: str
    edge_type: str
    typical_traversal_s: float | None = None
    bidirectional: bool = True


@dataclass(frozen=True, slots=True)
class InfrastructureNode:
    node_id: str
    friendly_name: str
    type: str
    building_id: str | None = None
    zone_id: str | None = None
    x: float | None = None
    y: float | None = None
    known_bssid: str | None = None
    known_ble_identifier: str | None = None
    rtt_capable: bool = False

    @property
    def is_located_anchor(self) -> bool:
        return self.x is not None and self.y is not None


@dataclass(frozen=True, slots=True)
class ObserverCalibration:
    observer_id: str
    rssi_offset_db: float
    measured_at_utc: str | None = None
    sample_count: int | None = None
    spread_db: float | None = None
    method: str | None = None

    def is_applicable(self, max_spread_db: float) -> bool:
        """A wide per-source spread is antenna-pattern difference, not a scalar offset.

        Applying it would make results worse, so the offset is stored but declined.
        """
        return self.spread_db is None or self.spread_db <= max_spread_db


@dataclass(frozen=True, slots=True)
class Observer:
    observer_id: str
    friendly_name: str
    platform: str
    capabilities: frozenset[str] = frozenset()
    unsupported: frozenset[str] = frozenset()
    building_id: str | None = None
    default_zone_id: str | None = None
    x_coordinate: float | None = None
    y_coordinate: float | None = None
    fixed_observer: bool = False

    def can_see(self, sensor: SensorType) -> bool:
        """Absence of evidence is not evidence of absence.

        An observer that cannot scan Wi-Fi reporting no Wi-Fi means *this observer cannot see
        Wi-Fi*, not *no access points were present*, and the visibility statistics must not count
        it as a negative observation.
        """
        name = "WIFI_SCAN" if sensor is SensorType.WIFI_ASSOCIATION else sensor.value
        if name in self.unsupported:
            return False
        return not self.capabilities or name in self.capabilities


@dataclass(frozen=True, slots=True)
class SurveyPoint:
    survey_point_id: str
    building_id: str
    zone_id: str
    x: float
    y: float
    floor: int | None = None
    label: str | None = None
    physical_description: str | None = None


@dataclass(frozen=True, slots=True)
class ManagedDevice:
    device_id: str
    friendly_name: str
    device_type: str
    status: str = "AUTHORIZED"
    identifiers: tuple[str, ...] = ()


@dataclass
class ReferenceModel:
    """The administrator-curated site: geometry, topology, anchors, observers, ground truth."""

    reference_model_id: str
    zones: dict[str, Zone] = field(default_factory=dict)
    buildings: dict[str, str] = field(default_factory=dict)
    edges: tuple[ZoneEdge, ...] = ()
    infrastructure: dict[str, InfrastructureNode] = field(default_factory=dict)
    observers: dict[str, Observer] = field(default_factory=dict)
    calibration: dict[str, ObserverCalibration] = field(default_factory=dict)
    survey_points: dict[str, SurveyPoint] = field(default_factory=dict)
    fingerprints: tuple[FingerprintPoint, ...] = ()
    devices: dict[str, ManagedDevice] = field(default_factory=dict)
    calibration_set_id: str | None = None

    def source_for_observer(self, observer_id: str) -> str | None:
        """The radio identifier by which an observer is itself audible, if one is registered.

        This is the hinge of the whole comparison, and it is worth stating plainly. A fingerprint
        records what a surveyor heard *at* a location. A target device never reports anything; what
        exists is observers hearing the device. The two are comparable by reciprocity — the path
        loss between an observer and a point is the same in both directions — but only once the
        observer is identified as a *source*.

        Registering a collector phone as an ``OBSERVER_PHONE`` infrastructure node with its
        advertised identifier is what supplies that link (``docs/18-site-model-specification.md``
        §2). Without it, an observer's measurements of a device cannot be matched against any
        fingerprint, and the estimate honestly falls back to a coarser tier rather than being
        matched against an unrelated signal space.
        """
        node = self.infrastructure.get(observer_id)
        if node is not None:
            return node.known_ble_identifier or node.known_bssid
        for candidate in self.infrastructure.values():
            if candidate.type != "OBSERVER_PHONE":
                continue
            if candidate.friendly_name == observer_id or candidate.node_id == observer_id:
                return candidate.known_ble_identifier or candidate.known_bssid
        return None

    def device_for_observer(self, observer_id: str) -> str | None:
        """The managed device an observer *is*, when the administrator has enrolled it as one.

        A company phone running the Collector is both an observer and a device somebody carries.
        When the registry says so, that phone's own scans become evidence about its own position —
        the classic fingerprinting case, and the only route by which RTT can position anything,
        since a range measures the distance from the *measuring* radio to the anchor.

        The link is explicit REFERENCE data, never inferred: the same identifier appears on the
        ``ManagedDevice`` and on the observer's infrastructure node.
        """
        source = self.source_for_observer(observer_id)
        if source:
            matched = self.device_for_identifier(source)
            if matched:
                return matched
        return self.device_for_identifier(observer_id)

    def anchors_by_bssid(self) -> dict[str, InfrastructureNode]:
        return {
            node.known_bssid: node
            for node in self.infrastructure.values()
            if node.known_bssid and node.is_located_anchor
        }

    def device_for_identifier(self, identifier: str) -> str | None:
        for device in self.devices.values():
            if identifier in device.identifiers:
                return device.device_id
        return None

    def adjacency(self, a: str, b: str) -> TopologyStatus:
        """Whether two zones connect.

        A missing edge reports ``UNKNOWN_EDGE`` rather than ``NON_ADJACENT``: an unauthored door is
        far more likely than a teleporting device, and the difference matters because only an
        explicitly authored ``IMPOSSIBLE`` edge is a real contradiction.
        """
        if a == b:
            return TopologyStatus.ADJACENT
        for edge in self.edges:
            forward = edge.from_zone_id == a and edge.to_zone_id == b
            backward = edge.bidirectional and edge.from_zone_id == b and edge.to_zone_id == a
            if forward or backward:
                if edge.edge_type == "IMPOSSIBLE":
                    return TopologyStatus.NON_ADJACENT
                if edge.edge_type == "RESTRICTED":
                    return TopologyStatus.RESTRICTED
                return TopologyStatus.ADJACENT
        return TopologyStatus.UNKNOWN_EDGE

    def ground_truth_fingerprints(self) -> tuple[FingerprintPoint, ...]:
        return tuple(f for f in self.fingerprints if f.status == "GROUND_TRUTH")


#: Frame id for measurements in which some *other* observer heard the device. Reciprocity places
#: every such measurement in one signal space — the device's own — so together they form a single
#: joint opinion, not one thin opinion per observer.
RECIPROCAL_FRAME = "reciprocal"


def self_frame(observer_id: str) -> str:
    """Frame id for measurements the device took with its own radio."""
    return f"self:{observer_id}"


@dataclass(frozen=True, slots=True)
class Measurement:
    """One observer's view of one source inside a fusion window."""

    observer_id: str
    radio_identifier: str
    identifier_type: IdentifierType
    sensor_type: SensorType
    rssi_raw: int | None
    rssi_normalized: float | None
    age_ms: int
    freshness: str
    observation_id: str
    rtt_distance_mm: int | None = None
    rtt_stddev_mm: int | None = None
    #: Which frame of reference this measurement describes the device from. Measurements in one
    #: frame share a signal space and are classified together; separate frames form separate
    #: opinions that fusion then combines.
    frame: str = RECIPROCAL_FRAME


@dataclass(frozen=True, slots=True)
class LiveVector:
    """Everything known about one device within one fusion window."""

    device_id: str
    timestamp_ms: int
    timestamp_utc: str
    measurements: tuple[Measurement, ...]

    @property
    def observer_ids(self) -> tuple[str, ...]:
        return tuple(sorted({m.observer_id for m in self.measurements}))

    @property
    def frames(self) -> tuple[str, ...]:
        return tuple(sorted({m.frame for m in self.measurements}))

    @property
    def observation_ids(self) -> tuple[str, ...]:
        return tuple(sorted({m.observation_id for m in self.measurements}))

    @property
    def has_rtt(self) -> bool:
        return any(m.sensor_type is SensorType.RTT for m in self.measurements)

    def strongest_by_source(self) -> dict[str, Measurement]:
        """One value per source, taking the strongest reading when several observers saw it."""
        best: dict[str, Measurement] = {}
        for m in self.measurements:
            if m.rssi_normalized is None:
                continue
            existing = best.get(m.radio_identifier)
            if existing is None or m.rssi_normalized > (existing.rssi_normalized or -999):
                best[m.radio_identifier] = m
        return best


@dataclass(frozen=True, slots=True)
class Placement:
    """What a positioning engine produced, before confidence and uncertainty are attached.

    Separating this from :class:`PositionEstimate` keeps the engines honest about their own scope.
    An engine reports geometry — where, how tightly its own inputs agreed, and which evidence it
    used. It does not get to assert a final uncertainty, because the empirical half of that number
    comes from the benchmark rather than from the engine's own optimism (§8).
    """

    method: str
    precision_tier: PrecisionTier
    x: float | None = None
    y: float | None = None
    sigma_geometric_m: float | None = None
    supporting_fingerprint_ids: tuple[str, ...] = ()
    supporting_observation_ids: tuple[str, ...] = ()
    residual_m: float | None = None
    quality_flags: tuple[str, ...] = ()
    detail: dict[str, float] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if (self.x is None) != (self.y is None):
            raise ValueError("x and y must both be present or both absent")
        if self.x is not None and not self.precision_tier.has_coordinates:
            raise ValueError(f"tier {self.precision_tier} must not carry coordinates")
        if self.precision_tier.has_coordinates and self.x is None:
            raise ValueError(f"tier {self.precision_tier} requires coordinates")

    @property
    def has_coordinates(self) -> bool:
        return self.x is not None


@dataclass(frozen=True, slots=True)
class ZoneCandidate:
    zone_id: str
    building_id: str
    score: float
    distance: float | None = None
    fingerprint_ids: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ZoneResult:
    """A ranked list, not a winner.

    The *margin* between the best and second-best candidate is what indicates discriminability, so
    the runners-up have to survive to the confidence calculation rather than being discarded at the
    point of decision.
    """

    candidates: tuple[ZoneCandidate, ...]
    method: str

    @property
    def best(self) -> ZoneCandidate | None:
        return self.candidates[0] if self.candidates else None

    @property
    def margin(self) -> float:
        if len(self.candidates) < 2:
            return 1.0 if self.candidates else 0.0
        best, second = self.candidates[0].score, self.candidates[1].score
        total = abs(best) + abs(second)
        if total <= 0:
            return 0.0
        return max(0.0, min(1.0, (best - second) / total))

    def top(self, n: int) -> tuple[str, ...]:
        return tuple(c.zone_id for c in self.candidates[:n])


def mean(values: Iterable[float]) -> float:
    items = list(values)
    return sum(items) / len(items) if items else 0.0
