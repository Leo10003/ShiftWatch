# ShiftWatch Premium v12.0 — Perception Engine

## Smart Rota Import
- Added a dedicated perception layer beneath the v11 verification engine.
- Added a tiny-vocabulary time recognizer that repairs common OCR/handwriting confusions such as O/0, I/1 and compact 930 notation.
- Added learned-time priors without turning workplace history into hard rules.
- Added global time-band perception across weekday columns.
- Added explicit perception ambiguity and structural-readability measurements.
- Integrated perception as independent evidence into the v11 verifier; perception can support or downgrade a hypothesis but cannot bypass verification.
- Added perception diagnostics to the review screen.
- Added unit regression tests for common time-label failure modes.

## Model-ready architecture
The perception layer is deliberately separated from verification so a future trained handwriting embedding model and specialist digit model can be plugged in without allowing raw model output to write shifts directly. No untrained/fake neural weights are bundled in this release.
