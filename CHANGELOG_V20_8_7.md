# v20.8.7 — Reliable viewer status and faster repeatability triage

- Diagnostic `appVersion` now reads the **installed** Android package version instead of a hardcoded `20.8.5` label. Schema remains 8 for compatibility.
- Opening the viewer with completed cached recognition immediately shows the count of suggestions awaiting manual confirmation rather than the stale "Analyzing…" text. A separate completion label also covers zero matches; unavailable OCR / failed saved-profile matching offers manual selection.
- New `tools/check-recognition-repeatability.ps1`: compare two or more sanitized schema-8 diagnostic exports offline, with explicit session/run provenance, per-weekday decisions, matched candidate scores and per-stage candidate-generation statistics. The script makes no changes to profiles or imported shifts. If candidate results differ, it reports differences rather than asserting a recognition regression.
- Add JVM unit coverage for restored-viewer completion-message wording.

Safety: No matcher thresholds, learned profiles, persisted shifts, saved user decisions or candidate scoring are changed. The viewer status is not a claim that suggestions are confirmed or times are resolved. Full Android CI remains the release gate.

Test: after CI passes, perform two independent scans of the same photo using the existing handwriting profile. Export two schema-8 diagnostic JSON files and run the comparison helper; the IDs must differ. A third export after merely reopening the same viewer should retain the same recognition run ID and decisions.
