# v20.8.8 — Tuesday provenance and offline accuracy baseline

- Add a sanitized, user-confirmed weekday/physical-block baseline for the reference September 7 rota. It explicitly separates identity-location suggestions from unverified time/date OCR.
- Add `tools/check-reference-accuracy.ps1`: offline, native PowerShell accuracy evaluation for one or more diagnostic exports. It reports working-day hits, Tuesday recovery and Friday false positives, and returns a nonzero exit code only for regression versus the known baseline or malformed inputs. No Python required. Improvements to Tuesday are allowed.
- Diagnostic schema 9 adds `eligibleOcrTokensByBlock`, `ocrMergedByBlock` and `ocrAddedByBlock` to the existing weekday `candidatePipeline`. They are aggregate counts only; no text, crop coordinates, images, names or exact candidate IDs are exported.
- Add JVM tests for aggregate bucket invariants. The v20.8.7 matcher, scoring floors, saved-profile persistence and review-confirmation policy remain unchanged.

A schema-8 report can still be evaluated for accuracy; schema-9 exports supply the additional Tuesday block-level provenance. Full Android CI must pass before field deployment. Do not reset the emulator or saved handwriting profile.
