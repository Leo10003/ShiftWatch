# ShiftWatch 19.3.1 — Compile Fix

- Fixes Kotlin `val cannot be reassigned` in the v19.3 authoritative rota-date candidate collector by replacing `+=` with explicit `MutableList.add`.
- No recognition or UI behavior changed from v19.3.
