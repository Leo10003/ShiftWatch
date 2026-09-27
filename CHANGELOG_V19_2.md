# ShiftWatch 19.2 — High-Resolution Time Calibration

This patch concentrates on the remaining Smart Rota Import failure seen in v19.1: employee identity can be found while the structural rows remain unresolved for start time.

## Time recognition

- Raised the schedule-analysis working resolution from 2400px to 3000px on the longest side so tiny handwritten time labels retain more source detail.
- Enlarged every time-atlas block cell from 300×118 to 420×170 and widened the label crop while keeping it isolated from employee names.
- Added a pen-sensitive atlas rendering that preserves dark and chromatic handwriting (including blue/purple pen) while suppressing pale paper/background.
- Added a grayscale hour-focused pass in addition to thresholded hour/minute views.
- Tightened the superscript-minute crop to concentrate OCR on small 00/15/30/45 marks.
- Allows repeated two-column atlas evidence to resolve a structural block at a slightly lower calibrated confidence threshold, while still requiring same-block ownership and a non-ambiguous structural result.

## Calibration and adaptation

- A single explicit user-confirmed block time is now considered a strong template prior when its confidence is high and it has no contradictions.
- Manual time confirmation updates the active review model immediately, not only the saved template for the next import.
- Already-detected unresolved matches in the same physical block automatically inherit the explicit calibration during the current review.
- The confirmation prompt now explains that a row only needs to be taught once; unresolved-time status also explains when block calibration is needed.

## Preserved behavior

All v19.1 identity matching, confuser learning, canonical grid alignment, missing-row reconstruction, structural time ownership, verification, resumable sessions, duplicate detection, correction learning, preview stability, and manual review remain intact.
- Fixed template merging so previously calibrated block-time mappings are retained when a later import only observes a subset of rows; new confirmations update the matching block without erasing other learned blocks.
