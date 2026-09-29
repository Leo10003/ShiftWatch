# ShiftWatch v20.8.24 — all-seven-day ranking safety audit

**Offline developer tools only.** Android app remains v20.8.23 (versionCode 229), unchanged.

- Add optional `--ranking-safety` report to the existing labelled evaluator, covering all seven weekdays including OFF negative controls instead of only missed days.
- Show exported top block, correct-block rank within the top three, descriptive difference from the recorded acceptance floor, leading-candidate margin, raw positive-versus-confuser separation, and a diagnostic failure classification.
- Preserve strict automatic-export provenance, explicit complete seven-day ground truth and duplicate OCR fingerprint cautions. Never score manually corrected diagnostic scans as predictions.
- No new scoring or acceptance thresholds, no crop rescore, no Android OCR/recognition changes, no saved-profile changes, no source-tree private diagnostic files.
- Scores are uncalibrated; the report cannot infer that lowering the floor would accept a candidate because production has additional gates. Missing top-three candidates do not prove the candidate did not exist.

To generate privately, add `--ranking-safety "$private\ranking-safety-v24.md"` to the v20.8.23 labelled evaluation command. Validate independently photographed rosters before considering any production scoring changes.
