# ShiftWatch v20.8.19 — offline scan drift comparison

**Developer tools only.** The Android app version remains **20.8.18 (versionCode 228)**. No APK or Android recognition changes are included.

- Add read-only, offline Python 3 standard-library `tools/compare-scan-drift.py` to compare two **complete schema-15** scan exports, report individual weekday production-status/block changes, top-score drift, distinct shadow replay outcomes and candidate-pipeline changes.
- Distinguish matching vs changed OCR geometry fingerprints, list changed x-buckets when provided. A matching fingerprint is not proof of an identical photo or unchanged saved profile; profile identity is deliberately **not** exported to protect privacy. Report makes no causal claim about training changes.
- Fail early on incomplete scans, missing recognition, duplicate run/session IDs, missing weekday decisions, failed replay baseline parity, or concatenated diagnostic JSON. Never count rejected top candidates as user-visible suggestions, and never turn experimental replay into production.
- Add nine synthetic regression tests and run all `tools/tests/test_*.py` in CI. Keep private example scans and training data out of the repository.

Usage (from repository root):

```powershell
python .\tools\compare-scan-drift.py .\scan_previous.json .\scan_latest.json --output .\private-drift-report.md
```

This compares exports; it does not evaluate correctness, predict accuracy, perform OCR, determine who changed profile data, or prove photo independence. Use `evaluate-labelled-scans.py` and explicitly verified ground truth for correctness evaluation.
