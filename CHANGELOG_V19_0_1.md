# ShiftWatch 19.0.1 — Compile Fix

- Fixed the v19 compile error in `ZoomableRotaImage` by explicitly passing the existing `ShiftStore` instance into the composable.
- This resolves `Unresolved reference: store` in confirmed block-time learning.
- The associated Kotlin generic `R` inference failure was a cascade from that unresolved receiver and is resolved by the same fix.
- No recognition, structural-time, identity-learning, or review behavior was removed or changed.
