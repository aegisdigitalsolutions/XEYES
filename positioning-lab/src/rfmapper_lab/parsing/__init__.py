"""Phase 1 — parse, normalize, globally deduplicate.

The Lab consumes export packages and the site model document, and nothing else: it never reads a
Collector database and never reads a previous DERIVED generation
(``docs/01-repository-architecture.md`` §3).
"""

from __future__ import annotations

from .dataset import Dataset, IngestStats, load_dataset, load_packages
from .package_reader import PackageContents, PackageProblem, read_package
from .reference_reader import (
    ReferenceModelError,
    load_devices,
    load_reference_model,
    parse_reference_model,
)

__all__ = [
    "Dataset",
    "IngestStats",
    "PackageContents",
    "PackageProblem",
    "ReferenceModelError",
    "load_dataset",
    "load_devices",
    "load_packages",
    "load_reference_model",
    "parse_reference_model",
    "read_package",
]
