# ShiftWatch v18.1 — Recall & Block-Time Recovery

- Added conservative second-pass handwriting rescue for a single missed weekday. A borderline candidate is accepted only when it is clearly separated from the runner-up and learned confusers.
- Kept one-candidate-per-day behavior to avoid reopening false-positive name matches.
- Added TIME_ATLAS block-local token composition. OCR fragments such as `16` + `00` and `9` + `30` are recombined only inside the same day/block cell before time scoring.
- Structural block-time solving remains authoritative. Solved block times are reused for every employee match in that block; unresolved rows remain unresolved rather than borrowing a different row's time.
- Existing strict row ownership, verification, identity profiles, confuser learning, resumable sessions, duplicate detection, preview stability, and manual review are preserved.
