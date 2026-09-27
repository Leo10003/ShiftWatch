# ShiftWatch Premium v20.4 — Deterministic Review & Focused Header OCR

## Deterministic review
- Handwritten/mixed-rota identity matches are suggestions only until the user taps the marker.
- Automatic detections never start selected for import; selection is always an explicit user action.
- READY now means the row is resolved **and** explicitly selected. Resolved but unselected rows show REVIEW.
- When the resolved rota week changes, existing selections are cleared and draft dates are rebased before verification.
- Manual week changes also clear the current selection to prevent stale-date imports.
- Handwritten automatic drafts are no longer carried directly into the planner list; the selector owns confirmation.

## Date authority
- Adds a dedicated high-resolution header OCR pass over the top of the rota.
- Runs both enlarged normal and thresholded header views, maps boxes back to source coordinates, and marks them as HEADER_FOCUS evidence.
- Adds one shared `documentWeekResolution()` result so analysis and review use the same authoritative week/confidence decision.
- Header-focus tokens are explicitly excluded from time recognition so date fragments cannot become shift hours.

## Start-time ownership
- Keeps time propagation restricted to already-confirmed identities in the exact structural block.
- A calibrated row time never confirms an unconfirmed blue identity suggestion.
- Adds visible structural-row diagnostics to the selector overlay for faster debugging of unresolved time bands.

## Stability
- Automatic handwriting bootstrap no longer calls the heavy tap/time resolver for every candidate.
- Adds a single-flight tap-resolution flag on top of the existing analysis guard and increases burst throttling.
- Repeated identity suggestions are cheap until the user explicitly confirms one.

## Regression checks
- Ordered `21.09–27.09` header text resolves to Monday 21 Sep 2026 even with a poisoned Dec 2024 fallback.
- Superscript `9³⁰`, `13⁰⁰`, and `16⁰⁰` remain recognized by the specialist time engine.
