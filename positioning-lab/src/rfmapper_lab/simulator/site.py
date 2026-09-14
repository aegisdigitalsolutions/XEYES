"""A synthetic multi-building site and the observations it would produce.

The generated site follows the specification's own example shape: a chain of buildings
``B4 <-> B5 <-> ... <-> B9``, each with a handful of named zones, fixed observer phones, Wi-Fi
access points, a long-range router registered as a ``ZONE_ANCHOR``, and one building equipped with
RTT anchors so the ranged path has something to exercise.

Signals come from a log-distance model with wall attenuation, per-observer bias, scan-cadence
effects, cached results and clock skew. That model is *exactly* the one the specification says not
to trust indoors, which is why nothing produced here may support an accuracy claim about a real
site. It is here so the pipeline, the algorithms and the degenerate cases can be exercised before
any real survey exists.

Everything is driven by a seeded ``numpy`` generator, so a given spec always produces the same site
and the same observations.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Iterable

import numpy as np

from ..jsonio import derive_id
from ..models import Observation, SensorType, IdentifierType
from ..timeutil import format_ms, parse_ms
from ..version import SCHEMA_VERSION

#: Log-distance model: rssi = P0 - 10 n log10(d), plus wall loss and noise.
REFERENCE_RSSI_1M = -42.0
PATH_LOSS_EXPONENT = 2.9
WALL_LOSS_DB = 7.5
NOISE_DB = 2.2
RSSI_FLOOR = -96
RSSI_CEILING = -28


@dataclass(frozen=True, slots=True)
class SimulationSpec:
    seed: int = 20260914
    buildings: int = 3
    zones_per_building: int = 3
    building_spacing_m: float = 40.0
    zone_size_m: float = 12.0

    survey_sessions: int = 3
    survey_samples_per_session: int = 40
    #: Two points per zone, because one gives the weighted-centroid method nothing to interpolate
    #: between and it would silently never run.
    survey_points_per_zone: int = 2

    #: Walk-test devices and their route length in fusion windows.
    devices: int = 2
    walk_steps: int = 90
    step_ms: int = 10_000
    start_utc: str = "2026-09-14T08:00:00.000Z"

    #: Degenerate cases worth having in every regression run.
    include_rtt_building: bool = True
    include_collinear_anchors: bool = True
    include_uncalibrated_observer: bool = True
    leave_one_zone_uncalibrated: bool = True
    environmental_devices: int = 6


@dataclass
class SimulatedSite:
    spec: SimulationSpec
    reference_document: dict[str, Any]
    survey_observations: tuple[Observation, ...]
    live_observations: tuple[Observation, ...]
    truth: tuple[dict[str, Any], ...] = ()
    survey_truth: dict[str, str] = field(default_factory=dict)

    @property
    def observations(self) -> tuple[Observation, ...]:
        return tuple(
            sorted(
                [*self.survey_observations, *self.live_observations],
                key=lambda o: (o.timestamp_ms, o.observation_id),
            )
        )

    def observer_ids(self) -> tuple[str, ...]:
        return tuple(sorted({o.observer_id for o in self.observations}))


def simulate(spec: SimulationSpec = SimulationSpec()) -> SimulatedSite:
    rng = np.random.default_rng(spec.seed)
    layout = _layout(spec)
    document = _reference_document(spec, layout)

    survey = _survey_observations(spec, layout, rng)
    live, truth = _live_observations(spec, layout, rng)

    return SimulatedSite(
        spec=spec,
        reference_document=document,
        survey_observations=survey,
        live_observations=live,
        truth=truth,
        survey_truth={
            point["survey_point_id"]: point["zone_id"] for point in layout["survey_points"]
        },
    )


# -- geometry -------------------------------------------------------------------------------------


def _layout(spec: SimulationSpec) -> dict[str, Any]:
    """Buildings in a row, zones in a row inside each, one observer and one AP per zone."""
    buildings: list[dict[str, Any]] = []
    zones: list[dict[str, Any]] = []
    edges: list[dict[str, Any]] = []
    nodes: list[dict[str, Any]] = []
    observers: list[dict[str, Any]] = []
    survey_points: list[dict[str, Any]] = []
    devices: list[dict[str, Any]] = []

    size = spec.zone_size_m
    #: Zone ids in layout order, per building. Every later reference resolves through this rather
    #: than reconstructing a name, because a guessed name that happens not to exist at a smaller
    #: site shape is a broken site model rather than a missing zone.
    zones_by_building: list[list[str]] = []

    for index in range(spec.buildings):
        building_id = f"B{4 + index}"
        origin_x = index * spec.building_spacing_m
        width = spec.zones_per_building * size
        building_zones: list[str] = []
        zones_by_building.append(building_zones)

        buildings.append(
            {
                "building_id": building_id,
                "name": f"Building {4 + index}",
                "floors": [0],
                "outline_polygon": _rectangle(origin_x, 0.0, width, size),
            }
        )

        for zone_index in range(spec.zones_per_building):
            suffix = _zone_suffix(zone_index)
            zone_id = f"{building_id}-{suffix}"
            building_zones.append(zone_id)
            x0 = origin_x + zone_index * size
            polygon = _rectangle(x0, 0.0, size, size)
            centre_x, centre_y = x0 + size / 2, size / 2

            zones.append(
                {
                    "zone_id": zone_id,
                    "building_id": building_id,
                    "name": f"{building_id} {suffix.title()}",
                    "floor": 0,
                    "zone_kind": "ROOM" if suffix != "CENTER" else "AREA",
                    "polygon": polygon,
                    "centroid_x": centre_x,
                    "centroid_y": centre_y,
                    "enclosing_radius_m": round(math.hypot(size / 2, size / 2), 3),
                }
            )

            observer_id = f"OBS-{index}{zone_index}"
            identifier = _ble_identifier(index, zone_index)
            observers.append(
                {
                    "observer_id": observer_id,
                    "friendly_name": f"{zone_id} collector",
                    "platform": "ANDROID",
                    "observer_device_type": "ANDROID_PHONE",
                    "capabilities": ["WIFI_SCAN", "BLE", "RTT"] if index == 0 else ["WIFI_SCAN", "BLE"],
                    "building_id": building_id,
                    "default_zone_id": zone_id,
                    "x_coordinate": centre_x,
                    "y_coordinate": centre_y,
                    "fixed_observer": True,
                    "app_version": "1.0.0",
                }
            )
            # The observer is registered as infrastructure as well, which is what makes it audible
            # as a source and therefore comparable against a fingerprint.
            nodes.append(
                {
                    "node_id": observer_id,
                    "friendly_name": f"{zone_id} collector beacon",
                    "type": "OBSERVER_PHONE",
                    "building_id": building_id,
                    "zone_id": zone_id,
                    "x": centre_x,
                    "y": centre_y,
                    "known_ble_identifier": identifier,
                }
            )
            nodes.append(
                {
                    "node_id": f"AP-{index}{zone_index}",
                    "friendly_name": f"{zone_id} access point",
                    "type": "WIFI_AP",
                    "building_id": building_id,
                    "zone_id": zone_id,
                    "x": centre_x,
                    "y": size - 1.0,
                    "known_bssid": _bssid(index, zone_index),
                }
            )

            for point_index, (label, offset) in enumerate(
                _survey_offsets(spec.survey_points_per_zone, size)
            ):
                survey_points.append(
                    {
                        "survey_point_id": f"{zone_id}_{label}",
                        "building_id": building_id,
                        "zone_id": zone_id,
                        "x": round(centre_x + offset[0], 3),
                        "y": round(centre_y + offset[1], 3),
                        "label": f"{zone_id} {label.lower()}",
                        "physical_description": (
                            f"{label.title()} of the zone, 1.2 m height, facing north"
                        ),
                    }
                )

            if zone_index > 0:
                edges.append(
                    {
                        "from_zone_id": building_zones[zone_index - 1],
                        "to_zone_id": zone_id,
                        "edge_type": "DOOR",
                        "typical_traversal_s": 8.0,
                        "bidirectional": True,
                    }
                )

        if index > 0 and zones_by_building[index - 1]:
            edges.append(
                {
                    "from_zone_id": zones_by_building[index - 1][-1],
                    "to_zone_id": building_zones[0],
                    "edge_type": "OUTDOOR_PATH",
                    "typical_traversal_s": 45.0,
                    "bidirectional": True,
                }
            )

    first_building = buildings[0]["building_id"] if buildings else None
    first_zone = zones_by_building[0][len(zones_by_building[0]) // 2] if zones_by_building[0] else None

    # A long-range router: wide coverage, position known only approximately. Registered as a
    # ZONE_ANCHOR and never as a ranging instrument, because a coverage radius is a planning figure.
    if first_zone:
        nodes.append(
            {
                "node_id": "ROUTER-SITE",
                "friendly_name": "Site-wide long-range router",
                "type": "ZONE_ANCHOR",
                "building_id": first_building,
                "zone_id": first_zone,
                "x": spec.building_spacing_m * max(0, spec.buildings - 1) / 2,
                "y": size / 2,
                "known_bssid": "aa:bb:cc:00:00:99",
                "rtt_capable": False,
            }
        )

    if spec.include_rtt_building and first_zone:
        # Three anchors in a triangle around the first building, verified by real ranging in this
        # fiction. The spread is kept inside the building's own footprint so the ranges the walk
        # test produces are the ones a real deployment would see.
        span = spec.zones_per_building * size
        triangle = ((2.0, 2.0), (span - 2.0, 3.0), (span / 2.0, size - 2.0))
        for anchor_index, (dx, dy) in enumerate(triangle):
            nodes.append(
                {
                    "node_id": f"RTT-{anchor_index}",
                    "friendly_name": f"{first_building} RTT anchor {anchor_index}",
                    "type": "RTT_ANCHOR",
                    "building_id": first_building,
                    "zone_id": first_zone,
                    "x": dx,
                    "y": dy,
                    "known_bssid": _bssid(9, anchor_index),
                    "rtt_capable": True,
                }
            )

    if spec.include_collinear_anchors and len(zones_by_building) > 1 and zones_by_building[1]:
        # Deliberately degenerate: three anchors in a line, so the geometry gate has something to
        # decline. Without a case like this in every regression run, the gate is untested code.
        second_building = buildings[1]["building_id"]
        second_zone = zones_by_building[1][0]
        for anchor_index in range(3):
            nodes.append(
                {
                    "node_id": f"RTT-LINE-{anchor_index}",
                    "friendly_name": f"{second_building} collinear anchor {anchor_index}",
                    "type": "RTT_ANCHOR",
                    "building_id": second_building,
                    "zone_id": second_zone,
                    "x": spec.building_spacing_m + 2.0 + anchor_index * 4.0,
                    "y": 1.0,
                    "known_bssid": _bssid(8, anchor_index),
                    "rtt_capable": True,
                }
            )

    for device_index in range(spec.devices):
        identifier = f"d1:e2:f3:{device_index:02x}:15:26"
        devices.append(
            {
                "device_id": f"DEVICE-{device_index:02d}",
                "friendly_name": f"Site tag {device_index:02d}",
                "device_type": "BLE_TAG",
                "status": "AUTHORIZED",
                "known_ble_identifiers": [identifier],
                "known_service_uuids": ["0000180f-0000-1000-8000-00805f9b34fb"],
            }
        )

    # One walking phone that is both an observer and an enrolled device: the case where a device's
    # own scans position it, and the only route by which RTT positions anything.
    devices.append(
        {
            "device_id": "DEVICE-PHONE",
            "friendly_name": "Field phone",
            "device_type": "ANDROID_PHONE",
            "status": "AUTHORIZED",
            "known_ble_identifiers": ["d2:aa:bb:cc:dd:ee"],
        }
    )
    observers.append(
        {
            "observer_id": "OBS-WALK",
            "friendly_name": "Walking collector",
            "platform": "ANDROID",
            "observer_device_type": "ANDROID_PHONE",
            "capabilities": ["WIFI_SCAN", "BLE", "RTT", "GPS"],
            "building_id": None,
            "default_zone_id": None,
            "fixed_observer": False,
            "app_version": "1.0.0",
        }
    )
    nodes.append(
        {
            "node_id": "OBS-WALK",
            "friendly_name": "Walking collector beacon",
            "type": "OBSERVER_PHONE",
            "known_ble_identifier": "d2:aa:bb:cc:dd:ee",
        }
    )

    return {
        "buildings": buildings,
        "zones": zones,
        "edges": edges,
        "nodes": nodes,
        "observers": observers,
        "survey_points": survey_points,
        "devices": devices,
        "size": size,
    }


def _reference_document(spec: SimulationSpec, layout: dict[str, Any]) -> dict[str, Any]:
    known = {observer["observer_id"] for observer in layout["observers"]}
    calibration = []
    if "OBS-00" in known:
        calibration.append(
            {
                "observer_id": "OBS-00",
                "rssi_offset_db": 0.0,
                "measured_at_utc": spec.start_utc,
                "sample_count": 600,
                "spread_db": 1.4,
                "method": "CO_LOCATION_10MIN",
            }
        )
    if spec.include_uncalibrated_observer and "OBS-01" in known:
        # An offset with a wide per-source spread: stored, declined, and reported. Applying it
        # would make results worse, because it is antenna-pattern difference rather than gain.
        calibration.append(
            {
                "observer_id": "OBS-01",
                "rssi_offset_db": 3.0,
                "measured_at_utc": spec.start_utc,
                "sample_count": 120,
                "spread_db": 11.0,
                "method": "CO_LOCATION_10MIN",
            }
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "reference_model_id": f"synthetic-site-{spec.seed}",
        "created_at": spec.start_utc,
        "notes": "Synthetic site. No accuracy claim about any real environment may cite this model.",
        "frame": {
            "origin_lat": 51.5,
            "origin_lon": -0.12,
            "rotation_deg": 0.0,
            "projection": "LOCAL_TANGENT_PLANE",
        },
        "buildings": layout["buildings"],
        "zones": layout["zones"],
        "zone_edges": layout["edges"],
        "infrastructure_nodes": layout["nodes"],
        "observers": layout["observers"],
        "survey_points": layout["survey_points"],
        "fingerprints": [],
        "observer_calibration": calibration,
        "calibration_set_id": f"cal-synthetic-{spec.seed}",
        "managed_devices": layout["devices"],
    }


# -- observation generation -----------------------------------------------------------------------


def _sources(layout: dict[str, Any]) -> list[dict[str, Any]]:
    """Every audible fixed source with its position and identifier."""
    sources = []
    for node in layout["nodes"]:
        identifier = node.get("known_bssid") or node.get("known_ble_identifier")
        if not identifier or node.get("x") is None:
            continue
        sources.append(
            {
                "identifier": identifier,
                "x": float(node["x"]),
                "y": float(node["y"]),
                "building_id": node.get("building_id"),
                "is_ble": bool(node.get("known_ble_identifier")),
                "rtt": bool(node.get("rtt_capable")),
                "node_id": node["node_id"],
            }
        )
    return sources


def _building_index(building_id: str | None) -> int:
    if not building_id or not building_id.startswith("B"):
        return 0
    try:
        return int(building_id[1:]) - 4
    except ValueError:
        return 0


def _rssi(
    rng: np.random.Generator,
    distance_m: float,
    walls: int,
    bias_db: float,
) -> int:
    value = (
        REFERENCE_RSSI_1M
        - 10.0 * PATH_LOSS_EXPONENT * math.log10(max(distance_m, 1.0))
        - walls * WALL_LOSS_DB
        + bias_db
        + float(rng.normal(0.0, NOISE_DB))
    )
    return int(round(max(RSSI_FLOOR, min(RSSI_CEILING, value))))


def _observer_bias(observer_id: str, spec: SimulationSpec) -> float:
    """Per-device chipset bias. OBS-01 is deliberately badly behaved."""
    if observer_id == "OBS-01" and spec.include_uncalibrated_observer:
        return -6.5
    return {"OBS-WALK": 1.5}.get(observer_id, 0.0)


def _survey_observations(
    spec: SimulationSpec,
    layout: dict[str, Any],
    rng: np.random.Generator,
) -> tuple[Observation, ...]:
    """Survey Mode captures: the only source of ground truth.

    One zone is deliberately left uncalibrated when ``leave_one_zone_uncalibrated`` is set, so the
    ``SPARSE_CALIBRATION`` path and the zone-centroid fallback are exercised on every run rather
    than only when a real site happens to be incomplete.
    """
    sources = _sources(layout)
    points = list(layout["survey_points"])
    if spec.leave_one_zone_uncalibrated:
        # Every point in the last zone, not just its last point: leaving one of two behind would
        # still calibrate the zone, and the path being exercised is the zone that has nothing.
        skipped = points[-1]["zone_id"]
        remaining = [point for point in points if point["zone_id"] != skipped]
        if remaining:
            points = remaining

    start = parse_ms(spec.start_utc)
    rows: list[Observation] = []

    for session in range(spec.survey_sessions):
        session_id = derive_id("survey_session", spec.seed, session)
        # Sessions on different days, as the procedure requires: one afternoon's RF conditions are
        # not a fingerprint.
        session_start = start + session * 86_400_000
        for point_index, point in enumerate(points):
            observer_id = ["OBS-00", "OBS-01", "OBS-WALK"][session % 3]
            bias = _observer_bias(observer_id, spec)
            for sample in range(spec.survey_samples_per_session):
                timestamp = session_start + point_index * 120_000 + sample * 1_500
                for source in sources:
                    distance = math.hypot(source["x"] - point["x"], source["y"] - point["y"])
                    walls = abs(
                        _building_index(source["building_id"]) - _building_index(point["building_id"])
                    )
                    # Body shadowing, averaged out by the 360-degree rotation the procedure asks
                    # for: modelled as extra variance rather than a fixed loss.
                    rssi = _rssi(rng, distance, walls, bias + float(rng.normal(0.0, 1.5)))
                    if rssi <= RSSI_FLOOR:
                        continue
                    rows.append(
                        _observation(
                            timestamp=timestamp,
                            observer_id=observer_id,
                            identifier=source["identifier"],
                            is_ble=source["is_ble"],
                            rssi=rssi,
                            building_id=point["building_id"],
                            zone_id=point["zone_id"],
                            x=point["x"],
                            y=point["y"],
                            metadata={
                                "sample_kind": "GROUND_TRUTH",
                                "survey_point_id": point["survey_point_id"],
                                "survey_session_id": session_id,
                                "survey_operator": "operator-1",
                                "survey_conditions": "OCCUPANCY_LOW",
                                "result_freshness": "FRESH",
                                "session_id": session_id,
                            },
                        )
                    )
    return tuple(rows)


def _live_observations(
    spec: SimulationSpec,
    layout: dict[str, Any],
    rng: np.random.Generator,
) -> tuple[tuple[Observation, ...], tuple[dict[str, Any], ...]]:
    """A walk test plus fixed observers hearing the tags, and some environmental noise."""
    sources = _sources(layout)
    zones = list(layout["zones"])
    observers = {o["observer_id"]: o for o in layout["observers"]}
    start = parse_ms(spec.start_utc) + 3 * 86_400_000

    rows: list[Observation] = []
    truth: list[dict[str, Any]] = []

    device_ids = [f"DEVICE-{index:02d}" for index in range(spec.devices)]
    tag_identifiers = {
        f"DEVICE-{index:02d}": f"d1:e2:f3:{index:02x}:15:26" for index in range(spec.devices)
    }

    for device_index, device_id in enumerate(device_ids):
        # Each tag dwells in a zone then moves to the next, so transitions exist to be detected.
        for step in range(spec.walk_steps):
            timestamp = start + step * spec.step_ms
            zone = zones[(device_index + step // 12) % len(zones)]
            position = (
                float(zone["centroid_x"]) + float(rng.normal(0.0, 1.2)),
                float(zone["centroid_y"]) + float(rng.normal(0.0, 1.2)),
            )
            truth.append(
                {
                    "device_id": device_id,
                    "timestamp_utc": format_ms(timestamp),
                    "zone_id": zone["zone_id"],
                    "building_id": zone["building_id"],
                    "x": round(position[0], 3),
                    "y": round(position[1], 3),
                }
            )

            for observer_id, observer in observers.items():
                if not observer.get("fixed_observer"):
                    continue
                distance = math.hypot(
                    float(observer["x_coordinate"]) - position[0],
                    float(observer["y_coordinate"]) - position[1],
                )
                walls = abs(
                    _building_index(observer["building_id"]) - _building_index(zone["building_id"])
                )
                rssi = _rssi(rng, distance, walls, _observer_bias(observer_id, spec))
                if rssi <= RSSI_FLOOR + 2:
                    continue
                cached = bool(rng.random() < 0.12)
                rows.append(
                    _observation(
                        timestamp=timestamp - (1_800_000 if cached else 0),
                        observer_id=observer_id,
                        identifier=tag_identifiers[device_id],
                        is_ble=True,
                        rssi=rssi,
                        building_id=observer["building_id"],
                        zone_id=observer["default_zone_id"],
                        x=observer["x_coordinate"],
                        y=observer["y_coordinate"],
                        target_device_id=device_id,
                        metadata={
                            "result_freshness": "CACHED" if cached else "FRESH",
                            "session_id": f"live-{observer_id}",
                            "ble_service_uuids": "0000180f-0000-1000-8000-00805f9b34fb",
                        },
                    )
                )

    # The walking phone: its own scans of infrastructure, plus ranges where anchors allow.
    for step in range(spec.walk_steps):
        timestamp = start + step * spec.step_ms
        zone = zones[(step // 15) % len(zones)]
        position = (float(zone["centroid_x"]), float(zone["centroid_y"]))
        truth.append(
            {
                "device_id": "DEVICE-PHONE",
                "timestamp_utc": format_ms(timestamp),
                "zone_id": zone["zone_id"],
                "building_id": zone["building_id"],
                "x": round(position[0], 3),
                "y": round(position[1], 3),
            }
        )
        # A mid-run wall-clock adjustment on the walking phone, so the CLOCK_JUMP detector has
        # something real to find.
        skew = 4_500 if step > spec.walk_steps // 2 else 0

        for source in sources:
            distance = math.hypot(source["x"] - position[0], source["y"] - position[1])
            walls = abs(
                _building_index(source["building_id"]) - _building_index(zone["building_id"])
            )
            rssi = _rssi(rng, distance, walls, _observer_bias("OBS-WALK", spec))
            if rssi <= RSSI_FLOOR + 2:
                continue
            rows.append(
                _observation(
                    timestamp=timestamp + skew,
                    observer_id="OBS-WALK",
                    identifier=source["identifier"],
                    is_ble=source["is_ble"],
                    rssi=rssi,
                    building_id=zone["building_id"],
                    zone_id=None,
                    x=None,
                    y=None,
                    metadata={
                        "result_freshness": "FRESH",
                        "session_id": "live-OBS-WALK",
                        "clock_elapsed_realtime_ms": str(step * spec.step_ms),
                        "clock_boot_utc": spec.start_utc,
                    },
                )
            )
            if source["rtt"] and distance < 45.0:
                error = float(rng.normal(0.0, 0.9))
                rows.append(
                    _observation(
                        timestamp=timestamp + skew,
                        observer_id="OBS-WALK",
                        identifier=source["identifier"],
                        is_ble=False,
                        rssi=rssi,
                        building_id=zone["building_id"],
                        zone_id=None,
                        x=None,
                        y=None,
                        sensor=SensorType.RTT,
                        rtt_distance_mm=int(max(0.3, distance + error) * 1000),
                        rtt_stddev_mm=int(abs(rng.normal(900, 200))),
                        metadata={
                            "result_freshness": "FRESH",
                            "session_id": "live-OBS-WALK",
                            "rtt_num_successful": "7",
                            "clock_elapsed_realtime_ms": str(step * spec.step_ms),
                        },
                    )
                )

    rows.extend(_environmental(spec, layout, rng, start))
    return tuple(rows), tuple(truth)


def _environmental(
    spec: SimulationSpec,
    layout: dict[str, Any],
    rng: np.random.Generator,
    start: int,
) -> Iterable[Observation]:
    """Unenrolled devices with rotating random addresses.

    They exist to prove the system leaves them alone: a randomized address is never attributed, and
    nothing links two of them together however suggestive the timing.
    """
    fixed = [o for o in layout["observers"] if o.get("fixed_observer")]
    if not fixed:
        return ()

    rows: list[Observation] = []
    for index in range(spec.environmental_devices):
        observer = fixed[index % len(fixed)]
        for rotation in range(3):
            # Locally administered bit set, which is how both platforms present a rotating address.
            address = f"{0x02 | (index << 2) & 0xFE:02x}:{rotation:02x}:aa:bb:cc:dd"
            for step in range(6):
                timestamp = start + rotation * 900_000 + step * spec.step_ms
                rows.append(
                    _observation(
                        timestamp=timestamp,
                        observer_id=observer["observer_id"],
                        identifier=address,
                        is_ble=True,
                        rssi=int(rng.integers(-95, -60)),
                        building_id=observer["building_id"],
                        zone_id=observer["default_zone_id"],
                        x=None,
                        y=None,
                        identifier_type=IdentifierType.BLE_MAC_RANDOM,
                        metadata={"result_freshness": "FRESH", "session_id": "live-env"},
                    )
                )
    return rows


def _observation(
    timestamp: int,
    observer_id: str,
    identifier: str,
    is_ble: bool,
    rssi: int,
    building_id: str | None,
    zone_id: str | None,
    x: float | None,
    y: float | None,
    metadata: dict[str, str],
    target_device_id: str | None = None,
    sensor: SensorType | None = None,
    identifier_type: IdentifierType | None = None,
    rtt_distance_mm: int | None = None,
    rtt_stddev_mm: int | None = None,
) -> Observation:
    sensor_type = sensor or (SensorType.BLE if is_ble else SensorType.WIFI_SCAN)
    resolved_type = identifier_type or (
        IdentifierType.BLE_MAC_PUBLIC if is_ble else IdentifierType.WIFI_BSSID
    )
    timestamp_utc = format_ms(timestamp)
    return Observation(
        observation_id=derive_id(
            "sim_observation",
            observer_id,
            timestamp_utc,
            identifier,
            sensor_type.value,
            target_device_id or "",
        ),
        schema_version=SCHEMA_VERSION,
        timestamp_utc=timestamp_utc,
        timestamp_ms=timestamp,
        observer_id=observer_id,
        observer_device_type="ANDROID_PHONE",
        sensor_type=sensor_type,
        radio_identifier=identifier,
        identifier_type=resolved_type,
        rssi=rssi,
        bssid=None if is_ble else identifier,
        ble_service_uuid=metadata.get("ble_service_uuids") if is_ble else None,
        frequency=None if is_ble else 5180,
        channel=None if is_ble else 36,
        rtt_distance_mm=rtt_distance_mm,
        rtt_stddev_mm=rtt_stddev_mm,
        building_id=building_id,
        zone_id=zone_id,
        observer_x=x,
        observer_y=y,
        target_device_id=target_device_id,
        confidence=0.9,
        metadata=dict(sorted(metadata.items())),
    )


def _rectangle(x: float, y: float, width: float, height: float) -> list[dict[str, float]]:
    return [
        {"x": x, "y": y},
        {"x": x + width, "y": y},
        {"x": x + width, "y": y + height},
        {"x": x, "y": y + height},
    ]


def _survey_offsets(count: int, size: float) -> tuple[tuple[str, tuple[float, float]], ...]:
    """Where inside a zone the surveyor stood, relative to its centroid."""
    quarter = size / 4.0
    candidates = (
        ("CENTER", (0.0, 0.0)),
        ("NORTHEAST", (quarter, quarter)),
        ("SOUTHWEST", (-quarter, -quarter)),
        ("NORTHWEST", (-quarter, quarter)),
        ("SOUTHEAST", (quarter, -quarter)),
    )
    return candidates[: max(1, min(count, len(candidates)))]


def _zone_suffix(zone_index: int) -> str:
    """A readable zone name that stays unique past the fifth zone in a building."""
    names = ("WEST", "CENTER", "EAST", "NORTH", "SOUTH")
    name = names[zone_index % len(names)]
    lap = zone_index // len(names)
    return name if lap == 0 else f"{name}-{lap + 1}"


def _bssid(group: int, index: int) -> str:
    return f"aa:bb:cc:{group:02x}:{index:02x}:01"


def _ble_identifier(group: int, index: int) -> str:
    return f"c0:11:{group:02x}:{index:02x}:00:01"
