# v20.8.10 — Final-scan ownership and input-sensitive recognition cache

## Root-cause investigation

The same photo produced a repeatable workflow-dependent difference: opening the viewer while the background scan was running showed 5/6 expected shift locations; opening only after the background scan showed 4/6. Existing code allowed viewer handwriting matching to begin on provisional `AssistData`, whose token list and geometry were still being replaced by background OCR. Moreover, its dialog-level recognition cache validated only token count and OCR pass count, not their actual contents or coordinates. These are concrete concurrency and invalidation defects; whether they explain **all** residual independent-scan variability is not yet established.

## Changes

- **Single background OCR owner:** immediate preview remains available, but the full-screen viewer displays the image and a progress indicator until the existing background importer completes and the review receives its final `AssistData`. Only then is the expensive saved-profile matcher mounted. The viewer does not start its own competing analysis on partial OCR results; if already open, it transitions into the matcher automatically when inputs are final. This may delay automatic viewer suggestions for users who open immediately. Manual edits in the review remain available and protected.
- **Session-only full-input keys:** reopened viewer recognition snapshots and the internal candidate cache are invalidated when any OCR token's text, source, coordinates or block hint changes, or when image size, document kind, pass count, grid rules, row boundaries or template fingerprint changes. Canonical sorting avoids spurious invalidations caused solely by OCR token order. Internal keys containing OCR-text-derived material are **never exported**.
- **Safe, geometry-only diagnosis:** diagnostic schema 11 exports a short SHA-256 fingerprint of sorted OCR token geometry/source plus structural layout, with seven approximate horizontal bucket fingerprints. The buckets are NOT authoritative weekday columns. Neither raw OCR text, names, photographs nor precise token coordinates are exported. Full hashes are not cryptographic anonymization against all imaginable inference; exported digests use only geometry and layout.
- **Viewer lifecycle:** export `waited_for_final_scan` for a viewer opened while analysis was in flight, `fresh_final_scan` when opened after completion, or `reused_completed` when reopening compatible cached results.
- **Development report:** `tools/analyze-scans.ps1` and `tools/check-recognition-repeatability.ps1` now identify whether exported OCR geometry/layout inputs differ between independently captured scans. The safe patch installer now derives the expected base version directly from the release patch, so future releases do not require an installer-version edit. For installing **this** release from v20.8.9, use the manual `git apply --check` path below because the *old* installed installer still expects v20.8.8.
- **Unit checks:** canonical fingerprint ordering, same-token-count text/position changes, row geometry invalidation, readiness gate combinations and viewer-cache invalidation.

## Unchanged / not claimed

- No OCR threshold, shadow-OCR promotion, scoring policy, saved handwriting profile format or shift import behavior changes.
- Existing confirmed shifts, manual corrections and training profile are never cleared; do not uninstall the app during upgrade.
- A matching geometry fingerprint cannot rule out OCR **text** drift, CPU/timing effects or scoring bugs. If results still diverge, compare the fingerprints and candidate diagnostics before deciding on further changes.
- Week interpretation, time inference and OCR runtime optimization remain outstanding.

## Acceptance test

After GitHub Actions passes, use the **same photo and unmodified saved profile** for two independent scans: (A) open full-screen preview immediately and leave it open until background analysis completes, (B) wait for background scanning to finish before opening the viewer. Export two schema-11 diagnostics and run `tools/analyze-scans.ps1` with `-Output`. Ideally, decisions and scores match exactly, Friday stays OFF, and the viewer lifecycle differs appropriately between workflows. If not, the geometry fingerprints should help identify the next fault. This release is an engineering hypothesis subject to device validation, not a claim of 6/6 accuracy or proven deterministic OCR.
