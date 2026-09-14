"""Algorithm protocols and the registry that keys them by permanent id.

``docs/10-positioning-mathematical-architecture.md`` §13 requires every engine to be replaceable:
implementations register under a permanent id, the pipeline takes ids as configuration, and every
output row records which id produced it. That is what lets the benchmark compare two classifiers on
identical inputs, and what keeps a historical estimate explicable after the default changes.

Algorithm ids are never reused or repurposed. ``zone_nn_v1`` means one specific method forever; an
improved nearest neighbour registers as ``zone_nn_v2`` alongside it.
"""

from __future__ import annotations

from typing import Callable, Generic, Protocol, TypeVar, runtime_checkable

from .models import (
    FingerprintPoint,
    LiveVector,
    Placement,
    ReferenceModel,
    ZoneResult,
)
from .params import ParameterSet


@runtime_checkable
class ZoneClassifier(Protocol):
    """Ranks candidate zones for one live vector."""

    id: str
    version: str

    def classify(
        self,
        vector: LiveVector,
        fingerprints: tuple[FingerprintPoint, ...],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> ZoneResult: ...


@runtime_checkable
class PositioningEngine(Protocol):
    """Turns a zone result plus evidence into the deepest estimate the evidence supports.

    Returning ``None`` is a first-class outcome: a method that does not apply declines rather than
    returning a low-quality answer, and the pipeline falls through to the next strategy.
    """

    id: str
    version: str

    def estimate(
        self,
        vector: LiveVector,
        zones: ZoneResult,
        fingerprints: tuple[FingerprintPoint, ...],
        model: ReferenceModel,
        params: ParameterSet,
    ) -> Placement | None: ...


T = TypeVar("T")


class Registry(Generic[T]):
    """A name-to-implementation map that refuses silent replacement.

    Re-registering an id would let two different methods share a name, and every derived row that
    cites that name would become ambiguous. So it raises.
    """

    def __init__(self, kind: str) -> None:
        self._kind = kind
        self._items: dict[str, T] = {}

    def register(self, key: str, item: T) -> T:
        if key in self._items:
            raise ValueError(f"{self._kind} id '{key}' is already registered")
        self._items[key] = item
        return item

    def get(self, key: str) -> T:
        try:
            return self._items[key]
        except KeyError:
            raise KeyError(
                f"unknown {self._kind} '{key}'; registered: {', '.join(self.ids())}"
            ) from None

    def ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._items))

    def all(self) -> tuple[T, ...]:
        return tuple(self._items[key] for key in self.ids())

    def __contains__(self, key: object) -> bool:
        return key in self._items

    def __len__(self) -> int:
        return len(self._items)


ZONE_CLASSIFIERS: Registry[ZoneClassifier] = Registry("zone classifier")
POSITIONING_ENGINES: Registry[PositioningEngine] = Registry("positioning engine")


def zone_classifier(cls: Callable[[], ZoneClassifier]):
    """Class decorator: instantiate and register under the class's own ``id``."""
    instance = cls()
    ZONE_CLASSIFIERS.register(instance.id, instance)
    return cls


def positioning_engine(cls: Callable[[], PositioningEngine]):
    instance = cls()
    POSITIONING_ENGINES.register(instance.id, instance)
    return cls
