# ShiftWatch v20.8.29 — same-crop evidence audit

Research-only Python tooling on top of the v20.8.28 `develop` commit `b176eb8`.
No Android version bump, no production ranking/threshold changes, no saved data migration.

The v20.8.28 report independently maximized adjusted, raw and positive scores,
which can select *different* crops. This separate report lists the actual
single crop maximizing adjusted score and the actual crop maximizing raw
separation within each weekday/physical block. It records each candidate's
export rank, OCR/ink origin and its *own* complete score bundle, marks when
those representatives are the same crop, and counts B4+/null regions without
mapping them into the three labelled reference blocks. Labels annotate only
after the fixed, label-blind crop choice. It uses the existing strict schema-16+
manifest evaluator and refuses output overwrites.

Usage (PowerShell; private inputs and outputs remain outside the repository):

```powershell
$private = 'C:\Users\leonardo\Desktop\ShiftWatch-private'
python .\tools\audit-same-crop-evidence.py `
    --manifest "$private\manifest-v27-corrected.json" `
    --output "$private\same-crop-v29.md"
if ($LASTEXITCODE -ne 0) { throw 'Same-crop audit failed.' }
```

This is not OCR evaluation, calibration or a replay of production decisions.
One photo and its repeated exports cannot establish generalization or justify
new acceptance thresholds. Keep Friday's OFF constraint and existing
production behavior unchanged pending independent labelled photographs.
