# ShiftWatch Premium v20.0 — Canonical Document Engine

## Smart rota import
- Added a document-first date authority engine. Coherent rota header dates now outrank planner-week assumptions; the old resolver remains a guarded fallback for weak headers.
- Added regression coverage for the real failure class where a 14–20 September rota was incorrectly mapped onto a June/July planner week.
- Added canonical perspective-normalized row coordinates. Each weekday column is piecewise aligned to shared block anchors before semantic time learning, reducing cross-column drift from tilted photos.
- Preserved block-indexed time ownership, high-resolution time atlas, hour/minute focused views, strict cross-row protection, live calibration and learned block-time mappings.

## Performance and stability
- Added import-local grid geometry caching. Global anchors, local row starts and reconstructed block starts are now computed once per immutable AssistData generation instead of repeatedly during taps, review recomposition and diagnostics.
- Kept the v19 structural grid reconstruction that restores missing horizontal rules and aligns each weekday to one canonical block sequence.
- No identity-learning, confuser, resumable-session, verification, duplicate-detection or manual-review behavior was removed.

## Architecture
- Extracted document date reasoning into RotaDateAuthorityEngine, a pure Kotlin component that can be regression tested without Android/ML Kit.
- Extended RotaGridModel into the canonical geometry authority for both block indexing and perspective-normalized Y coordinates.

## Validation
- RotaDateAuthorityEngine compiles standalone with Kotlin.
- RotaGridModel compiles standalone against a minimal AssistData stub.
- Added RotaDateAuthorityEngineTest covering explicit-header authority and bare-day sequence recovery.
- Full Gradle execution remains unavailable in this environment because the Gradle 9.5 distribution cannot be downloaded from services.gradle.org.
