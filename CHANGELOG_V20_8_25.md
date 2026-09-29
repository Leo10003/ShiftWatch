# ShiftWatch v20.8.25 — fixed-score exported ranking ablations

**Offline developer tools only.** The installed Android app remains v20.8.23 (versionCode 229); no Android recognition logic, threshold, saved profile, confirmed shift, or README changes.

- Add optional `--score-ablation` report to the strict labelled evaluator. It compares four **predeclared** ranking observations among the *exported top three* crops: adjusted score, positive-only score, raw separation, and OCR-origin adjusted-only score.
- Deduplicate crops by physical block within each variant to avoid confusing two crops from one block with two competing blocks. Report label ranks descriptively; labels never affect scoring.
- Include **all seven days**, with Friday OFF shown as an explicit negative control. Mark missing or unavailable ranking evidence instead of inventing scores or assuming missing candidates were never generated.
- Maintain automatic-export provenance checks, fingerprint duplicate warnings, and distinct report output paths. Add six synthetic tests for ranking, sparse evidence, negative controls and CLI behavior.

**Limits:** this is NOT a production replay. It neither processes unexported candidates nor runs production acceptance gates, and it cannot demonstrate recovered shifts or support rule selection using one repeated reference image. Independently photographed and labelled rosters are needed before a production change.

Run privately alongside existing reports using `--score-ablation "$private\score-ablation-v25.md"`.
