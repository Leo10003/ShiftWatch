# ShiftWatch 18.0.2 — Compile Fix

- Restored the missing `ScheduleImporter.TimeSuggestion` public data class used by `timeSuggestionsForTap()` and the Planner review overlay.
- Fixes the cascading Kotlin errors: generic T/R inference failures, invalid `firstOrNull()` receiver, and unresolved `score` / `time` references.
- Keeps the v18 Structural Time Engine and all v17.2 safety/learning features unchanged.
