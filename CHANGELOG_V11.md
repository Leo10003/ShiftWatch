# ShiftWatch Premium v11.0 — RotaVision Verification Engine

## Major changes

- Separate hypothesis and verification stages.
- Document graph describing weekday nodes, time bands, and candidate shifts.
- Explicit contradiction detection for same-day conflicts, date/column disagreement, weak image quality, learned-time disagreement, and weekly outliers.
- Conservative verification states: CONFIRMED, HIGH_CONFIDENCE, REVIEW, UNRESOLVED, CONFLICT.
- Conflict/unresolved drafts can no longer auto-select for planner import.
- Stable rota fingerprinting and duplicate-import warnings.
- Resumable review sessions persist drafts, document geometry, verification state, correction state, and the image reference across activity/process recreation.
- Correction-specific local event history for wrong day, wrong time, and false-shift corrections.
- Diagnostics expose graph candidates, time bands, conflicts, fingerprint, and correction-event count.
- User-confirmed corrections become immutable confirmed evidence in the current review.
- v10 template library, time vocabulary, image quality scoring, hard negatives, prototype diversity, and v9.2 preview stability are retained.
- Added regression tests for verification conflicts, high-confidence structural agreement, and fingerprint stability.

## Neural roadmap

No untrained neural model is bundled. The verifier is designed to accept future TFLite/ONNX handwriting and time-recognition signals as additional evidence without allowing one model to own the final answer.
