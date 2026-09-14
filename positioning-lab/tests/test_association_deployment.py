"""A site with no survey: Wi-Fi APs on towers, and association reports as the only evidence.

Everything else in this suite assumes the fingerprinting deployment the specification describes — a
surveyor walks the site, records ground truth at marked points, and the classifiers compare live
vectors against it. A site can also be built the other way round, with the infrastructure doing the
observing: an AP on each tower reports which enrolled devices are currently associated to it, nobody
ever runs a survey, and the honest answer for a device is the name of a tower.

That deployment is not a degraded version of the other one. It is a different evidence type with a
different ceiling, and the properties worth pinning are mostly about what it must *not* claim.
"""

from __future__ import annotations

from dataclasses import replace

import pytest

from rfmapper_lab.models import DatasetKind, IdentifierType, Observation, SensorType
from rfmapper_lab.params import DEFAULTS
from rfmapper_lab.parsing.dataset import Dataset, IngestStats
from rfmapper_lab.parsing.reference_reader import parse_reference_model
from rfmapper_lab.pipeline import PipelineConfig, run_pipeline
from rfmapper_lab.timeutil import format_ms

BASE_MS = 1_780_000_000_000

#: tower id, its coverage zone, the BSSID it advertises, where it stands
TOWERS = (
    ("TOWER-1", "YARD-T1", "aa:bb:cc:00:00:01", (10.0, 10.0)),
    ("TOWER-2", "YARD-T2", "aa:bb:cc:00:00:02", (60.0, 10.0)),
    ("TOWER-3", "YARD-T3", "aa:bb:cc:00:00:03", (110.0, 10.0)),
)

STATIONARY = "DEVICE-STATIONARY"
ROAMING = "DEVICE-ROAMING"
DEVICE_MACS = {STATIONARY: "11:22:33:44:55:01", ROAMING: "11:22:33:44:55:02"}

#: The roaming device leaves tower 1 for tower 3 at this point in the day.
MOVE_AT_MINUTE = 15
MINUTES = 30


def _site_model() -> dict:
    zones, infrastructure, observers = [], [], []
    for index, (tower, zone, bssid, (cx, cy)) in enumerate(TOWERS):
        half = 25.0
        zones.append(
            {
                "zone_id": zone,
                "building_id": "YARD",
                "name": f"{tower} coverage",
                "floor": 0,
                "zone_kind": "OUTDOOR_AREA",
                "polygon": [
                    {"x": cx - half, "y": cy - half},
                    {"x": cx + half, "y": cy - half},
                    {"x": cx + half, "y": cy + half},
                    {"x": cx - half, "y": cy + half},
                ],
                "centroid_x": cx,
                "centroid_y": cy,
                "enclosing_radius_m": half * 1.42,
            }
        )
        # The tower is both the observer (it reports the associations) and the infrastructure node
        # whose coverage defines the zone. Registering it as a node is what lets the anchor
        # classifier turn "reported by TOWER-2" into "in TOWER-2's zone".
        infrastructure.append(
            {
                "node_id": tower,
                "friendly_name": f"{tower} AP",
                "type": "ROUTER",
                "building_id": "YARD",
                "floor": 0,
                "zone_id": zone,
                "x": cx,
                "y": cy,
                "known_bssid": bssid,
                "rtt_capable": False,
            }
        )
        observers.append(
            {
                "schema_version": "1.0.0",
                "observer_id": tower,
                "friendly_name": f"{tower} AP",
                "observer_device_type": "FIXED_OBSERVER",
                "building_id": "YARD",
                "default_zone_id": zone,
                "platform": "other",
                "app_version": "1.0.0",
                "installation_id": f"b1c2d3e4-1111-4222-8333-44445555000{index}",
                "capabilities": ["WIFI_ASSOCIATION", "WIFI_SCAN"],
                "x_coordinate": cx,
                "y_coordinate": cy,
                "fixed_observer": True,
            }
        )

    return {
        "schema_version": "1.0.0",
        "reference_model_id": "towers-no-survey",
        "created_at": format_ms(BASE_MS),
        "frame": {
            "frame_id": "site",
            "origin_latitude": 51.5,
            "origin_longitude": -0.12,
            "rotation_degrees": 0.0,
            "units": "METERS",
        },
        "buildings": [
            {
                "building_id": "YARD",
                "name": "Yard",
                "floors": [0],
                "outline_polygon": [
                    {"x": -20.0, "y": -20.0},
                    {"x": 140.0, "y": -20.0},
                    {"x": 140.0, "y": 40.0},
                    {"x": -20.0, "y": 40.0},
                ],
            }
        ],
        "zones": zones,
        "zone_edges": [
            {"from_zone_id": "YARD-T1", "to_zone_id": "YARD-T2", "edge_type": "OPEN"},
            {"from_zone_id": "YARD-T2", "to_zone_id": "YARD-T3", "edge_type": "OPEN"},
        ],
        "infrastructure_nodes": infrastructure,
        "observers": observers,
        # The whole point: nobody ever surveyed this site and nobody ever will.
        "survey_points": [],
        "fingerprints": [],
        "observer_calibration": [],
        "managed_devices": [
            {
                "device_id": device_id,
                "friendly_name": device_id,
                "device_type": "PHONE",
                "status": "AUTHORIZED",
                "known_wifi_identifiers": [mac],
                "known_ble_identifiers": [],
                "known_service_uuids": [],
            }
            for device_id, mac in sorted(DEVICE_MACS.items())
        ],
    }


