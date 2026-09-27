# ShiftWatch Premium v18.0 — Structural Time Engine

This release focuses on start-time reliability while preserving the existing identity, verification,
preview, resume, duplicate-detection, and correction-learning features from v17.2.

## Structural time ownership
- Added `RotaStructuralTimeEngine`, which solves start times by physical rota block rather than by
  nearest OCR token or employee name.
- Each block keeps multiple competing time hypotheses and combines evidence across weekday columns.
- Time-atlas observations receive explicit block and column provenance, so a token cannot migrate to
  another schedule row after OCR.
- Learned semantic times remain priors only; a learned block without fresh evidence stays ambiguous.
- Added a soft chronological row-consistency check. Backwards row sequences are downgraded instead
  of silently accepted.
- Ambiguous blocks abstain and remain TIME UNRESOLVED instead of borrowing a plausible time from a
  different row.

## Time atlas improvements
- Narrowed the OCR crop to the actual time-label corner of each physical block.
- Removed the known top grid rule before OCR so small superscript `00` / `30` marks are less likely
  to be crossed by a table line.
- Replaced one global threshold with per-block thresholding, improving mixed lighting and shadows.
- Atlas tokens now carry `TIME_ATLAS` provenance and a persistent block hint through saved/resumed
  import sessions.

## Integration and diagnostics
- `draftFromTap`, preview time suggestions, and direct time-tap recognition now consult the same
  structural block solver first.
- The strict v17.2 owned-row resolver remains as a guarded fallback; useful existing behavior was not
  removed.
- Analysis details now expose the structural block model, support columns, atlas support, confidence,
  ambiguity state, and leading alternatives.
- Added regression tests for isolated 09:00 hallucinations inside a repeated 16:00 block, weak
  single-column evidence, prior-only mappings, and backwards row-order conflicts.

Version: 18.0 (versionCode 180)
