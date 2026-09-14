"""Phase 6 — multi-observer fusion over time windows."""

from __future__ import annotations

from .windows import (
    attribute_device,
    build_vectors,
    frame_weights,
    fuse_zones,
    measurement_of,
    signal_source_key,
    split_by_frame,
)

__all__ = [
    "attribute_device",
    "build_vectors",
    "frame_weights",
    "fuse_zones",
    "measurement_of",
    "signal_source_key",
    "split_by_frame",
]
