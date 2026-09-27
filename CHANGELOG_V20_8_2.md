# v20.8.2 — Repeatability diagnostics (investigation release)

**Purpose:** Determine why an identical roster and profile may produce Tuesday vs Thursday misses, without weakening recognition safeguards or overriding manual dates/times.

- Add privacy-preserving **top three saved-profile candidates per weekday**, including rank, physical block, vertical *decile* (not exact coordinates), positive-profile score, learned-confuser score and final adjusted score. Missing candidates remain explicitly unobserved rather than fabricated.
- Upgrade sanitized import diagnostics to schema 4, app version `20.8.2`, exporting ranked candidates inside `viewerMarkers.savedProfileDecisions` and `seededVisualDecisions`. These traces explain whether a rejected candidate came from the expected physical block and whether a confuser penalty changed its rank. Individual candidate traces appear only after opening the image viewer and running its matcher.
- Add `tools/compare-scan-diagnostics.ps1`, a Windows PowerShell-native comparison tool for two or more complete JSON reports. No Python or internet required. It warns when image dimensions or OCR token counts differ.
- Unit tests cover coarse geometry and block-specific candidate diagnostics.

**Not changed:** identity acceptance thresholds, OCR engine, date resolution, time-evidence weighting, personal training data or review approval rules. v20.8.1's dataset showed five of six weekdays, but Tuesday rather than Thursday was missing. A single report cannot prove that the matcher is unstable, so collect at least two additional scans using the *same image and unchanged training profile*.

**Privacy:** no employee names, OCR text, image crops, exact date hypotheses or precise candidate coordinates are added. Do not share raw rotas containing other people's personal information without permission.

**Integration:** Apply the patch to v20.8.1 on `develop`; do not overwrite your repo with the ZIP. Because `docs/DEVELOPMENT_MEMORY.md` differed in a previous iteration, this patch deliberately leaves the existing development-memory file untouched. Verify with GitHub Actions before considering the Android build tested.
