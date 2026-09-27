# ShiftWatch Premium v15.0 — Time Consensus & Reliability

## Smart Rota Import
- Preserves both best and runner-up time hypotheses from ambiguous OCR instead of collapsing early to one guess.
- Uses confidence-weighted row voting across weekday columns.
- Prevents multiple hypotheses from one OCR token from being double-counted in the same day column.
- Gives split handwritten labels such as `9` + superscript `30` stronger evidence than weak hour-only OCR.
- Applies learned workplace shift times only as soft priors; unseen times remain valid.
- Requires stronger row-level evidence and winning margin before a canonical time band is accepted.
- Keeps the v11 verifier as the final safety gate so uncertain time bands cannot silently become trusted shifts.

## Reliability
- v13 adaptive identity and v14 specialized token normalization remain intact.
- Existing preview recovery, resumable review state, duplicate-rota detection, and correction-specific learning remain intact.
