# ShiftWatch 18.0.1 — Compile Fix

- Fixed Kotlin 2.3 type-inference failures introduced by the v18 structural-time integration.
- Replaced inference-heavy structural solver collections with explicit typed maps/lists.
- Reworked canonical-time voting to use explicit typed vote objects.
- Replaced an untyped `fold(mutableListOf())` in time-marker deduplication with a typed imperative pass.
- Removed the unused `BoxWithConstraints` scope warning without changing viewer behavior.
- Preserves the v18 Structural Time Engine and all prior identity, verification, preview, learning, resume, duplicate-detection and manual-review features.
