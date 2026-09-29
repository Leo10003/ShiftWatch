# v20.8.30 — Same-crop score-adjustment provenance (diagnostic only)

- Add `tools/audit-adjustment-provenance.py` for fixed, label-blind per-crop evidence conflicts.
- Record the exact positive/raw/confuser/adjustment combination from each crop; do not mix maxima of different crops.
- Count (but never relabel) crops in B4+ or without physical-block assignment.
- Explicitly retain OFF-day rows and refuse to generate a report when serialized raw or adjusted score arithmetic is inconsistent.
- Limit detailed displays to two strongest qualifying actual crops per day while counting every qualifying crop.
- Add focused regression tests for deterministic ordering, OFF label independence, outside-region preservation, inconsistent evidence, old schema and no overwrite.
- No Android, version metadata, production thresholds, OCR, replay, acceptance or learning changes. This patch does not establish improved recognition.

Run the preflight, Python tests, Android assemble/unit/lint and generate a new private report. Examine the Kotlin branch producing `separationAdjustment` before proposing any recognition changes; keep Friday OFF as a mandatory negative control and gather independent labelled photographs.
