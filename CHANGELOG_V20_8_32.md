# ShiftWatch 20.8.32 — Review-only boundary telemetry

- The Android diagnostic export now includes **additive, label-blind** daily boundary review telemetry: number of positive-raw but below-boundary scored crops in the three expected labelled blocks, number of extra-region crops, and the **single actual closest** crop with its origin, rank, block, raw score, measured requirement, and shortfall. No OCR text, photographs, names, or exact crop coordinates are added.
- This is **not a new proposed shift**, automatic acceptance, new review marker, threshold change, or replay; production matching, saved profiles and OFF safeguards remain untouched.
- The `audit-boundary-review.py` offline tool checks every measured crop, recomputes and verifies any new Android aggregate, tolerates older measured-boundary exports, and can require at least two distinct OCR geometry fingerprints containing labelled OFF controls before cross-photo comparison. Fingerprint uniqueness does **not** prove independent photographs.
- B4+ and null-region candidates remain counted, but never promoted into B1–B3; any report is diagnostic and labels annotate after crop selection.
- The APK metadata is incremented to `versionCode 231`, `versionName 20.8.32`, eliminating the misleading 20.8.27 label for this build. Fresh scans must be exported from the **new APK** to contain the new summary.
- Added Kotlin and Python regression checks, including the negative-raw OFF control. Prior exports, regression fixtures, learning state, and report directories are unchanged.

## Limitation

The supplied reference photograph is one case: Wednesday has a small positive shortfall, but Friday is an OFF negative control and Saturday has negative raw separation. **No threshold or production recall improvement is claimed.** Independent labelled photos are needed before altering recognition decisions. The existing sanitized 5/6 fixture is metadata, not OCR replay; the current measured reference export indicates 4/6 production hits.
