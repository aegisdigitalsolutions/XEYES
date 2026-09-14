"""Reads ``site_model.json`` — the whole REFERENCE layer as one document.

Per ``docs/18-site-model-specification.md`` the site model travels as a single versioned document
rather than as a pile of CSVs, because geometry, topology, anchors and ground truth only make sense
together: a zone edge referencing a zone that did not arrive is not a partially valid site, it is a
broken one.

The Lab's reader is strict about referential integrity for the same reason the Master is. Silently
dropping a dangling reference would turn a site-model authoring mistake into a quiet loss of
accuracy that nobody could trace.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from ..models import (
    FingerprintEntry,
    FingerprintPoint,
    IdentifierType,
    InfrastructureNode,
    ManagedDevice,
    Observer,
    ObserverCalibration,
    Point,
    ReferenceModel,
    SurveyPoint,
    Zone,
    ZoneEdge,
)
from ..version import SCHEMA_VERSION


class ReferenceModelError(ValueError):
    """The site model cannot be interpreted, so the run must not proceed.

    Inferring a site is not an option. Every coordinate the Lab emits is in the frame this document
    defines, and guessing at a missing zone or a dangling edge would produce output that looks
    authoritative and is not.
    """


def load_reference_model(path: Path, devices: Path | None = None) -> ReferenceModel:
    """Load the site model, optionally merging a separately exported device registry.

    The device registry is not part of the site model document: geometry changes when a wall moves,
    the registry changes when somebody is issued a phone, and versioning them together would force
    a new ``reference_model_id`` for every enrollment. The Lab needs both, so it accepts them
    either merged into one document or as two files.
    """
    model = parse_reference_model(json.loads(path.read_text(encoding="utf-8")))
    if devices is not None:
        model.devices.update(load_devices(devices))
    return model


def load_devices(path: Path) -> dict[str, ManagedDevice]:
    document = json.loads(path.read_text(encoding="utf-8"))
    items = document if isinstance(document, list) else document.get("managed_devices") or []
    parsed = (_device(item) for item in items if isinstance(item, dict))
    return {device.device_id: device for device in parsed}


def parse_reference_model(document: dict[str, Any]) -> ReferenceModel:
    if not isinstance(document, dict):
        raise ReferenceModelError("site model is not a JSON object")

    declared = str(document.get("schema_version", SCHEMA_VERSION))
    if not _readable(declared):
        raise ReferenceModelError(
            f"site model schema_version {declared!r} has a major this build cannot read"
        )

    model_id = document.get("reference_model_id")
    if not model_id:
        raise ReferenceModelError("reference_model_id is required: an unnamed site model cannot be cited")

    buildings = {
        str(item["building_id"]): str(item.get("name") or item["building_id"])
        for item in _array(document, "buildings")
        if item.get("building_id")
    }

    zones: dict[str, Zone] = {}
    for item in _array(document, "zones"):
        zone = _zone(item)
        zones[zone.zone_id] = zone

    edges = tuple(_edge(item) for item in _array(document, "zone_edges"))
    infrastructure = {node.node_id: node for node in (_node(i) for i in _array(document, "infrastructure_nodes"))}
    observers = {o.observer_id: o for o in (_observer(i) for i in _array(document, "observers"))}
    survey_points = {p.survey_point_id: p for p in (_survey_point(i) for i in _array(document, "survey_points"))}
    fingerprints = tuple(_fingerprint(item) for item in _array(document, "fingerprints"))
    calibration = {c.observer_id: c for c in (_calibration(i) for i in _array(document, "observer_calibration"))}
    devices = {d.device_id: d for d in (_device(i) for i in _array(document, "managed_devices"))}

    model = ReferenceModel(
        reference_model_id=str(model_id),
        zones=zones,
        buildings=buildings,
        edges=edges,
        infrastructure=infrastructure,
        observers=observers,
        calibration=calibration,
        survey_points=survey_points,
        fingerprints=fingerprints,
        devices=devices,
        calibration_set_id=_optional_str(document, "calibration_set_id"),
    )

    problems = referential_issues(model)
    if problems:
        raise ReferenceModelError(
            "site model is not internally consistent: " + "; ".join(problems[:10])
        )
    return model


def referential_issues(model: ReferenceModel) -> list[str]:
    """The same checks ``core-model/SiteModel.kt`` runs, repeated on the reading side.

    A contract enforced only by the writer is not enforced, and the Lab may be handed a document
    that never passed through the Master.
    """
    problems: list[str] = []

    for zone in model.zones.values():
        if zone.building_id not in model.buildings:
            problems.append(f"zone '{zone.zone_id}' references unknown building '{zone.building_id}'")

    for edge in model.edges:
        if edge.from_zone_id not in model.zones:
            problems.append(f"zone_edge references unknown zone '{edge.from_zone_id}'")
        if edge.to_zone_id not in model.zones:
            problems.append(f"zone_edge references unknown zone '{edge.to_zone_id}'")

    for point in model.survey_points.values():
        if point.building_id not in model.buildings:
            problems.append(
                f"survey point '{point.survey_point_id}' references unknown building '{point.building_id}'"
            )
        if point.zone_id not in model.zones:
            problems.append(
                f"survey point '{point.survey_point_id}' references unknown zone '{point.zone_id}'"
            )

    for fingerprint in model.fingerprints:
        if fingerprint.survey_point_id not in model.survey_points:
            problems.append(
                f"fingerprint '{fingerprint.fingerprint_id}' references unknown survey point "
                f"'{fingerprint.survey_point_id}'"
            )
        if fingerprint.zone_id not in model.zones:
            problems.append(
                f"fingerprint '{fingerprint.fingerprint_id}' references unknown zone '{fingerprint.zone_id}'"
            )

    for node in model.infrastructure.values():
        if node.zone_id is not None and node.zone_id not in model.zones:
            problems.append(f"infrastructure node '{node.node_id}' references unknown zone '{node.zone_id}'")
        # An RTT anchor without coordinates cannot anchor a range, and a multilateration that
        # silently skipped it would report a geometry it did not use.
        if node.type == "RTT_ANCHOR" and not node.is_located_anchor:
            problems.append(
                f"RTT anchor '{node.node_id}' has no site-frame coordinates and cannot anchor a range"
            )

    for observer in model.observers.values():
        if observer.default_zone_id is not None and observer.default_zone_id not in model.zones:
            problems.append(
                f"observer '{observer.observer_id}' references unknown zone '{observer.default_zone_id}'"
            )

    return problems


# -- element decoding -----------------------------------------------------------------------------


def _zone(item: dict[str, Any]) -> Zone:
    _require(item, "zone_id", "building_id")
    return Zone(
        zone_id=str(item["zone_id"]),
        building_id=str(item["building_id"]),
        name=str(item.get("name") or item["zone_id"]),
        floor=int(item.get("floor") or 0),
        zone_kind=str(item.get("zone_kind") or "AREA"),
        polygon=tuple(Point(float(p["x"]), float(p["y"])) for p in item.get("polygon") or []),
        centroid_x=_optional_float(item, "centroid_x"),
        centroid_y=_optional_float(item, "centroid_y"),
        enclosing_radius_m=_optional_float(item, "enclosing_radius_m"),
    )


def _edge(item: dict[str, Any]) -> ZoneEdge:
    _require(item, "from_zone_id", "to_zone_id")
    return ZoneEdge(
        from_zone_id=str(item["from_zone_id"]),
        to_zone_id=str(item["to_zone_id"]),
        edge_type=str(item.get("edge_type") or "ADJACENT"),
        typical_traversal_s=_optional_float(item, "typical_traversal_s"),
        bidirectional=bool(item.get("bidirectional", True)),
    )


def _node(item: dict[str, Any]) -> InfrastructureNode:
    _require(item, "node_id")
    return InfrastructureNode(
        node_id=str(item["node_id"]),
        friendly_name=str(item.get("friendly_name") or item["node_id"]),
        type=str(item.get("type") or "OTHER"),
        building_id=_optional_str(item, "building_id"),
        zone_id=_optional_str(item, "zone_id"),
        x=_optional_float(item, "x"),
        y=_optional_float(item, "y"),
        known_bssid=_optional_str(item, "known_bssid"),
        known_ble_identifier=_optional_str(item, "known_ble_identifier"),
        rtt_capable=bool(item.get("rtt_capable", False)),
    )


def _observer(item: dict[str, Any]) -> Observer:
    _require(item, "observer_id")
    return Observer(
        observer_id=str(item["observer_id"]),
        friendly_name=str(item.get("friendly_name") or item["observer_id"]),
        platform=str(item.get("platform") or "UNKNOWN"),
        capabilities=frozenset(str(c) for c in item.get("capabilities") or []),
        unsupported=frozenset(str(c) for c in item.get("unsupported") or []),
        building_id=_optional_str(item, "building_id"),
        default_zone_id=_optional_str(item, "default_zone_id"),
        x_coordinate=_optional_float(item, "x_coordinate"),
        y_coordinate=_optional_float(item, "y_coordinate"),
        fixed_observer=bool(item.get("fixed_observer", False)),
    )


def _survey_point(item: dict[str, Any]) -> SurveyPoint:
    _require(item, "survey_point_id", "building_id", "zone_id", "x", "y")
    return SurveyPoint(
        survey_point_id=str(item["survey_point_id"]),
        building_id=str(item["building_id"]),
        zone_id=str(item["zone_id"]),
        x=float(item["x"]),
        y=float(item["y"]),
        floor=_optional_int(item, "floor"),
        label=_optional_str(item, "label"),
        physical_description=_optional_str(item, "physical_description"),
    )


def _fingerprint(item: dict[str, Any]) -> FingerprintPoint:
    _require(item, "fingerprint_id", "survey_point_id", "building_id", "zone_id")
    return FingerprintPoint(
        fingerprint_id=str(item["fingerprint_id"]),
        survey_point_id=str(item["survey_point_id"]),
        building_id=str(item["building_id"]),
        zone_id=str(item["zone_id"]),
        x=_optional_float(item, "x"),
        y=_optional_float(item, "y"),
        observer_id=_optional_str(item, "observer_id"),
        status=str(item.get("status") or "CANDIDATE"),
        sample_count=int(item.get("sample_count") or 0),
        session_count=_optional_int(item, "session_count"),
        source_survey_session_ids=tuple(str(s) for s in item.get("source_survey_session_ids") or []),
        engine_version=_optional_str(item, "engine_version"),
        created_at_utc=_optional_str(item, "created_at_utc"),
        entries=tuple(_entry(e) for e in item.get("entries") or []),
    )


def _entry(item: dict[str, Any]) -> FingerprintEntry:
    _require(item, "radio_identifier", "rssi_median")
    raw_type = str(item.get("identifier_type") or IdentifierType.OTHER.value)
    try:
        identifier_type = IdentifierType(raw_type)
    except ValueError:
        identifier_type = IdentifierType.OTHER
    return FingerprintEntry(
        radio_identifier=str(item["radio_identifier"]),
        identifier_type=identifier_type,
        sample_count=int(item.get("sample_count") or 0),
        visibility_probability=float(item.get("visibility_probability") or 0.0),
        rssi_median=float(item["rssi_median"]),
        rssi_mean=_optional_float(item, "rssi_mean"),
        rssi_stddev=_optional_float(item, "rssi_stddev"),
        rssi_p10=_optional_float(item, "rssi_p10"),
        rssi_p90=_optional_float(item, "rssi_p90"),
        rssi_min=_optional_float(item, "rssi_min"),
        rssi_max=_optional_float(item, "rssi_max"),
        temporal_stability=_optional_float(item, "temporal_stability"),
    )


def _calibration(item: dict[str, Any]) -> ObserverCalibration:
    _require(item, "observer_id", "rssi_offset_db")
    return ObserverCalibration(
        observer_id=str(item["observer_id"]),
        rssi_offset_db=float(item["rssi_offset_db"]),
        measured_at_utc=_optional_str(item, "measured_at_utc"),
        sample_count=_optional_int(item, "sample_count"),
        spread_db=_optional_float(item, "spread_db"),
        method=_optional_str(item, "method"),
    )


def _device(item: dict[str, Any]) -> ManagedDevice:
    _require(item, "device_id")
    identifiers = [
        *(item.get("known_wifi_identifiers") or []),
        *(item.get("known_ble_identifiers") or []),
        *(item.get("known_service_uuids") or []),
        *(item.get("identifiers") or []),
    ]
    return ManagedDevice(
        device_id=str(item["device_id"]),
        friendly_name=str(item.get("friendly_name") or item["device_id"]),
        device_type=str(item.get("device_type") or "OTHER"),
        status=str(item.get("status") or "AUTHORIZED"),
        identifiers=tuple(sorted({str(identifier) for identifier in identifiers})),
    )


# -- helpers --------------------------------------------------------------------------------------


def _array(document: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = document.get(key) or []
    if not isinstance(value, list):
        raise ReferenceModelError(f"'{key}' must be an array")
    return [item for item in value if isinstance(item, dict)]


def _require(item: dict[str, Any], *keys: str) -> None:
    missing = [key for key in keys if item.get(key) is None]
    if missing:
        raise ReferenceModelError(f"missing required field(s) {', '.join(missing)} in {item!r:.120}")


def _optional_str(item: dict[str, Any], key: str) -> str | None:
    value = item.get(key)
    return None if value is None or value == "" else str(value)


def _optional_float(item: dict[str, Any], key: str) -> float | None:
    value = item.get(key)
    return None if value is None else float(value)


def _optional_int(item: dict[str, Any], key: str) -> int | None:
    value = item.get(key)
    return None if value is None else int(value)


def _readable(declared: str) -> bool:
    try:
        return int(declared.split(".", 1)[0]) == int(SCHEMA_VERSION.split(".", 1)[0])
    except ValueError:
        return False
