# ShiftWatch v20.8.1 — Recognition Investigation (diagnostic-only)

Source-only patch against **v20.8.0 Evidence Baseline**. Do not replace the existing GitHub checkout or use `git init` locally. This version does **not** change the recognition thresholds, learned profile, automatic planner insertion policy, date resolution policy, saved user data or work-shift times.

## What the user's v20.8 diagnostic established

- Scan reported **21,730 ms** (one measurement, not a reliable speed benchmark).
- 5 viewer suggestions were present on Monday, Tuesday, Wednesday, Saturday and Sunday. The user's confirmed Thursday shift (16:00) was not marked.
- 6 user-confirmed reference shifts: Mon 09:30, Tue/Wed/Thu/Sat/Sun 16:00; Friday off. Ground-truth week begins 2026-09-07.
- The app still fell back to the planner week; all four date paths were non-authoritative. B1 and B3 showed weak 09:00 hypotheses with 09:30 and 16:00 alternatives, respectively. Unconfirmed B2 13:00 may belong to other workers.
- Review drafts and viewer suggestions are different collections; `candidates: []` does not negate 5 visible marker suggestions.

## Changes

1. Diagnostic schema 3 reports **per-weekday saved-profile and seeded-visual matcher decisions**, including count of candidate lines, successful signatures, best and runner-up score, acceptance threshold, confuser score, and a *diagnostic reason for the final policy outcome*. The existing matcher acceptance behavior remains unchanged. When that matcher was not run, the export explicitly says `not_attempted` rather than inventing rejection details. A seeded training run also updates the trace.
2. Each viewer marker carries its origin (`ocr_name`, `saved_profile`, `seeded_visual_search`, `learned_visual_search`, or manual selection), weekday index, physical-block index (if geometry permits), and a score *decile*; export includes no handwriting coordinates, crops, names or OCR text. Existing suggestion counts remain unchanged.
3. Export *weekday distributions* of readable header observations and explicitly shaped date observations, and compare the relative evidence support for **planner fallback versus the displayed week** (particularly informative after explicitly choosing the true week). It includes match counts, not the calendar dates or OCR strings.
4. Export individual, sanitized **time hypotheses before structural consolidation**: physical block, weekday, proposed time, atlas-versus-page source, strong/alternate flags, confidence decile and geometry eligibility/rejection reason. This can reveal whether B1/B3 09:00 derives from duplicated token evidence or genuine distinct labels. The export reuses the existing candidate collector and mirrors the solver's geometry filters; no time inference rule changes.
5. Bump Android app code to 212 and version to 20.8.1. Extend pure Kotlin unit tests for per-week week evidence and a five-of-six marker diagnostic case.

## How to capture the useful next export

1. On the same device/profile as the v20.8 scan, import the same photograph and allow the scan to complete.
2. Enter the actual Monday **2026-09-07** in the rota week selector and confirm it. Do not guess or edit OCR evidence. Do not force any incorrect automated time into the planner.
3. Open the image viewer, wait for handwriting suggestions, then return to the review screen. If useful, tap Thursday **only after the first diagnostic export**, so the missing-day evidence is preserved. You can export a second JSON after training.
4. Use **Export sanitized import diagnostics** and upload that JSON. Include the confirmed-example JSON only if you changed the ground truth.
5. Inspect `viewerMarkers.savedProfileStatus`, `viewerMarkers.seededSearchStatus`, `savedProfileDecisions`/`seededVisualDecisions` for weekday column **3** (Thursday); `viewerMarkers.markerDetails`; `weekEvidenceComparison`; and `timeCandidateTrace` for physical block indices 0 and 2.

## Validation limits

- Pure Kotlin compilation and local source-level smoke assertions verify new date-fit, fallback, marker-summary semantics.
- Regression metadata validator passes. Full Android Gradle `assembleDebug`, unit-test suite and lint are **not run in this container**, which lacks an Android SDK installation. GitHub Actions must be green before installing this build. No claimed accuracy or performance improvement until on-device regression is measured.
- The matcher decision labels are instrumented for the principal *per-column winning candidate*, not every pixel crop: if Thursday's intended row was never segmented but other rows were, `candidateLineCount` can be nonzero. Full row-level pixel explanation would require private/redacted image evidence and additional consented instrumentation.
