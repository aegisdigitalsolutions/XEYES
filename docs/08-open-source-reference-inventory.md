# Deliverable H — Open-source projects worth studying, and their licenses

The master prompt requires studying appropriately licensed prior art before writing scanners from
scratch, and forbids copying incompatible-license code.

**Current state: no third-party source code is vendored or adapted in this repository.** Everything
below is a *reference* — read for technique, platform quirks and pitfalls. Any future adaptation must
add a row to §4 with the file, the upstream commit and the license header.

## 1. License compatibility assessment

| Project | License | Copy code? | Read for technique? |
|---|---|---|---|
| WiGLE WiFi Wardriving (Android) | Apache-2.0 (app source historically mixed; verify per file) | Only with per-file license verification | Yes |
| NeoStumbler | **GPL-3.0** | **No — incompatible** | Yes, ideas only |
| Android WifiRttScan sample (`android/connectivity`) | Apache-2.0 | Yes, with attribution | Yes |
| AltBeacon `android-beacon-library` | Apache-2.0 | Yes, with attribution | Yes |
| RadioBeacon / openbmap | GPL-3.0 | **No** | Ideas only |
| Nordic Android BLE Library | BSD-3-Clause | Yes, with attribution | Yes |
| FIND3 | MIT | Yes | Yes |
| ESP32/Kontakt positioning samples | various | case by case | Yes |
| `scikit-learn`, `scipy` source | BSD-3-Clause | Yes | Yes |

**The GPL boundary is the important line here.** NeoStumbler and RadioBeacon are the closest
functional analogues to the Collector, and both are GPL-3.0. Reading them to learn *that* a problem
exists (e.g. how Android 29 throttling manifests) is fine. Copying their solution into this private,
binary-distributed app would impose GPL obligations the project cannot meet. Notes taken from those
projects must be observations about the *Android platform*, not about their code.

## 2. What to learn from each

### WiGLE WiFi Wardriving — *the reference for sustained Android Wi-Fi collection*
- Broadcast-driven scan collection and the practical consequences of throttling across API levels.
- Long-session battery management and database write batching at field scale.
- Distinguishing genuinely new scan results from repeated cached ones.
- **Taken as technique:** broadcast-driven rather than poll-driven collection (independently
  implemented in `AndroidWifiObservationProvider`), and batched writes.

### NeoStumbler (read-only, GPL-3.0) — *modern Compose/Room/Hilt-era stumbler*
- How a current-generation Android app structures permission onboarding for 31/33/34 changes.
- Foreground-service configuration for `foregroundServiceType="location"` on 34+.
- **Taken as knowledge:** the existence and ordering of the background-location two-step request.
  No code, no structure, no strings.

### Android `WifiRttScan` official sample — *the RTT reference*
- `WifiRttManager` + `RangingRequest` lifecycle, `RangingResultCallback`, status codes.
- Dynamic `PackageManager.FEATURE_WIFI_RTT` detection and the 802.11mc responder check
  (`ScanResult.is80211mcResponder`).
- The per-request limit on peers (`RangingRequest.getMaxPeers()`), which dictates batching.
- **Apache-2.0, so directly adaptable if needed.** Currently reimplemented rather than copied.

### AltBeacon `android-beacon-library` — *BLE at field scale*
- Advertisement parsing layouts (iBeacon/Eddystone/AltBeacon) and why a generic parser is preferable
  to hard-coded offsets.
- Scan-period cycling to survive Android 7+ scan-restart limits on long BLE sessions.
- Distance estimation from RSSI **and its documented unreliability**, which is direct support for the
  specification's insistence on fingerprints over path-loss formulas.

### Nordic Android BLE Library — *BLE correctness*
- Defensive handling of adapter state transitions and scanner restart after Bluetooth toggles.
- `ScanSettings` trade-offs (`SCAN_MODE_LOW_LATENCY` vs battery) and report-delay batching.

### FIND3 (MIT) — *end-to-end fingerprint positioning*
- A working RSSI-fingerprint pipeline with a classifier ensemble, and honest accuracy reporting.
- Its per-device-family calibration problem is exactly our `ref_observer_calibration`.
- Useful as a sanity reference for expected indoor accuracy: room-level classification is reliable;
  metre-level coordinates from Wi-Fi RSSI alone generally are not.

### scikit-learn / scipy
- `KNeighborsClassifier` weighting semantics and `GroupKFold`-style splitting, which is the right
  pattern for splitting ground-truth surveys by *survey session* rather than by sample (§
  [`12-benchmark-methodology-and-error-metrics.md`](12-benchmark-methodology-and-error-metrics.md)).
- `scipy.optimize.least_squares` with a robust loss for RTT multilateration.

## 3. Published research worth reading (technique, not code)

| Topic | Reference | Why |
|---|---|---|
| Wi-Fi fingerprinting baseline | Bahl & Padmanabhan, *RADAR* (2000) | The original nearest-neighbour-in-signal-space method; our baseline algorithm |
| Probabilistic fingerprinting | Roos et al., *A Probabilistic Approach to WLAN User Location Estimation* (2002) | Basis for the Bayesian matcher |
| Horus | Youssef & Agrawala (2005) | Clustering + correlation handling; why per-AP independence is optimistic |
| Zone/HMM smoothing | Wallbaum & Wasch, HMM-based WLAN tracking | The deferred Phase-12 candidate |
| RSSI propagation reality | Kaemarungsi & Krishnamurthy, *Properties of Indoor Received Signal Strength* (2004) | Empirical evidence that free-space models fail indoors — directly cited by our assumptions list |
| Uncertainty reporting | Any work reporting CDF / P90 rather than mean error | Why deliverable 12 forbids reporting mean error alone |

## 4. Adapted-code register

| File | Upstream project | Upstream license | Commit / version | Notes |
|---|---|---|---|---|
| *(none)* | | | | No third-party source is currently vendored or adapted |

Rules for adding a row:
1. Verify the license **per file**, not per repository. Mixed-license repositories are common.
2. Preserve the original copyright and license header verbatim.
3. Record the exact upstream commit so the provenance is auditable.
4. Note every modification in the file header.
5. Reject the adaptation outright if the license is GPL/LGPL/AGPL.
