# ShiftWatch v20.8.27 — complete sanitized production candidate export

Android app v20.8.27 (build 230), scan diagnostic schema 16.

- Export `completeProductionCandidates` for **all seven days**, including OFF and no-usable-signature cases. Each array contains **every successfully scored production crop**, in existing production order, with contiguous rank, physical block (nullable), coarse vertical decile, origin, and adjusted/positive/confuser/raw separation/penalty/adjustment scores. No OCR text, names, images, templates, or exact coordinates are added.
- Preserve the existing `topCandidates` top-three compatibility field, per-block and shadow-OCR summaries, replay, scoring gates, OFF handling, and recognition outcome. The complete array is diagnostic only and is not consumed by recognition or learning.
- Add an optional `--complete-candidates` offline report. Strictly validate row counts, rank order, top-three prefix, and per-block candidate counts before comparing the entire exported list using fixed, label-independent ranking rules. No acceptance or OFF simulation; previous schema-15 exports require a new automatic scan for this report.
- Add Android unit and offline synthetic regression tests, including an OFF control and malformed/incomplete export rejection. README unchanged.

**Important:** complete scored crops are **not** every generated crop: candidates with unusable signatures remain unscored and cannot be ranked. Separate shadow OCR is not merged into production. Same photo scans do not establish independent validation. No automatic scoring or acceptance behavior changes. Preserve saved profiles and use in-place APK update; never clear app data.
