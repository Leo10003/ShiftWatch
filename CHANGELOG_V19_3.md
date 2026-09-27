# ShiftWatch 19.3 — Document Date Authority & Morning-Time Recovery

- Explicit rota header dates now outrank the planner's currently selected week.
- Three or more consistent DD.MM weekday headers reconstruct the exact Monday directly from the document.
- Header parsing is restricted to the real header corridor instead of the broad top 19–20% of the image.
- Fixed a time-recognition bug where top-row labels such as 09:30 could be discarded because the hour looked like a calendar day.
- Header-date exclusion from time OCR now requires explicit date-shaped text inside the header corridor.
- Planner-week proximity is now only a weak tie-breaker when document date evidence is incomplete.
- Existing v19.2 structural time, high-resolution atlas, calibration, identity learning, verifier and review behavior are preserved.
