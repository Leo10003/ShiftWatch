# ShiftWatch v17.0 — Block Time Intelligence

- Start-time learning is now keyed primarily to the physical schedule block index instead of normalized Y alone.
- Semantic time bands persist block index, confirmations and contradictions across resumable sessions.
- Smart tap/import resolution consults the block-indexed semantic model before legacy nearest-OCR heuristics.
- Learned block mappings tolerate vertical translation/camera framing changes while fresh contradictory evidence remains able to override them.
- Added regression tests for noisy 09:30 evidence within one physical block and learned-time recovery after large vertical photo shifts.
- Diagnostics now identify semantic blocks explicitly.
