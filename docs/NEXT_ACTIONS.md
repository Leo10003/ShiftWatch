# v20.8 handoff
This version addresses *diagnostic blind spots*, not an unmeasured recognition fix. The first labeled metadata fixture covers week 2026-09-07 and six working days; source photo remains outside the repository.

CI quality gate on `develop`: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` plus `python tools/verify_regression_cases.py`.

Next steps after the new sanitized JSON export:
1. Inspect independent date hypotheses and compare header geometry/text paths. Only change algorithms based on reproducible, privacy-safe evidence.
2. Map the physical-block time diagnostics back to user-confirmed times. Guard against cross-block borrowing.
3. Diagnose Thursday's missing name occurrence with optional *redacted*, user-approved crops; collect negative examples before any threshold change.
4. Improve stage cost: repeated full-page OCR passes took ~16 s and time solving ~7 s in the first report. Benchmark on the same device with image caching/targeted reruns, not speculative parallelism.
5. Keep CI green and preserve the existing signed-app local data. GitHub debug signing may differ from Android Studio; install on a separate clean emulator until a stable development signing key exists.


## v20.8.1 source patch handoff

Apply the v20.8.1 patch on top of a clean v20.8 checkout. Verify GitHub Actions assembly, unit tests and lint. Import the same image on the same profile; explicitly select actual week 2026-09-07; open viewer and export diagnostic JSON **before** manually marking missing Thursday. Inspect saved-profile and seeded-search decisions for weekday 3, marker block provenance, header fallback-versus-displayed matching counts and per-block time candidate input trace. Do not change identity/time thresholds without source evidence. See CHANGELOG_V20_8_1.md.
