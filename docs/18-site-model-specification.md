# Site model specification (REFERENCE layer)

The site model is administrator-authored REFERENCE data. No existing CAD or site documentation is
required; everything here can be produced by an administrator with a tape measure and the Master app.

JSON Schema: [`../schema/site_model.schema.json`](../schema/site_model.schema.json).

## 1. Coordinate frame

One site-wide Cartesian frame in **metres**:

- Origin: a chosen `origin_lat` / `origin_lon` (WGS84), recorded once on the site record.
- `rotation_deg`: clockwise rotation of the site's +Y axis from true north, so buildings aligned to a
  road or fence line get axis-aligned coordinates.
- Projection: local tangent plane (equirectangular about the origin). Adequate and simple for a site
  of a few kilometres; the error at 5 km is well under a metre, far below the positional uncertainty
  the system can ever claim.
- Floors are discrete integers, not a Z coordinate. Indoor RF positioning distinguishes floors as
  *classes*, not as a continuous vertical measurement.

Every `x` / `y` in the entire system — observer positions, anchors, survey points, position estimates
— is in this one frame. Establishing it before the first survey is mandatory, because coordinates
from different frames are silently incomparable.

## 2. Entities

### Building

```
building_id | name | floors | origin_lat | origin_lon | rotation_deg
| outline_polygon (site-frame points) | notes
```

### Zone

```
zone_id | building_id | name | floor | polygon (site-frame points)
| centroid_x | centroid_y | enclosing_radius_m | zone_kind | notes
```

`zone_kind` ∈ `ROOM` | `AREA` | `CORRIDOR` | `THRESHOLD` | `OUTDOOR` | `PATH`.

`enclosing_radius_m` is precomputed as the radius of the smallest circle enclosing the polygon, and it
is what `pos_zone_centroid_v1` reports as uncertainty. Storing it makes the coarse-estimate
uncertainty derive from real site geometry rather than from a constant.

Zones may not overlap within a floor of a building. Overlap would make zone classification
ill-defined, and the Master validates against it.

### Zone edge (the site topology graph)

```
from_zone_id | to_zone_id | edge_type | typical_traversal_s | bidirectional | notes
```

`edge_type` ∈ `DOOR` | `CORRIDOR` | `OUTDOOR_PATH` | `STAIRS` | `RESTRICTED` | `IMPOSSIBLE`.

The specification's example chain is exactly the intended shape:

```
B4 <-> B5 <-> B6 <-> B7 <-> B8 <-> B9
```

`IMPOSSIBLE` edges are authored explicitly rather than inferred from the absence of an edge, because
"no edge yet" and "cannot happen" must be distinguishable. An unlisted pair is `UNKNOWN_EDGE` — the
movement engine treats it as suspicious but not impossible, while an `IMPOSSIBLE` edge produces a
`TOPOLOGY_VIOLATION` flag.

`typical_traversal_s` lets the movement engine judge plausibility of timing, not just adjacency: a
committed B7→B9 transition 4 seconds after a B7 estimate is implausible even though the zones are
connected.

### Infrastructure node

```
node_id | friendly_name | type | building_id | floor | zone_id | x | y
| latitude | longitude | known_bssid | known_ble_identifier | rtt_capable | notes
```

`type` ∈ `WIFI_AP` | `ZONE_ANCHOR` | `RTT_ANCHOR` | `BLE_ANCHOR` | `OBSERVER_PHONE` | `ROUTER` |
`OTHER`.

**Type assignment rules, which matter more than they appear to:**

| Situation | Correct type |
|---|---|
| AP with a known position, 802.11mc responder, verified by an actual RTT measurement | `RTT_ANCHOR` |
| AP with a known position, no RTT | `WIFI_AP` |
| Long-range router covering a wide area, position known only approximately | `ZONE_ANCHOR` |
| BLE beacon at a fixed measured position | `BLE_ANCHOR` |
| A collector phone at a fixed location | `OBSERVER_PHONE` |

A long-range router is a `ZONE_ANCHOR` and **not** a distance instrument. A published coverage radius
is a marketing or planning figure; it says nothing about ranging accuracy. Treating a 300-metre
coverage specification as a 300-metre measurement is the single most likely source of fabricated
precision in a system like this, so `rtt_capable` is set **only** after a real RTT range has been
obtained from that node — not from a datasheet.

### Survey point

```
survey_point_id | building_id | zone_id | floor | x | y | label
| physical_description | created_at_utc | notes
```

`physical_description` is free text plus (optionally) a photo reference. Repeatability is the whole
point: a survey point that cannot be re-occupied precisely cannot be resurveyed, and drift becomes
unmeasurable.

### Observer

```
observer_id | friendly_name | observer_device_type | building_id | default_zone_id
| device_model | platform | app_version | installation_id | capabilities
| x | y | fixed_observer | status | enrolled_at_utc
```

`fixed_observer = true` means `x`/`y` are trusted as a known measurement location, which upgrades
that observer's samples to reference-quality evidence for multi-observer fusion. A mobile observer's
`building_id` / `default_zone_id` are context only.

## 3. Map authoring workflow (Master, Milestone 3/5)

No CAD required. The intended sequence:

1. **Set the site origin.** Stand at a chosen landmark, capture a GNSS fix, average it over a minute.
2. **Sketch the site boundary and building outlines** by walking the perimeter with GNSS capture at
   corners, or by drawing on a canvas and calibrating against two measured reference points.
3. **Calibrate the drawing.** Two known points with measured real-world separation fix the
   scale and rotation of a hand-drawn or imported floor sketch.
4. **Draw zones** as polygons, name them the way people name them.
5. **Place anchors.** Walk to each AP/beacon, capture its position, record its BSSID or BLE
   identifier.
6. **Author the topology graph** by connecting adjacent zones and marking impossible pairs.
7. **Define survey points** and run the survey procedure from
   [`11-ground-truth-and-calibration-procedure.md`](11-ground-truth-and-calibration-procedure.md).

Step 3 is what makes the whole thing work without site documentation: a photograph of a whiteboard
sketch, plus two measured points, becomes a usable metric floor plan.

## 4. Site model versioning

The complete model is snapshotted as a `reference_model_id` (e.g. `site-2026-09-01`) whenever it
changes materially. Every derived estimate records which snapshot it used, so a historical estimate
remains interpretable after a zone is redrawn or an AP is moved.

Reprocessing must state which snapshot to use: the historical one (to reproduce a past result) or the
current one (to re-judge past data with today's knowledge). See
[`14-algorithm-versioning-strategy.md §5`](14-algorithm-versioning-strategy.md).

## 5. Validation rules

Checked by the Master before a snapshot can be created:

| Rule |
|---|
| Zone polygons have ≥ 3 vertices and do not self-intersect |
| Zones within one building+floor do not overlap |
| Every zone belongs to an existing building |
| Survey points lie inside their declared zone polygon |
| Infrastructure nodes with `x`/`y` lie inside their declared building outline |
| `known_bssid` is unique across infrastructure nodes |
| `rtt_capable = true` requires at least one observed RTT measurement from that node |
| Topology edges reference existing zones |
| The topology graph is connected, or its disconnected components are explicitly acknowledged |
| Every building has ≥ 1 zone with ≥ 1 promoted ground-truth fingerprint, or is flagged `SPARSE_CALIBRATION` |

The last rule is advisory rather than blocking — a site is allowed to be partially calibrated, but
never *silently* partially calibrated.
