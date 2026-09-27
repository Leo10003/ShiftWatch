# ShiftWatch Premium v20.2 — Resolver Correction & Shift Management

## Resolver correctness

- Document dates are propagated into review drafts before verification and planner import.
- Added a geometry-independent explicit date-sequence fallback so coherent `DD.MM` headers can recover the actual rota week even when OCR boxes are unreliable.
- Review/resume paths use the same document week authority as fresh imports.
- When rota dates override the planner week, the review UI states this explicitly.
- Manual week selection remains an intentional user override.

## Duplicate-import correctness

- Duplicate fingerprints are now week-aware (`document fingerprint + resolved week`).
- A rota is marked as imported only after at least one shift is actually committed to the planner.
- A review whose candidates are all rejected by planner validation remains retryable.

## Employee recall

- Added conservative mature-profile recovery for well-trained handwriting profiles that unexpectedly collapse to one or two first-pass matches.
- Recovery still requires clear separation from the weekday runner-up and learned confusers; recovered candidates remain review-grade suggestions.

## Selected-shift management

- Added a dedicated **Manage detected shifts** section.
- **Clear selection** deselects all automatic choices without deleting candidates.
- **Select ready** selects only import-safe candidates.
- Every candidate now has explicit **Edit** and **Remove** actions.
- **Undo remove** restores the most recently removed candidate.
- **Remove all** clears all detected candidates so the user can rebuild the review manually from the rota.
- Automatic choices are explicitly presented as suggestions; nothing is committed until the final add action.

## Data integrity

- `ShiftStore.addPlanned` now reports the shifts actually inserted, allowing import bookkeeping to reflect real commits rather than requested candidates.

## Compatibility

All v20.1 stability, cancellation, canonical grid, verification, time-atlas, adaptive identity, calibration, resume, and preview behavior is retained.
