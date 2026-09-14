# Deliverable 19 — The association-only deployment

Every other document here describes the fingerprinting deployment: a surveyor walks the site,
records ground truth at marked points, and the classifiers compare live vectors against it. A site
can be built the other way round, with the infrastructure doing the observing.

**The shape.** Wi-Fi access points are mounted on the towers and enclosures the site already has.
Enrolled devices associate with them in the ordinary way. Each AP reports which devices are
currently attached. Nobody surveys anything, and nobody ever will.

**The ceiling.** The honest answer for a device is the name of a tower. That is not a degraded
version of the fingerprinting answer — it is a different evidence type, and its limit is set by
physics rather than by effort. An association says a device is within range of one transmitter.
Range is asymmetric, environment-dependent, and routinely larger than any polygon drawn on a plan,
so a device at the edge of coverage is ordinary rather than exceptional.

This deployment is worth building. "Which tower" answered reliably, all day, for every enrolled
device, is a useful operational fact. It just must not be dressed up as a position.

## 1. What the site model has to say

The tower appears twice, and both entries are load-bearing.

| Entry | Why |
|---|---|
| `observers[]` with `observer_device_type: FIXED_OBSERVER` | The tower is the thing *reporting*. Every observation it emits carries its `observer_id`. |
| `infrastructure_nodes[]` with `known_bssid` and `zone_id` | The tower is also the thing whose *coverage* defines the zone. This is what turns "reported by TOWER-2" into "in TOWER-2's zone". |

The `node_id` must match the `observer_id`. That link is what
[`ReferenceModel.source_for_observer`](../positioning-lab/src/rfmapper_lab/models.py) resolves: a
device never reports anything itself, so a row that *measures a device* is keyed into signal space
by the observer's own registered identifier, by reciprocity. Without the infrastructure entry the
tower has no registered identifier, the key is namespaced instead of guessed, it matches nothing,
and the run correctly falls back to building presence.

Each tower's coverage area is a zone. The polygon is a planning figure and nothing in the pipeline
treats it as a measurement — it is used to name the zone and, deliberately, for nothing else.

Observations are `sensor_type: WIFI_ASSOCIATION`, `observer_id` the tower, `radio_identifier` the
device's MAC. Attribution is registry-driven as everywhere else: a MAC that is not on an enrolled
`ManagedDevice` attributes to nothing and produces no estimate
([`17-identity-and-attribution-policy.md`](17-identity-and-attribution-policy.md) §4).

## 2. Choose the engine deliberately

```bash
rfmapper-lab run --raw <packages> --site-model <site> --devices <registry> \
  --zone-engine zone_anchor_v1 --out <derived>
```

The default is `zone_bayes_v1`, which compares against fingerprints. On a site with none it
declines and the run answers `BUILDING` with `NO_ZONE_EVIDENCE` on every row. That is correct
behaviour — it will not quietly fall back to the anchors and it will not invent a zone — but it
looks exactly like a broken installation, so the choice has to be made explicitly.

`zone_anchor_v1` is the classifier for this deployment. It maps a registered anchor's identifier to
that anchor's zone and ranks by signal strength, crudely and on purpose: nothing in it converts a
signal level into a distance.

## 3. What it emits, and what it refuses to

| | |
|---|---|
| `precision_tier` | `ZONE` |
| `method` | `pos_zone_only_v1` |
| `x`, `y` | null |
| `horizontal_uncertainty_m` | null |

`pos_zone_centroid_v1` declines here, and the refusal is the point. The centroid of a coverage
polygon is the transmitter's own position; emitting it would report every device as standing at the
foot of its tower, with a tidy circle around it, on evidence that supports neither the point nor the
radius. A confident zone with null coordinates is the correct and preferred output.

There is therefore no accuracy figure to quote for this deployment, in metres, ever. The measurable
quantity is **tower attribution correctness** — how often the named tower is the right one — which
is a classification question. `zone_accuracy` in
[`12-benchmark-methodology-and-error-metrics.md`](12-benchmark-methodology-and-error-metrics.md) is
already that metric, and it needs ground truth: a walk test recording which tower a device was
genuinely nearest, not a survey.

## 4. The thresholds are calibrated for the other deployment

The movement engine commits a zone transition only when a candidate clears three gates:
`min_candidate_duration_ms`, `min_transition_confidence` and `min_supporting_observations`. Their
defaults are placeholders awaiting a walk test
([`15-assumptions-requiring-validation.md`](15-assumptions-requiring-validation.md)), and they were
chosen with multi-observer RSSI fingerprinting in mind.

Association evidence produces lower confidence than that, structurally: the vector has one observer
because a device has one association, and there is no fingerprint behind the zone. Two factors that
used to penalise exactly those properties no longer do — a corroborating observer is unobtainable
here rather than missing, and survey density says nothing about a negotiated connection. Even so,
the resulting confidence sits below the default `min_transition_confidence` of 0.6, and a site left
on the defaults records which tower each device is on and **no movement between towers at all**.

The run says so rather than leaving it to be discovered:

```
1 observed zone change(s) were not committed as transitions. Gate(s) that stopped them: confidence (1).
  Highest confidence reached was 0.404 against a min_transition_confidence of 0.6. ...
```

The same counts travel in the derived package under `run_statistics.withheld_transitions`. Set the
threshold from a walk test on the real site — drive a device between towers on a known route and
sweep the three parameters against it, as `rfmapper-lab demo` does against a simulated route. Do not
set it to whatever makes yesterday's numbers look better.

Note that `min_candidate_duration_ms` and `min_supporting_observations` remain the right guards
against flapping here. Devices do bounce between APs, and a re-association is a real event that
still should not become a recorded movement on its own.

## 5. If tower granularity is not enough

In rough order of cost, and each needs the measurement before the build:

1. **Have the towers scan as well as report associations.** A device heard by three towers instead
   of associated with one is a genuinely different evidence set: `observer_count` becomes
   informative, and overlapping coverage starts to discriminate between towers. Still no metres.
2. **Survey the areas that matter.** This is the fingerprinting deployment, and it buys
   within-zone position where it is done. It also has to be maintained: a fingerprint is a claim
   about an environment, and environments change.
3. **802.11mc RTT on the towers, if the hardware genuinely supports it.** The only source of real
   ranging in this system. Register a node as `RTT_ANCHOR` *after* obtaining a real range from it,
   not because a datasheet mentions the feature.

What will not work is inferring distance from association signal strength. A calibrated
log-distance model exists in the parameter set as optional weak supporting evidence and is disabled
by default, and free-space propagation is not assumed anywhere in this system
([`10-positioning-mathematical-architecture.md`](10-positioning-mathematical-architecture.md) §6).
