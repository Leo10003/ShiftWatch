# ShiftWatch Premium v20.6 — Nonblocking Review & Diagnostics

Based on the user-uploaded v20.5 source, preserving the Android application ID, existing logo, stored-shift schema, widgets, trackers and visual-learning systems.

## Immediate, independent manual input
- Review opens immediately after selecting a photo instead of waiting for the full OCR/time/handwriting pipeline to finish.
- Manual **Add shift** with Material date and 24-hour time pickers works throughout scanning, including before OCR has produced any data.
- **Confirm week** is available independently and does not silently approve uncertain start times; individually manual-entered dates remain trusted for those exact shifts only.
- **Stop analysis** cancels the running photo scan without deleting manual review entries; saving confirmed shifts cancels the scan so late responses cannot reopen the sheet or modify planner state.
- Background image preview decode and matching-safe consistent bitmap coordinate sampling.

## State correctness
- Analysis responses are generation-scoped and ignored after a newer photo, a cancellation, dismissal or a save.
- Pure `RotaReviewMerge` merges late suggestions only for untouched weekdays; explicit manual edits/removals/checkbox approvals cannot be silently overwritten or duplicated across differing candidate weeks.
- **Remove all** suppresses subsequent late suggestions for that photo.
- Manual tap-to-identify returns a placeholder immediately; time labels and ranked suggestions are resolved asynchronously and any stale result is ignored.
- Editor changes, undo/removal and manual confirmations no longer force a document-wide re-verification that could erase other manual corrections.
- `ShiftStore` session saves/clears use a global monotonic revision, preventing slower older serialization from overwriting newer manual checkpoints or resurrecting a cleared import session.

## Opt-in diagnostic export
- New **Export sanitized import diagnostics** action produces local JSON using Android's file picker. It includes session/progress state, header confidence/reason, structural time summaries, geometry counts, anonymous candidate statuses and time-resolution evidence.
- Export intentionally excludes raw OCR text, employee names, image crops/photos and precise calendar dates. The user controls whether and where the file is saved/shared.

## Tests and limitations
- New pure-Kotlin review-merge tests cover late arrivals, user-touched weekdays, removal tombstones, duplicate passes and stale generations.
- Pure date, time, structural and reducer code compiled; UI frontend syntax sweep reported no syntax errors (Android dependencies were not present).
- Android Studio full build, emulator/device concurrency tests and performance benchmarking are **still required**. The OCR engine itself is not a fully streamed stage-by-stage pipeline in v20.6.

See `docs/SHIFTWATCH_PROJECT_MEMORY.md` for the complete development handoff and future roadmap.
