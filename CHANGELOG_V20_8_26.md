# ShiftWatch v20.8.26 — exported block-evidence coverage

**Offline developer tools only.** The installed Android app remains v20.8.23 (build 229); no changes to Android code, thresholds, saved handwriting profiles, confirmed shifts or README.

- Add `--block-coverage` report for all seven days, including explicit OFF negative controls. Reconcile top-three exported crops with the per-block production-best summaries and separate shadow-OCR stream.
- Identify physical blocks missing from the exported top-three subset while reporting the per-block production adjusted-score winner and its candidate origin.
- Explicitly prevent misinterpreting per-block winners as maximum raw separation/positive scores, or shadow OCR as a complete OCR-only production ranking.
- Validate missing sparse summary fields, duplicate report destination collisions, and preserve old report outputs and baseline behavior with new unit tests.

**Limitations:** the exported per-block diagnostics still summarize only the highest-adjusted crop in each block, not every production crop. This release does not rescore candidates or replay acceptance. Independent photographs and richer crop-level exports are required before a production model change.
