# ShiftWatch 16.0 — Engine Architecture & Semantic Time Bands

This release begins the architecture/performance phase identified in the v15 code review while also improving the weak time-recognition path.

## Import lifecycle and stability
- Added `ScheduleImportCoordinator`, a cancellation-aware coroutine entry point for Smart Rota Import.
- Import startup and image decode now begin on `Dispatchers.IO` instead of directly in the Compose callback.
- Picking a second rota cancels the previous import's right to publish results, preventing stale analysis from replacing a newer review screen.
- The existing recognition engine is intentionally preserved behind the coordinator while the large importer is split incrementally in later versions.

## Semantic time-band intelligence
- Added `RotaSemanticTimeBands`.
- Time hypotheses are clustered by normalized vertical position and weekday support.
- Repeated rows become semantic bands such as `09:30 @ 31%`, independent of one OCR token.
- Existing template `canonicalTimes` are reused as learned structural priors on later rota photos.
- Strong fresh evidence beats a learned prior; weak/ambiguous OCR can fall back to a high-quality learned row instead of hallucinating a new time.
- Semantic bands are persisted in resumable import sessions and shown in Analysis details.

## Reliability
- Duplicate rota fingerprints no longer depend on learned time vocabulary or the selected template fingerprint. The same document remains the same document as ShiftWatch learns.
- Added semantic-band regression tests, including noisy 09:30 consensus and recovery from an unreadable future image.

## Build note
The Gradle wrapper still requires Gradle 9.5 from services.gradle.org. This environment cannot resolve that host, so a complete Android Gradle build/test run could not be performed here. Standalone Kotlin compilation of the new semantic-time engine succeeded and source syntax was checked for parser errors.
