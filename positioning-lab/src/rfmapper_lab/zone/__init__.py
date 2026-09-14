"""Phase 5 — zone classification.

Five candidate methods, all behind one interface, selected by benchmark rather than by preference
(``docs/12-benchmark-methodology-and-error-metrics.md``). Importing this package registers them.
"""

from __future__ import annotations

from .classifiers import (
    ZoneAnchorV1,
    ZoneBayesV1,
    ZoneCosineV1,
    ZoneNearestNeighbourV1,
    ZoneWeightedKnnV1,
    signal_distance,
)

__all__ = [
    "ZoneAnchorV1",
    "ZoneBayesV1",
    "ZoneCosineV1",
    "ZoneNearestNeighbourV1",
    "ZoneWeightedKnnV1",
    "signal_distance",
]
