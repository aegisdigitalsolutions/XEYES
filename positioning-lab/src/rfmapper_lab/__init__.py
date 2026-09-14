"""RFMapper Positioning & Fusion Lab.

Offline statistical inference over exported wireless observations. Reads RAW observation packages
and the REFERENCE site model, writes a ``DERIVED_<date>.zip`` for the Master to import. Never
mutates RAW, never writes to the Master's database, never talks to a radio.

The operating principle, from the specification: measure first, model second, validate third, and
increase complexity only when the data justifies it.
"""

from __future__ import annotations

from .params import DEFAULTS, ParameterSet
from .version import LAB_NAME, LAB_VERSION, PIPELINE_VERSION, SCHEMA_VERSION

# Registration is a side effect of importing the engine packages, so they are imported here rather
# than left to whichever module happens to ask for an engine first. A registry whose contents
# depend on import order would let `zone_bayes_v1` resolve in one entry point and raise in another.
from . import positioning as positioning  # noqa: E402
from . import zone as zone  # noqa: E402

__all__ = [
    "DEFAULTS",
    "LAB_NAME",
    "LAB_VERSION",
    "PIPELINE_VERSION",
    "SCHEMA_VERSION",
    "ParameterSet",
    "positioning",
    "zone",
]
