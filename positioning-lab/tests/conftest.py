"""Shared fixtures.

The simulated site is session-scoped and small. Every test that needs a realistic dataset shares
one, because generating it is the expensive part and the tests that matter are about what the
pipeline does with it rather than about the generator.
"""

from __future__ import annotations

import pytest

from rfmapper_lab.models import DatasetKind
from rfmapper_lab.parsing import parse_reference_model
from rfmapper_lab.parsing.dataset import Dataset, IngestStats
from rfmapper_lab.pipeline import PipelineConfig
from rfmapper_lab.simulator import SimulationSpec, simulate

#: Small enough to keep the suite quick, large enough that every tier, every placement method and
#: the degenerate cases all appear. Losing any of those would make the suite pass while the code
#: it is meant to cover went untested.
SMALL_SITE = SimulationSpec(
    buildings=2,
    zones_per_building=3,
    survey_sessions=4,
    survey_samples_per_session=8,
    walk_steps=30,
    devices=2,
)


@pytest.fixture(scope="session")
def site():
    return simulate(SMALL_SITE)


@pytest.fixture(scope="session")
def model(site):
    return parse_reference_model(site.reference_document)


@pytest.fixture(scope="session")
def dataset(site, model) -> Dataset:
    observations = site.observations
    return Dataset(
        observations=observations,
        reference=model,
        source_dataset_ids=("sim-dataset",),
        stats=IngestStats(
            packages=1,
            rows_read=len(observations),
            rows_accepted=len(observations),
        ),
    )


@pytest.fixture(scope="session")
def config(dataset) -> PipelineConfig:
    span = dataset.time_range()
    return PipelineConfig(
        dataset_kind=DatasetKind.SYNTHETIC,
        computed_at_ms=span[1] if span else 0,
    )
