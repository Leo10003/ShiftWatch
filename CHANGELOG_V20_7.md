# ShiftWatch Premium v20.7 — Responsive, evidence-safe review foundation

Baseline: user-uploaded **v20.6.1** Android Studio source. Package/application ID and existing tracker/widget/branding code retained.

## Highest-priority fixes
- **Review immediately accessible:** no automatic full-screen rota dialog on photo selection. The user can add/edit/remove and save explicitly confirmed shifts immediately; the image viewer opens only by user action.
- **Stage-aware, cancellable analysis:** OCR reports named stages and emits provisional header/layout information before the slow high-resolution passes finish. A generation-keyed session reducer rejects callbacks from an old photo or canceled import. Timings per completed stage are included in the opt-in sanitized diagnostic JSON.
- **Explicit evidence gates:** date, time, identity, conflict, and selected-for-import are evaluated independently. Uncertain candidates remain CHECK NAME/CHECK DATE/CHECK TIME instead of appearing READY merely because some other evidence was strong.
- **One-tap week confirmation** is visible both next to the blocked review list and above the save button. It confirms ONLY the calendar week, never the name or start time.
- **Typed human evidence:** saved drafts now carry explicit user-confirmed identity/date/time flags and physical block IDs. Old session JSON is still readable using default values and legacy verification notes.
- **Human edits survive corrections:** updates to the inferred document week rebase only non-manually-dated candidates, unselect changed dates, and leave manual calendar entries unchanged. Persist explicit week confirmation, selected IDs, per-weekday override tombstones, and remove-all suppression in resume checkpoints. Resume retains manually confirmed handwritten drafts.
- **Multiple shifts on one weekday:** candidate merging deduplicates per weekday/time slot instead of throwing away all subsequent candidates on a day. Manual additions reject overlapping intervals but permit two non-overlapping shifts on one date. Tap-assisted identity matching distinguishes physical block IDs on the same weekday.
- **All-or-nothing planner saving:** reviewed import validates the whole selection for overlaps before writing. Import/optional template updates are dispatched off the UI thread. Pending checkpoint writes are paused on save and permanently invalidated on a successful commit so a late write cannot resurrect a completed import.

## Viewer and responsiveness
- Fit Page and true Fit Width modes share the same Canvas image/marker/touch coordinate transform. Fit Width supports vertical pan at 1x; double-tap zoom is anchored at the touched location. Diagnostics remain optional.
- Viewer handwriting jobs share one bounded worker; checkbox/time edits don't restart saved-profile scans. The identity proposal list now preserves different physical block suggestions on the same weekday rather than taking only one match per column.
- Added a conservative boundary guard: points close to uncertain horizontal row rules remain unresolved instead of being assigned a neighboring block's time.

## Accuracy and long-term learning
- Corrected three previously failing pure tests: coherent recent bare-date header proposal (not authoritative), compact bare `900` remains ambiguous, and repeated contradiction-free block calibrations can produce a confident *solver hypothesis*. A prior-only hypothesis **cannot auto-assign a time** without at least one fresh current-image observation.
- Added a pure objective precision/recall evaluator and a separate, opt-in export of the user's explicitly confirmed weekday/time/block labels without names, photos or raw OCR text. This is scaffolding for rigorous handwriting/model benchmarking, **not a trained neural model**.

## Validation and limitations
- The targeted nine pure-Kotlin test classes now pass in a local JUnit-compatible harness (31/31), with 11 additional focused synthetic regressions run earlier in this patch cycle. Kotlin PSI parses all source/test files without syntax errors.
- This environment does not provide a verified Android SDK/Gradle device build. Use Android Studio Build > Make Project and test the real photo, week confirmation, manual entry during slow scanning, resume, widget tracking, and saving twice before treating this as production-ready.
- A neural handwriting embedding model, full ViewModel/StateFlow migration, physical-device latency/ANR certification, and real-photo perspective rectification remain validation/data-dependent work; these are not claimed complete in this release.
