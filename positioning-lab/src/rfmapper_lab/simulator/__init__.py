"""Synthetic site simulator.

What it is for, and what it is emphatically not for
(``docs/12-benchmark-methodology-and-error-metrics.md`` §6):

**Valid** — proving the pipeline is correct end to end, proving an algorithm is implemented as
specified, catching regressions, and exercising the degenerate cases that real data may not contain
for months (collinear RTT anchors, a zone with no ground truth, an observer with a large offset).

**Invalid** — any accuracy claim about a real site. The simulator generates observations from the
very log-distance propagation model the specification tells us not to trust indoors, so good
synthetic numbers demonstrate internal consistency and nothing more. Every report derived from it
is stamped ``dataset_kind: SYNTHETIC``.
"""

from __future__ import annotations

from .packages import WrittenPackage, write_packages
from .site import SimulatedSite, SimulationSpec, simulate

__all__ = [
    "SimulatedSite",
    "SimulationSpec",
    "WrittenPackage",
    "simulate",
    "write_packages",
]
