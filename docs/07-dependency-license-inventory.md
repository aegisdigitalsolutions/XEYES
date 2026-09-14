# Deliverable G — Dependency and license inventory

Policy: **permissive licenses only** (Apache-2.0, MIT, BSD, PSF). No GPL or LGPL in any shipped
artifact, because RFMapper is private software distributed internally as binaries. No dependency
enters the build without appearing in this table first.

Versions are pinned in [`../android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml)
and [`../positioning-lab/pyproject.toml`](../positioning-lab/pyproject.toml).

## 1. Android runtime dependencies

| Dependency | Version | License | Used for | Notes |
|---|---|---|---|---|
| Kotlin stdlib | 2.0.21 | Apache-2.0 | language runtime | |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | concurrency, `Flow` | |
| kotlinx-serialization-json | 1.7.3 | Apache-2.0 | canonical JSON | Chosen over Gson/Moshi: compile-time, reflection-free, no `@Keep` interaction with R8, and explicit about unknown keys — which the schema requires us to preserve. |
| kotlinx-datetime | 0.6.1 | Apache-2.0 | ISO-8601 UTC handling | Avoids `java.time` desugaring differences below API 26 and is multiplatform-ready. |
| androidx.core:core-ktx | 1.13.1 | Apache-2.0 | platform extensions | |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | Compose host | |
| androidx.lifecycle (runtime/viewmodel/compose) | 2.8.7 | Apache-2.0 | lifecycle, ViewModel | |
| androidx.compose BOM | 2024.10.01 | Apache-2.0 | UI toolkit | BOM aligns all Compose artifacts |
| androidx.compose.material3 | via BOM | Apache-2.0 | UI components | |
| androidx.navigation:navigation-compose | 2.8.3 | Apache-2.0 | in-app navigation | |
| androidx.room (runtime/ktx/paging) | 2.6.1 | Apache-2.0 | persistence | Required by spec |
| androidx.paging:paging-compose | 3.3.4 | Apache-2.0 | observation browser paging | 100k+ row tables must not be loaded whole |
| androidx.work:work-runtime-ktx | 2.9.1 | Apache-2.0 | deferrable export/housekeeping | Not used for collection |
| androidx.documentfile | 1.0.1 | Apache-2.0 | SAF tree writing for exports | |
| androidx.datastore:datastore-preferences | 1.1.1 | Apache-2.0 | observer identity, settings | Replaces SharedPreferences; no main-thread I/O |
| com.google.dagger:hilt-android | 2.52 | Apache-2.0 | dependency injection | Makes providers swappable for fakes, which is how the JVM tests run |

### Build-time only

| Dependency | Version | License | Used for |
|---|---|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | build |
| Gradle | 8.13 | Apache-2.0 | build |
| KSP | 2.0.21-1.0.28 | Apache-2.0 | Room + Hilt codegen |
| Compose compiler plugin | bundled with Kotlin 2.0.21 | Apache-2.0 | Compose |

### Test only

| Dependency | Version | License | Used for |
|---|---|---|---|
| kotlin-test / JUnit4 | 2.0.21 / 4.13.2 | Apache-2.0 / EPL-1.0 | unit tests (test-scope EPL is acceptable: not shipped) |
| kotlinx-coroutines-test | 1.9.0 | Apache-2.0 | `Flow`/coroutine tests |
| androidx.room:room-testing | 2.6.1 | Apache-2.0 | migration tests |
| androidx.test.ext:junit, espresso | 1.2.1 / 3.6.1 | Apache-2.0 | instrumented tests |
| Robolectric | 4.13 | MIT | JVM tests for the few Android-touching classes |

## 2. Positioning Lab dependencies

| Dependency | Version | License | Used for | Justification |
|---|---|---|---|---|
| numpy | ≥1.26 | BSD-3-Clause | vectorized math | Unavoidable and appropriate |
| pandas | ≥2.1 | BSD-3-Clause | tabular ingest/quality analysis | CSV/JSON ingest and group-by quality statistics |
| scipy | ≥1.11 | BSD-3-Clause | `least_squares` for RTT multilateration, statistics | Only genuinely needed for the optimizer and distributions |
| scikit-learn | ≥1.3 | BSD-3-Clause | KNN baselines, train/validation/test splitting, metrics | Interpretable baselines, not deep learning |

### Deliberately **not** adopted (and why)

| Candidate | License | Decision |
|---|---|---|
| FilterPy | MIT | **Deferred.** A Kalman filter is only justified once the benchmark shows the rolling-median/EMA baseline is the limiting factor. The temporal-filter interface is in place so it can be added without restructuring. |
| Shapely / GeoPandas | BSD-3 / BSD-3 | **Deferred.** Zone polygons are currently simple and point-in-polygon is ~20 lines with a ray-cast. Adopt when the site model needs real geometry (holes, unions, projections). |
| TensorFlow / PyTorch | Apache-2.0 / BSD-3 | **Rejected for V1.** Both specifications forbid adopting deep learning merely because it exists. Revisit only if validation demonstrates a meaningful, reproducible gain over calibrated statistical baselines. |
| statsmodels | BSD-3 | Not needed; scipy covers the required distributions. |
| hmmlearn | BSD-3 | **Deferred.** The HMM zone-transition model is a Phase-12 candidate; the hysteresis state machine is the baseline. |

## 3. Dependencies avoided in the Android build

| Candidate | Why avoided |
|---|---|
| Google Play Services Location | Adds a proprietary dependency and a fused-location abstraction that hides the raw GNSS accuracy the schema needs. Platform `LocationManager` is sufficient and honest. |
| Firebase / any analytics | This is a private surveillance-adjacent system; exfiltrating any observation metadata to a third party is unacceptable. Zero network dependencies in the Collector. |
| A charting library | Visualization (Milestone 5) draws on Compose `Canvas`. A site map with custom projection and uncertainty regions is not a chart. |
| Retrofit / OkHttp | V1 has no server. Adding an HTTP client would create an unused attack surface. |
| Gson / Moshi | See kotlinx-serialization rationale above. |

The Collector ships with **no networking permission at all** (`android.permission.INTERNET` is not
declared). That is the strongest available guarantee that field observations cannot leave the device
except through a deliberate, administrator-initiated file export.

## 4. License compliance process

1. **Gate:** a new dependency requires a row in this table, including license and justification.
2. **Automated inventory:** `./gradlew :collector-app:licenseReport` (Gradle license plugin, to be
   added in Milestone 2) emits the resolved dependency/license list; the report is committed under
   `docs/generated/` and reviewed on change.
3. **Attribution:** both apps ship an in-app "Open source licenses" screen generated from that report.
   `NOTICE` files from Apache-2.0 dependencies are reproduced verbatim.
4. **No vendored code.** If code must be adapted from a third-party project, it goes in a clearly
   marked file with the original license header and an entry in
   [`08-open-source-reference-inventory.md`](08-open-source-reference-inventory.md). At present
   **no third-party source code is vendored.**
5. **Transitive review:** `./gradlew dependencies` output is checked for license changes on any
   version bump, because a permissive direct dependency can pull a copyleft transitive one.
