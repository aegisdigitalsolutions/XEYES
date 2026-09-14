"""Phases 3 and 4 — ground-truth ingestion and statistical fingerprint construction."""

from __future__ import annotations

from .checks import PromotionCheck, PromotionReport, evaluate_promotion
from .engine import FingerprintEngineV1, FingerprintSet, build_fingerprints

__all__ = [
    "FingerprintEngineV1",
    "FingerprintSet",
    "PromotionCheck",
    "PromotionReport",
    "build_fingerprints",
    "evaluate_promotion",
]
