"""Version identity for everything the Lab produces.

Each engine is versioned independently because they are replaced independently: swapping the zone
classifier must not silently invalidate every position estimate's provenance. The composite
``pipeline_version`` is what a derived package declares, and it is derived from the engine versions
plus the parameter-set hash so that two runs that differ in any of them cannot collide.

See ``docs/14-algorithm-versioning-strategy.md``.
"""

from __future__ import annotations

LAB_NAME = "rfmapper_lab"
LAB_VERSION = "1.0.0"

SCHEMA_VERSION = "1.0.0"

FINGERPRINT_ENGINE_VERSION = "1.0.0"
ZONE_ENGINE_VERSION = "1.0.0"
FUSION_ENGINE_VERSION = "1.0.0"
POSITIONING_ENGINE_VERSION = "1.0.0"
UNCERTAINTY_ENGINE_VERSION = "1.0.0"
MOVEMENT_ENGINE_VERSION = "1.0.0"

PIPELINE_VERSION = "pipeline-1.0.0"


def engine_versions() -> dict[str, str]:
    return {
        "fingerprint_engine": FINGERPRINT_ENGINE_VERSION,
        "zone_engine": ZONE_ENGINE_VERSION,
        "fusion_engine": FUSION_ENGINE_VERSION,
        "positioning_engine": POSITIONING_ENGINE_VERSION,
        "uncertainty_engine": UNCERTAINTY_ENGINE_VERSION,
        "movement_engine": MOVEMENT_ENGINE_VERSION,
    }
