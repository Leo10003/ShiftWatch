# v20.8.17 — Cold-start viewer completion fix

- Fix indefinite “Analyzing…” message on a fresh installation when both saved handwriting profile and direct OCR name matches are absent. The viewer now displays an explicit manual-training instruction and emits diagnostic `savedProfileStatus=no_saved_profile`.
- Explicit terminal statuses for empty OCR (`skipped_no_ocr_regions`), review-only no-hit flows (`skipped_review_only`), and OCR-supported flows that intentionally bypass saved-profile matching (`skipped_ocr_matches`). These are not failed recognition attempts.
- Extend pure status regression tests for first-run, restored-profile, review-only and empty OCR cases.
- Keep all production recognition ranking, crop gates, learned data and shift confirmation unchanged. No app data reset is required.
- Based on tools-only v20.8.16 source. v20.8.17 raises Android versionCode to 227 and versionName to 20.8.17. Validate in Android CI and on a fresh isolated test environment before considering fixed on-device.