def _observations() -> tuple[Observation, ...]:
    rows, index = [], 0
    for minute in range(MINUTES):
        at = BASE_MS + minute * 60_000
        attached = [
            ("TOWER-1", DEVICE_MACS[STATIONARY]),
            (
                "TOWER-1" if minute < MOVE_AT_MINUTE else "TOWER-3",
                DEVICE_MACS[ROAMING],
            ),
        ]
        for tower, mac in attached:
            index += 1
            rows.append(
                Observation(
                    observation_id=f"00000000-0000-4000-8000-{index:012d}",
                    timestamp_utc=format_ms(at),
                    timestamp_ms=at,
                    observer_id=tower,
                    sensor_type=SensorType.WIFI_ASSOCIATION,
                    radio_identifier=mac,
                    identifier_type=IdentifierType.WIFI_BSSID,
                    bssid=mac,
                    rssi=-55,
                    building_id="YARD",
                    metadata={"sample_kind": "ORDINARY"},
                )
            )
    return tuple(sorted(rows, key=lambda row: (row.timestamp_ms, row.observation_id)))


@pytest.fixture(scope="module")
def dataset() -> Dataset:
    rows = _observations()
    return Dataset(
        observations=rows,
        reference=parse_reference_model(_site_model()),
        source_dataset_ids=("TOWERS_2026-09-14",),
        stats=IngestStats(packages=1, rows_read=len(rows), rows_accepted=len(rows)),
    )


def _run(dataset: Dataset, engine: str):
    return run_pipeline(
        dataset,
        PipelineConfig(
            zone_engine=engine,
            dataset_kind=DatasetKind.REAL,
            computed_at_ms=dataset.observations[-1].timestamp_ms,
        ),
    )


class TestAssociationOnlySite:
    def test_no_survey_is_required_to_name_the_tower(self, dataset):
        assert not dataset.reference.survey_points
        assert not dataset.reference.fingerprints

        result = _run(dataset, "zone_anchor_v1")
        zones = {
            (estimate.device_id, estimate.zone_id) for estimate in result.estimates
        }

        assert zones == {
            (STATIONARY, "YARD-T1"),
            (ROAMING, "YARD-T1"),
            (ROAMING, "YARD-T3"),
        }

    def test_the_tower_is_the_answer_and_no_coordinate_is_offered(self, dataset):
        """The ceiling of this evidence. An association says a device is within range of one
        transmitter, and range is asymmetric and environment-dependent, so the middle of the
        coverage polygon is the tower's own position rather than an estimate of the device's.
        """
        result = _run(dataset, "zone_anchor_v1")

        assert result.tier_breakdown() == {"ZONE": MINUTES * 2}
        assert result.method_breakdown() == {"pos_zone_only_v1": MINUTES * 2}
        assert all(estimate.x is None and estimate.y is None for estimate in result.estimates)
        assert all(
            estimate.horizontal_uncertainty_m is None for estimate in result.estimates
        )

    def test_a_fingerprint_engine_on_this_site_answers_building_and_stops(self, dataset):
        """Not a bug, and the reason the engine has to be chosen deliberately. The default
        classifier compares against fingerprints, there are none, and it correctly declines to
        invent a zone rather than falling back to the anchors behind the operator's back.
        """
        result = _run(dataset, "zone_bayes_v1")

        assert result.tier_breakdown() == {"BUILDING": MINUTES * 2}
        assert all(estimate.zone_id is None for estimate in result.estimates)
        assert all(
            "NO_ZONE_EVIDENCE" in estimate.quality_flags for estimate in result.estimates
        )

    def test_a_lone_association_is_not_scored_as_an_uncorroborated_sighting(self, dataset):
        """A device holds one Wi-Fi association at a time, so a second observer is unobtainable
        rather than missing, and the survey density behind it is irrelevant to the claim. Charging
        the site for either would hold it permanently below every downstream threshold.
        """
        estimate = _run(dataset, "zone_anchor_v1").estimates[0]

        assert estimate.confidence_factors["observer_count"] > 0.45
        assert estimate.confidence_factors["calibration_density"] == 1.0

    def test_a_move_that_is_never_committed_is_reported_as_such(self, dataset):
        """The roaming device plainly changes tower and the transition does not clear the default
        confidence gate. An empty movement history and a suppressed one read identically on a map,
        so the run has to say which it is, and what the threshold would have to be.
        """
        result = _run(dataset, "zone_anchor_v1")
        committed = [
            (transition.origin_zone_id, transition.destination_zone_id)
            for transition in result.transitions
        ]

        assert ("YARD-T1", "YARD-T3") not in committed
        assert result.withheld_transitions == 1
        assert result.withheld_by_gate == {"confidence": 1}
        assert result.peak_withheld_confidence is not None
        assert result.peak_withheld_confidence < DEFAULTS.movement.min_transition_confidence

    def test_lowering_the_gate_to_what_this_evidence_yields_commits_the_move(self, dataset):
        """Confirms the diagnosis above rather than endorsing the number: the move is visible in
        the estimates and only the threshold is holding it back. The threshold itself is a
        placeholder and belongs to a walk test on the real site.
        """
        measured = _run(dataset, "zone_anchor_v1").peak_withheld_confidence
        params = replace(
            DEFAULTS,
            movement=replace(DEFAULTS.movement, min_transition_confidence=measured),
        )

        result = run_pipeline(
            dataset,
            PipelineConfig(
                zone_engine="zone_anchor_v1",
                params=params,
                dataset_kind=DatasetKind.REAL,
                computed_at_ms=dataset.observations[-1].timestamp_ms,
            ),
        )
        committed = [
            (transition.origin_zone_id, transition.destination_zone_id)
            for transition in result.transitions
        ]

        assert ("YARD-T1", "YARD-T3") in committed
        assert result.withheld_transitions == 0
