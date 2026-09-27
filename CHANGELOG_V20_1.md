# ShiftWatch Premium v20.1 — Audit & Stability

A broad reliability, performance, persistence, and recognition-consistency audit on top of v20.0.

## Recognition / inference
- Document-resolved week is now used by verification, so a draft on the wrong planner week becomes a contradiction instead of appearing geometrically valid.
- Date authority requires stronger coherent/contiguous weekday evidence.
- Perception-time evidence is restricted to actual structural time-label regions so header dates cannot masquerade as times.
- Repeated OCR renderings of the same weekday/block/time are collapsed before semantic voting.
- Canonical perspective-normalized row anchors now drive template fingerprinting and row similarity.
- Manual replacement of an incorrect block time starts with clean contradiction state; repeated confirmations can repair earlier uncertainty.
- Resumed sessions rebuild derived perception state for deterministic verification.

## Performance / memory
- ML Kit callback processing runs on a dedicated background executor.
- Cancellation is terminal at recognition-stage boundaries and during time-atlas processing.
- TextRecognizer, analysis bitmaps, enhanced/threshold bitmaps, preview bitmaps, and atlas intermediates are explicitly released.
- Time-atlas variants are rendered and OCRed sequentially to reduce peak heap usage.
- Obsolete whole-column OCR implementations were removed.
- Analysis/preview bitmap decoding is bounded more reliably.
- Canonical grid caching now uses weak identity semantics without hashing large AssistData graphs.
- Heavy template/verification/session work is moved away from the UI thread; review-session checkpoints are debounced.

## Data integrity
- Very short completed shifts can no longer collapse to an invalid zero-duration record after half-hour rounding.
- Backup/planner import now rejects overlapping planned shifts consistently with normal planner entry.
- Non-critical adaptive-model writes use asynchronous preference persistence.

## Compatibility
All v20 functionality remains: canonical document/date model, structural time engine, high-resolution atlas, identity learning/confusers, calibration, verification, resumable sessions, duplicate detection, and manual review.
