"""Session splits and the held-out vectors an algorithm is scored against.

``docs/12-benchmark-methodology-and-error-metrics.md`` §2.3: split by survey **session**, never by
sample. Samples inside one session share an operator, a handset, a time of day and a crowd level.
Splitting by sample puts near-duplicates of the same measurement on both sides of the wall, and the
resulting accuracy figure measures how well the algorithm memorised a session rather than how well
it will place a device tomorrow. The difference is not subtle — sample-split fingerprinting
benchmarks routinely report double the accuracy they deliver.

A session lands wholly in TRAIN, VALIDATION or TEST. Tuning happens on VALIDATION; TEST is read
once, at the end, to report. A hyperparameter chosen by looking at TEST has made TEST a training
set, and no amount of care afterwards undoes that.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np

from ..fusion import measurement_of
from ..models import LiveVector, Observation, ReferenceModel
from ..params import ParameterSet
from ..parsing.normalize import window_ms
from ..timeutil import format_ms

#: Default proportions. Enough TRAIN to build fingerprints that mean something, enough TEST that a
#: bootstrap interval over sessions is not simply the width of the whole scale.
DEFAULT_RATIOS = (0.6, 0.2, 0.2)


@dataclass(frozen=True, slots=True)
class SessionSplit:
    train: tuple[str, ...]
    validation: tuple[str, ...]
    test: tuple[str, ...]
    seed: int

    def as_dict(self) -> dict[str, object]:
        return {
            "seed": self.seed,
            "train_sessions": list(self.train),
            "validation_sessions": list(self.validation),
            "test_sessions": list(self.test),
            "train": len(self.train),
            "validation": len(self.validation),
            "test": len(self.test),
        }

    def describe(self) -> str:
        return (
            f"TRAIN={len(self.train)} sessions  VAL={len(self.validation)}  "
            f"TEST={len(self.test)}   (split by session, seed={self.seed})"
        )


def session_ids(observations: Iterable[Observation]) -> tuple[str, ...]:
    """Every survey session present, in a stable order."""
    return tuple(
        sorted({o.survey_session_id for o in observations if o.survey_session_id})
    )


def split_sessions(
    sessions: Sequence[str],
    seed: int,
    ratios: tuple[float, float, float] = DEFAULT_RATIOS,
) -> SessionSplit:
    """Assign whole sessions to the three splits, deterministically for a given seed.

    With very few sessions the split degenerates, and it does so loudly rather than quietly: one
    session cannot be both trained on and tested against, so a single-session dataset yields an
    empty TEST and the harness reports that it has nothing to measure. That is the correct answer
    to "how accurate is this?" when the survey has been done once.
    """
    ordered = sorted(set(sessions))
    if not ordered:
        return SessionSplit((), (), (), seed)

    shuffled = list(np.random.default_rng(seed).permutation(np.array(ordered, dtype=object)))
    total = len(shuffled)
    if total == 1:
        return SessionSplit((str(shuffled[0]),), (), (), seed)
    if total == 2:
        return SessionSplit((str(shuffled[0]),), (), (str(shuffled[1]),), seed)

    train_count = max(1, round(total * ratios[0]))
    validation_count = max(1, round(total * ratios[1]))
    # TEST is never allowed to be empty: a benchmark with nothing held out is not a benchmark.
    while train_count + validation_count > total - 1:
        if validation_count > 1:
            validation_count -= 1
        else:
            train_count -= 1

    train = tuple(str(item) for item in shuffled[:train_count])
    validation = tuple(str(item) for item in shuffled[train_count : train_count + validation_count])
    test = tuple(str(item) for item in shuffled[train_count + validation_count :])
    return SessionSplit(train=train, validation=validation, test=test, seed=seed)


@dataclass(frozen=True, slots=True)
class HeldOutSample:
    """One held-out survey capture, as a live vector plus the truth it is scored against."""

    vector: LiveVector
    session_id: str
    survey_point_id: str
    observer_id: str
    zone_id: str
    building_id: str
    x: float
    y: float


def held_out_samples(
    observations: Sequence[Observation],
    sessions: Sequence[str],
    model: ReferenceModel,
    params: ParameterSet,
) -> tuple[HeldOutSample, ...]:
    """Turn held-out survey rows into the live vectors an algorithm will actually be handed.

    A survey capture is the surveying handset's own scan from a known spot, so every measurement is
    in that observer's own frame — the same frame a device's own scans occupy in production. The
    fingerprints built from the TRAIN sessions are never consulted here; only the rows are.

    Rows are grouped into windows the same width as the pipeline's, and a window is kept only if it
    has at least one usable signal. Scoring a vector with no signal in it would measure the
    algorithm's behaviour on an empty input, which is a unit test, not a benchmark.
    """
    wanted = set(sessions)
    grouped: dict[tuple[str, str, str, int], list[Observation]] = {}
    step = max(
        1,
        min(
            params.fusion.window_ms_wifi,
            params.fusion.window_ms_ble,
            params.fusion.window_ms_rtt,
            params.fusion.window_ms_default,
        ),
    )

    for observation in observations:
        session = observation.survey_session_id
        point = observation.survey_point_id
        if not session or session not in wanted or not point:
            continue
        if observation.rssi is None and observation.rtt_distance_mm is None:
            continue
        bucket = observation.timestamp_ms // step
        grouped.setdefault((session, point, observation.observer_id, bucket), []).append(
            observation
        )

    samples: list[HeldOutSample] = []
    for key in sorted(grouped):
        session, point_id, observer_id, bucket = key
        survey_point = model.survey_points.get(point_id)
        if survey_point is None:
            # Ground truth for a point the site model does not contain cannot be scored against:
            # there is no coordinate to be right or wrong about.
            continue

        anchor = bucket * step + step // 2
        measurements = []
        for observation in grouped[key]:
            age = abs(observation.timestamp_ms - anchor)
            if age > window_ms(observation.sensor_type, params.fusion):
                continue
            measurements.append(
                measurement_of(observation, age, model, params, measures_device=False)
            )
        if not measurements:
            continue

        samples.append(
            HeldOutSample(
                vector=LiveVector(
                    device_id=f"survey:{point_id}",
                    timestamp_ms=anchor,
                    timestamp_utc=format_ms(anchor),
                    measurements=tuple(measurements),
                ),
                session_id=session,
                survey_point_id=point_id,
                observer_id=observer_id,
                zone_id=survey_point.zone_id,
                building_id=survey_point.building_id,
                x=survey_point.x,
                y=survey_point.y,
            )
        )
    return tuple(samples)


def training_observations(
    observations: Sequence[Observation],
    sessions: Sequence[str],
) -> tuple[Observation, ...]:
    """Ground-truth rows from the named sessions only.

    The one line that keeps the whole exercise honest: a fingerprint built from a session that is
    later tested against would be validating itself, and the resulting accuracy would look
    excellent for reasons that have nothing to do with the site.
    """
    wanted = set(sessions)
    return tuple(
        observation
        for observation in observations
        if observation.is_ground_truth and observation.survey_session_id in wanted
    )
