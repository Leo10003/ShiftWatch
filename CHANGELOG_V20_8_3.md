# v20.8.3 — Candidate overlap and score decomposition diagnostics

**Purpose:** Establish whether Tuesday/Thursday/Saturday saved-profile misses arise from overlapping candidate bands, different line sources, or learned-confuser adjustments. **No identity acceptance policy changed.**

- Extend the sanitized schema to version 5. For the three top saved-profile candidates per weekday, export raw positive-minus-negative similarity, policy confuser penalty, separation adjustment and candidate source (`ocr_token_band` or `ink_gap_probe`). No employee names, OCR text, crops or precise image coordinates are exported.
- For each saved-profile weekday, export the locally calculated top-versus-runner overlap fraction and whether they occupy the same physical block. This distinguishes near-duplicate lines from independent rivals. It does **not** change which candidate is accepted.
- Stable top-candidate tie sorting by source-image band top/bottom makes exact-score ties reproducible without altering the main scoring or thresholds.
- Unit tests cover disjoint/overlapping candidate bands and separation-of-evidence fields.
- The existing PowerShell comparison script is unchanged to avoid conflicting with the user's already committed parser fix. Full Android compilation, unit tests and lint must pass on GitHub Actions.

**Evidence limit:** The uploaded v20.8.2 exports represent two distinct scoring outcomes (one exported more than once), not four independent scans. Exact repeated image bytes and a frozen profile have not been independently proven.
