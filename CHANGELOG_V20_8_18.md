# ShiftWatch v20.8.18 — viewer lifecycle hardening

Android follow-up to v20.8.17. Diagnostic schema remains 15; versionCode 228, versionName 20.8.18.

- Catch unexpected OCR name-lookup errors and display an explicit manual-selection/retry message rather than leaving the initial analyzing message indefinitely. Store `failed_ocr_name_lookup` in viewer diagnostic status. Cancellation is propagated, not mislabelled as a recognition error.
- Release the viewer-local `visionBusy` flag in the recognition coroutine's outer `finally`, including cancellation while seeded visual search is running. This is defensive lifecycle cleanup, not a change to matching rules.
- Add pure Kotlin status regression for the OCR lookup failure path. Existing tests for fresh install, review-only, and no OCR regions remain.

No changes to handwriting scores, acceptance thresholds, week/time inference, export schema, confirmed shifts, or saved profiles. This does not prove every possible viewer hang is fixed; Android CI, fresh-profile and actual interruption tests are still necessary. Preserve your current emulator training data; use a separate test profile for a cold-start test.
