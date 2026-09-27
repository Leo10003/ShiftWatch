# ShiftWatch Premium v13.0 — Adaptive Identity Engine

v13 concentrates on employee-identity learning rather than adding more OCR heuristics.

## What changed

- Fixed a long-term learning bottleneck: rvset3 profiles now reload up to 16 positive style prototypes and 28 hard-negative/confuser prototypes instead of silently truncating back to 6/10.
- Positive handwriting examples are diversity-selected immediately so the model preserves genuinely different writing styles rather than redundant samples from one rota.
- Hard negatives are also diversity-selected, creating a compact bank of representative confusers.
- Added correction-specific hard-negative learning. Removing a detected employee occurrence teaches the local identity model that the rejected handwriting is a confuser; changing only the shift time does not alter identity learning.
- Added `RotaIdentityPolicy`, a separately testable decision-boundary layer. Candidates resembling known confusers require a larger positive-vs-negative margin before acceptance.
- Added identity-model diagnostics: style prototype count, confuser prototype count, closest-confuser similarity, and boundary-health score.
- Added JVM regression tests for adaptive identity-boundary behavior.
- Preserved v11 verification and v12 perception as independent safety gates.

## Deliberate limitation

No untrained neural network weights are bundled. The local prototype/confuser model is now structured to produce better training evidence for a future real handwriting embedding model.
