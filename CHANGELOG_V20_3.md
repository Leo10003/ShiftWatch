# ShiftWatch Premium v20.3 — Reliability Recovery

## Safety and selection
- Separates **importable** shifts from **safe to auto-select** shifts.
- Handwritten and mixed-rota detections are review suggestions by default and are never silently preselected for planner import.
- Only high-confidence printed-table rows can auto-select themselves.
- Manually selecting a ready suggestion remains easy through the existing checkbox / Select ready workflow.

## Date authority
- Reworked OCR-text week recovery to require an **ordered consecutive calendar sequence** instead of mining an unordered bag of date-like strings.
- A real header run such as `21.09 → 27.09` now overrides a poisoned planner/fallback week.
- Unrelated explicit dates elsewhere in OCR text can no longer hijack the rota week.

## Identity recall
- Mature handwriting profiles now recover softer weekday matches at a lower review threshold while still requiring runner-up and confuser separation.
- Recovered matches remain review suggestions; they do not become automatic planner selections.

## Start-time recognition
- Specialist time parsing now normalizes Unicode superscript/subscript digits before recognition.
- Handwritten forms such as `9³⁰` and `16⁰⁰` are therefore interpreted by the same time engine as normal digits.
- Structural row ownership remains authoritative, so the change does not re-enable cross-row time borrowing.

## Stability / ANR protection
- Adds a single-flight interaction guard to the rota viewer.
- Repeated taps during handwriting analysis are ignored instead of queueing more expensive recognition work.
- Adds a short tap throttle to prevent accidental burst interactions.
- Prevents the saved-profile matcher and text-bootstrap matcher from starting concurrently during first composition.
- Disables starting/resuming another photo import while a current import is active.

## Regression coverage
- Adds tests for ordered header-date recovery with a deliberately wrong fallback week.
- Adds a regression ensuring unrelated dates cannot outrank the real header run.
- Adds superscript-time recognition coverage for `9³⁰` and `16⁰⁰`.
