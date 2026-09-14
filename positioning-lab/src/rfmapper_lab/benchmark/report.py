"""The report, per ``docs/12-benchmark-methodology-and-error-metrics.md`` §4.

``algorithm_report.json`` for machines, a text table for people. The table is built so that it
cannot print an error figure without its coverage in the same row, which is the one presentational
rule the methodology insists on: an algorithm answering a tenth of the time with three-metre error
is not better than one answering almost always with seven, and a median-only table says otherwise.

Synthetic runs are stamped and get a banner. Every number in this report comes from the very
log-distance propagation model the specification tells us not to trust indoors, so good synthetic
numbers prove that the pipeline is internally consistent and nothing about a real site.
"""

from __future__ import annotations

from ..models import DatasetKind
from .harness import BenchmarkReport, CandidateResult

SYNTHETIC_BANNER = (
    "!! SYNTHETIC DATASET. These numbers describe the simulator's own propagation model, not any\n"
    "!! real environment. No accuracy claim about a real site may cite this report."
)

_HEADER = (
    f"{'algorithm':<26}{'zone_acc':>10}{'top2':>8}{'bldg_acc':>10}"
    f"{'median_err':>12}{'P90_err':>10}{'coverage':>10}{'P68_cont':>10}{'cpu_ms':>9}"
)


def format_report(report: BenchmarkReport) -> str:
    lines: list[str] = []
    if report.dataset_kind is not DatasetKind.REAL:
        lines.append(SYNTHETIC_BANNER)
        lines.append("")

    samples = report.results[0].classification.samples if report.results else 0
    total_sessions = (
        len(report.split.train) + len(report.split.validation) + len(report.split.test)
    )
    lines.append(
        f"Dataset: {report.reference_model_id}  sessions={total_sessions}  "
        f"test_samples={samples}  content_sha256={report.dataset_content_sha256[:8]}..."
    )
    lines.append(f"Splits: {report.split.describe()}")
    lines.append("")

    if not report.results or samples == 0:
        lines.append(
            "No held-out samples. A survey with too few sessions to split cannot be benchmarked, "
            "and the honest answer to 'how accurate is it?' is that nobody knows yet."
        )
        return "\n".join(lines)

    lines.append(_HEADER)
    for result in report.results:
        lines.append(_row(result))
    lines.append("")

    intervals = [
        (result.candidate.label, result.intervals["zone_accuracy"])
        for result in report.results
        if "zone_accuracy" in result.intervals
    ]
    if intervals:
        ranked = sorted(intervals, key=lambda item: -item[1].point)[:3]
        rendered = "  ".join(f"{label} {interval}" for label, interval in ranked)
        if any(interval.resamples == 0 for _, interval in ranked):
            # A zero-width interval is not a tight one. Printing it without saying so invites the
            # reader to conclude that two candidates are separated when the split had one session
            # and the bootstrap had nothing to resample.
            verdict = "  -> degenerate: too few held-out sessions to bound"
        elif len(ranked) >= 2 and ranked[0][1].overlaps(ranked[1][1]):
            verdict = "  -> overlapping"
        elif len(ranked) >= 2:
            verdict = "  -> separated"
        else:
            verdict = ""
        lines.append(f"Bootstrap 95% CI on zone_acc: {rendered}{verdict}")

    calibration = "  ".join(
        f"{result.candidate.label}={result.calibration.verdict}" for result in report.results
    )
    lines.append(f"Uncertainty calibration: {calibration}")
    lines.append(f"Selected: {report.selected}  ({report.selection_reason})")

    zones_uncovered = report.data_quality.get("zones_without_test_coverage") or []
    if zones_uncovered:
        lines.append(
            f"Zones with no held-out ground truth ({len(zones_uncovered)}): "
            + ", ".join(str(zone) for zone in zones_uncovered[:8])
            + ("..." if len(zones_uncovered) > 8 else "")
        )

    if report.movement:
        lines.append("")
        lines.extend(_movement_lines(report.movement))

    return "\n".join(lines)


def _row(result: CandidateResult) -> str:
    classification = result.classification
    positional = result.positional
    calibration = result.calibration

    # Error and coverage are formatted together, in one expression, so no future edit can print one
    # without the other.
    if positional.median_m is None:
        error_columns = f"{'-':>12}{'-':>10}{positional.coverage:>10.2f}"
    else:
        error_columns = (
            f"{positional.median_m:>10.1f} m{positional.p90_m:>8.1f} m"
            f"{positional.coverage:>10.2f}"
        )

    containment = (
        f"{calibration.p68_containment:>10.2f}"
        if calibration.p68_containment is not None
        else f"{'-':>10}"
    )
    cpu = f"{result.cpu_ms:>9.2f}" if result.cpu_ms is not None else f"{'-':>9}"

    return (
        f"{result.candidate.label:<26}"
        f"{classification.zone_accuracy:>10.2f}"
        f"{classification.zone_top2_accuracy:>8.2f}"
        f"{classification.building_accuracy:>10.2f}"
        f"{error_columns}"
        f"{containment}"
        f"{cpu}"
    )


def _movement_lines(movement: dict) -> list[str]:
    lines = ["Movement (walk-test route):"]
    baseline = movement.get("baseline")
    if isinstance(baseline, dict):
        lines.append(
            f"  true={baseline.get('true_transitions')}  "
            f"committed={baseline.get('committed_transitions')}  "
            f"matched={baseline.get('matched')}  "
            f"median_latency={baseline.get('median_latency_s')} s  "
            f"missed={baseline.get('missed_rate')}  "
            f"false/h={baseline.get('false_per_hour')}"
        )
        # The per-hour rate is extrapolated from however long the route took. Over a few minutes
        # one spurious commit becomes a double-digit hourly rate, and the reader needs the
        # denominator to know which of those they are looking at.
        lines.append(f"  route duration: {baseline.get('duration_hours')} h")
    sweep = movement.get("sweep")
    if isinstance(sweep, list) and sweep:
        lines.append(
            f"  {'T_min(s)':>9}{'C_min':>7}{'N_min':>7}"
            f"{'latency(s)':>12}{'missed':>9}{'false/h':>9}{'osc':>7}"
        )
        for point in sweep:
            latency = point.get("median_latency_s")
            lines.append(
                f"  {point.get('min_candidate_duration_ms', 0) / 1000:>9.0f}"
                f"{point.get('min_transition_confidence', 0):>7.2f}"
                f"{point.get('min_supporting_observations', 0):>7}"
                f"{(f'{latency:.1f}' if latency is not None else '-'):>12}"
                f"{point.get('missed_rate', 0):>9.2f}"
                f"{point.get('false_per_hour', 0):>9.2f}"
                f"{point.get('oscillation_rate', 0):>7.2f}"
            )
    chosen = movement.get("operating_point")
    if isinstance(chosen, dict):
        lines.append(
            f"  Suggested operating point: T_min="
            f"{chosen.get('min_candidate_duration_ms', 0) / 1000:.0f} s  "
            f"C_min={chosen.get('min_transition_confidence')}  "
            f"N_min={chosen.get('min_supporting_observations')}  "
            f"(lowest latency within the false-commit budget)"
        )
    elif isinstance(sweep, list) and sweep:
        lines.append(
            "  No setting met the false-commit budget. Either the route was too short for an "
            "hourly rate to mean anything, or no threshold in this grid separates real moves "
            "from noise on this site."
        )
    return lines
