# ShiftWatch 19.0 — Structural Learning Foundation

## Smart Rota Import
- Added perspective-tolerant ordered row alignment (`RotaGridModel`). Each weekday column is aligned to a shared canonical block sequence, so a missed horizontal rule no longer shifts all later time blocks.
- Time-atlas block IDs now use the aligned structural grid rather than nearest raw Y coordinates.
- Semantic time bands and whole-page time candidates use the same canonical block ownership model.
- Tightened time-atlas crops and suppresses both the known top rule and crop-edge vertical rule remnants before OCR.
- Split hour/minute reconstruction now requires same-block ownership and checks superscript-like geometry before combining tokens.
- Confirmed start-time corrections are now stored as durable block mappings with confidence, confirmations and contradictions. Automatic guesses do not train the model.
- Rota templates persist block-indexed semantic times in a backward-compatible format; older y|time snapshots continue to load.
- Added sequence-level identity recall rescue: after at least three strong weekdays, up to two genuinely borderline missing days may be recovered only with a strong margin over runner-up/confuser evidence.

## Reliability
- Existing strict time ownership, structural solver, verifier, identity confuser learning, resumable sessions, duplicate protection and manual review are preserved.
- Learned time mappings remain priors; strong fresh evidence can override them when the rota changes.

## Version
- versionCode 190
- versionName 19.0
