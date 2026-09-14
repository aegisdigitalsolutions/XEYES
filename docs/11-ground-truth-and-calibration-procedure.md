# Positioning Lab deliverable 3 — Ground-truth and calibration procedure

Ground truth is the foundation of every accuracy claim this system will ever make. It is produced by
a deliberate human procedure, never by inference.

## 1. The single rule

A measurement becomes ground truth **only** when a human physically stood at a known point and ran
Survey Mode. Mechanically:

```
Survey Mode  ──► metadata.sample_kind = GROUND_TRUTH
                 metadata.survey_point_id = <labelled point>
                 metadata.survey_session_id = <uuid>
                          │
                          ▼
             ref_fingerprint.status = CANDIDATE
                          │
             administrator reviews and promotes   ◄── the only path to ground truth
                          ▼
             ref_fingerprint.status = GROUND_TRUTH
```

There is no automatic transition into `GROUND_TRUTH`. Two independent checks enforce it: the Master
rejects an import claiming `sample_kind=GROUND_TRUTH` without a matching survey session, and the Lab
ignores `sample_kind=ORDINARY` rows when building fingerprints.

Ordinary daily observations *can* tell us that calibration is weak — that produces a
`SPARSE_CALIBRATION` flag and a recommendation such as *"Building 4 fingerprint could be improved"*.
They can never redefine it.

## 2. Site preparation

Before the first survey:

1. **Enumerate infrastructure.** Every AP and BLE anchor gets an `InfrastructureNode` with its type.
   Long-range routers that cannot range are `ZONE_ANCHOR` — a coverage specification is not a ranging
   accuracy, and conflating them is the fastest way to fabricate precision.
2. **Define buildings and zones.** Zones should correspond to places a human would name ("B7 north
   door"), because zone-level output is the tier that will actually be used day to day.
3. **Establish a site coordinate frame.** One origin, one rotation, metres. Recorded in
   `ref_building` (`origin_lat`, `origin_lon`, `rotation_deg`). Every `x`/`y` in the system is in this
   frame. Without it, coordinates from different surveys are not comparable.
4. **Build the topology graph.** Adjacency, doors, corridors, impossible transitions
   (`ref_zone_edge`). Cheap to author, and it is what makes implausible jumps detectable.
5. **Label survey points.** At minimum: each zone centre, each door/threshold, and each inter-building
   path. The specification's examples are the right pattern: `B7_CENTER`, `B7_NORTH_DOOR`,
   `B7_SOUTH_DOOR`, `B7_B9_PATH`, `B9_CENTER`, `B4_CENTER`.

## 3. Survey procedure

Per point:

1. Stand at the labelled point. Record the physical location precisely enough to be repeatable —
   a photograph and a measured offset from two permanent features beats a description.
2. Open Survey Mode, select or create the `survey_point_id`, confirm the site-frame `x`/`y`.
3. `START 60 SECOND SURVEY`. Hold the device at a consistent height (~1.2 m, waist/pocket height) and
   **rotate slowly through 360°** over the capture.
4. Confirm the sample count looks plausible for the duration and cadence before moving on.

Rotation matters: body shadowing routinely costs 10–20 dB on the far side of the operator. A survey
captured facing one direction produces a fingerprint that only matches a target facing that
direction. Rotating averages the body-orientation effect into the distribution instead of baking one
orientation into the median.

### Repeat surveys

A point is surveyed **at least three times, on different days, and ideally by different operators and
different phone models**. One session captures one moment's RF environment and one device's chipset
bias; the variance across sessions is itself information, and it is what `temporal_stability` in the
fingerprint records. A fingerprint built from a single session is marked `SINGLE_SESSION` and
down-weighted.

### Conditions to vary deliberately

Occupancy (empty vs busy), doors open vs closed, and machinery on vs off. Each is recorded in
`metadata.survey_conditions`. If these produce materially different fingerprints, that is a finding
about the site, not noise to be averaged away — it may justify separate condition-tagged
fingerprints.

## 4. Observer RSSI calibration

Different chipsets report different RSSI for the same signal. Procedure:

1. Place all observer devices at **one** point, same height, same orientation, simultaneously.
2. Collect for 10 minutes.
3. For each radio source seen by all devices, compute each device's median RSSI.
4. Nominate a reference device. $\delta_o$ = median over sources of (reference median − device
   median).
5. Record in `ref_observer_calibration` with `sample_count` and `method`.

Apply an offset only when it is consistent across sources and its spread is small relative to its
magnitude. A "+3 dB" offset whose per-source values range from −4 to +10 is not an offset; it is
antenna-pattern difference, and applying it would make things worse. In that case leave
$\delta_o = 0$ and record the finding.

Recalibrate when a device is replaced, after a major OS update, and quarterly.

## 5. Train / validation / test splitting

**Split by survey session, never by sample.** Samples within one 60-second capture are strongly
correlated (same operator, same posture, same minute of RF environment). A random per-sample split
leaks nearly-identical samples across the boundary and yields accuracy figures that are simply wrong
— typically optimistic by a factor that is easy to mistake for success.

| Split | Share of sessions | Use |
|---|---|---|
| TRAINING | ~60% | Build fingerprints |
| VALIDATION | ~20% | Tune $k$, $\lambda$, hysteresis thresholds |
| TEST | ~20% | Report final numbers. **Touched once, at the end.** |

Every survey point must appear in TRAINING. Points with fewer than three sessions go entirely to
TRAINING and are excluded from reported metrics, with that exclusion stated in the report.

The walk test from the Milestone 1 field protocol is a **separate** evaluation set: manually
timestamped waypoints along a route. It is the only way to measure transition-detection latency and
false-transition rate, which static point surveys cannot assess.

## 6. Ground-truth data quality checks

Automatic checks before a candidate fingerprint can be promoted:

| Check | Threshold |
|---|---|
| Sample count | ≥ 30 per session |
| Duration | Within ±20% of the requested window |
| Session count | ≥ 3 for a promotable fingerprint |
| Source overlap between sessions | Jaccard ≥ 0.6 on sources with $\pi > 0.5$ |
| Median RSSI agreement between sessions | Within 8 dB per common source |
| Position plausibility | Point lies inside its declared zone polygon |
| Self-consistency | A leave-one-session-out classification of the point returns its own zone |

The last check is the strongest: if a point's own sessions cannot identify the point, that point is
not discriminable and no downstream algorithm will fix it. Failures are surfaced for administrator
decision — resurvey, relabel, or accept and record the limitation.

## 7. Maintenance

- **Quarterly** resurvey of a rotating subset to measure drift.
- **On infrastructure change** (AP added/moved/replaced), resurvey affected zones. The change is
  usually detected first by `AP_RELOCATED` from the daily drift check.
- **Version fingerprints, never overwrite them.** A resurvey creates a new `ref_fingerprint` row;
  the old one becomes `RETIRED`. Historical estimates remain explicable because the fingerprint they
  used still exists.
