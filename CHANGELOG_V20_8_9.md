# v20.8.9 – Shadow OCR and development toolkit

- **Diagnostic-only OCR merge experiment:** save original OCR bands before merging and score them with the existing saved handwriting profile separately. Production candidates, matching thresholds, rescues and user data remain unchanged. Export only anonymous shadow counts and strongest score/block/vertical decile in schema v10. Shadow scores **never** create suggestions or alter ranking.
- **One-command PowerShell diagnostics:** `tools/analyze-scans.ps1 -Paths scan1.json,scan2.json -Output report.md` checks independent scans, reference block matches, comparison drift and scan duration, producing a sanitized shareable report.
- **Safer release application:** `tools/apply-release.ps1 -Patch ...` performs clean-tree check and patch dry run; explicit `-Apply` applies, without committing, pushing, clearing app data or stashing user modifications.
- **Better preflight:** optional `-Android` local unit tests and lint, `-Patch`, and optional `-Diagnostics` unified report. Python remains optional locally. The reference baseline JSON is validated without Python too.
- **Permanent CI metadata guard:** anonymized v20.8.8 diagnostic decision snapshot, validated in GitHub Actions. This checks metadata and decision consistency, **not** OCR image reproduction.
- **Not addressed in this release:** unresolved rota document week, mistaken shift time inference, Tuesday automatic recovery, and device OCR performance.

**Compare:** Run two independent scans with schema v10; inspect Tuesday `shadowOcr` versus production top score, and Friday unchanged. Do not promote shadow candidates without independent evidence and wider regression coverage.
