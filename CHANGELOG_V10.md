# ShiftWatch Premium v10.0 — Adaptive Rota Intelligence

## Smart Rota Import architecture
- Added image-quality assessment for contrast, sharpness and grid completeness, with user-facing diagnostics.
- Added rota/template fingerprints so learned layouts can be matched instead of blindly reused.
- Upgraded persistent template storage from one layout to a library of up to five learned rota layouts per employee, with migration from v9 storage.
- Added template similarity scoring and best-template selection.
- Added learned workplace shift-time vocabulary. Confirmed imported shifts feed their start times back into future rota analysis.
- Added explicit multi-signal evidence fusion. Identity evidence can no longer produce verified confidence when geometry or time evidence contradict it.
- Added vocabulary-aware time ranking as a soft prior; novel times remain possible when current-image evidence is strong.
- Preserved three-tier uncertainty behavior so unresolved data is never silently converted into a confident planner entry.

## Adaptive handwriting model
- Reworked profile merging to keep diverse handwriting prototypes instead of filling the model with near-duplicate samples.
- Hard negatives are prioritized by similarity to confirmed employee handwriting, improving separation from confusing coworker names.
- Existing positive/negative profile compatibility is preserved.

## Review and diagnostics
- Analysis details now expose image quality, warnings and learned shift times.
- User-confirmed imported times are retained as local learning events.
- Existing preview-stability fixes from v9.2 are retained.

## Regression protection
- Added evidence-fusion tests verifying that poor geometry caps confidence and contradictions lower confidence.
- Added multi-template selection regression coverage.

## Build note
The source was structurally checked in the packaging environment. The full Gradle test/build could not run because the Gradle 9.5 wrapper distribution cannot be downloaded from services.gradle.org in this environment. Run Build > Make Project (and the unit tests) in Android Studio before installing on a phone.

## Neural handwriting model note
This patch does not bundle an untrained or fabricated neural model. The local handwriting system is strengthened with prototype clustering and hard-negative mining. A future embedding-model upgrade should only be shipped with a genuinely trained and regression-tested model asset.
