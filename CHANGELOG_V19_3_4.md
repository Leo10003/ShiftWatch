# ShiftWatch v19.3.4 — Compile Fix

## Fixed
- Fixed Kotlin smart-cast failures caused by nullable `assistData` being a delegated Compose state property.
- `ImportReviewSheet` now takes a stable local `currentAssistData` snapshot for each render branch before passing the data into schedule review, verification, date resolution and column resolution.
- Live calibration still writes updates back to the mutable Compose `assistData` state through `onAssistDataChanged`; recognition behavior is unchanged.

## Notes
- This patch only addresses compilation/state-access safety. It does not remove or weaken the v19.3 date/time authority, structural time solver, identity learning, or calibration logic.
