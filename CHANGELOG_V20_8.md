# ShiftWatch v20.8.0 — Evidence Baseline (diagnostics and regression case)

Built as an incremental patch against the exact provided v20.7.2 release source. **Apply only the Git patch to the existing `develop` checkout.** The user's GitHub branch additionally contains the API-27 theme lint fix; this patch intentionally does not touch theme resources or Gradle workflow files.

## Verified user ground truth
- Monday date: 2026-09-07 (the app displayed a *non-authoritative* planner fallback of 2026-09-21).
- Real shifts: Mon 09:30; Tue/Wed/Thu/Sat/Sun 16:00. Friday: no shift.
- User reports five correctly marked name locations (Mon/Tue/Wed/Sat/Sun), missed Thursday; this is not an automatic accuracy benchmark without a labeled/redacted image.
- Previous sanitized diagnostic showed 27,145 ms total, an unresolved 48%-confidence fallback date, B1 09:00 vs 09:30 and B3 09:00 vs 16:00, with `candidates: []` despite visible handwriting markers. Time bands cannot be labelled wrong without mapping to actual physical blocks.

## Implementation in this patch
1. New `RotaDiagnosticEvidence` pure Kotlin data model with seven-column aggregate handwriting suggestion/confirmation counts, plus independent date-resolution summaries. Not opening the viewer yields `not_observed_viewer_not_opened` rather than incorrectly reporting 0 suggestions.
2. Hoist viewer marker summaries into review state so an export performed *after closing the viewer* still contains the last observed suggestion count per weekday. Blue suggestions remain separate from reviewed drafts.
3. Raise sanitized diagnostic schema to 2. Export stage times, candidate counts/status, marker totals per weekday, manual week-override flag, offsets *relative to* the planner fallback, and independently run all-token, focused-header, text-sequence and actual selected date resolver hypotheses. No actual week date, employee name, original OCR text, image or marker coordinates are included in this export.
4. Make uncertain planner-fallback weeks explicitly require opening the date picker before bulk confirmation; a tempting “Confirm week” action can no longer silently approve a guessed wrong week. Preserve the one-tap route for genuinely authoritative headers.
5. Export structured per-block time evidence (selected/competing times, relative scores, independent support columns, atlas support, reason and abstention flag), not just human-readable band strings.
6. Add the user's six-day schedule as a metadata-only regression fixture (`reference-september-07-2026.json`); add focused date resolver regression for seven explicit Sept 7–13 header columns versus wrong Sept 21 fallback, a safe unverified fallback test, and summary semantics tests.
7. App version 20.8.0 / code 211 to distinguish this diagnostic build in device screenshots and exported files.

## What this does NOT claim
- Does not repair date OCR for the specific photographed rota: original OCR text or privacy-approved header evidence is missing; changing thresholds from sanitized confidence alone would be guessing.
- Does not train a neural handwriting model or claim Thursday now matches.
- Does not claim full instrumented Android build/phone performance tests. GitHub Actions must verify Android compile, full unit suite, lint and app behavior using the existing quality gate.
- Does not alter manual time/date truth, the recognized suggestions approval policy, planner data, application ID, widget or offline operation.

## Next device validation
Scan the *same* image, open **Select shifts** and wait for blue markers to appear, then press Done and **Export sanitized import diagnostics**. Confirm `viewerMarkers.status == "observed_in_viewer"`, the blue-marker count matches the UI, `review.draftCount` matches the review cards, and `dateHypotheses` shows exactly where the geometry/header/text paths disagree. Export contains no names/raw OCR/images. Record any name match differences; do not treat the fixed regression fixture as proof the real image succeeds.

Metadata validation now checks declared no-shift weekdays and disallows duplicate exact weekday/start-time pairs.
