# ShiftWatch v20.8.28 research step — fixed evidence-discrimination audit

This is a **diagnostic-only tooling patch** on top of the validated v20.8.27
`develop` commit (`8bcba0b`). It deliberately does **not** bump Android version
metadata, alter candidate ranking, change rescue thresholds, or touch app data.

`tools/audit-score-discrimination.py` reads an existing schema-16+ labelled
manifest using `evaluate-labelled-scans.py` and produces a separate Markdown
report. Existing strict candidate-count, rank, top-three and per-block
validation is retained. The report includes per-day, per-block fixed maxima
for adjusted, positive, confuser and raw separation evidence, subdivided by
OCR/ink origin. It reports block-leader margins, includes known OFF days as
negative controls, and counts out-of-layout candidates without promoting B4+
to a labelled shift block. It separately shows an exploratory cross-day score
envelope; this is **not** a learned threshold and cannot establish production
safety on one reference photograph.

The report is written only if evaluation succeeds and refuses to overwrite an
existing output. Private scans and labelled truth must remain outside Git.

Validation: `python -m unittest discover -s tools/tests -p "test_*.py"` and
`./tools/dev-preflight.ps1` on Windows. Full Android build and lint remain
required before release, even though this patch changes no Android code.

Usage from the development directory (PowerShell):

```powershell
$private = 'C:\Users\leonardo\Desktop\ShiftWatch-private'
$manifest = Join-Path $private 'manifest-v27-corrected.json'
$output = Join-Path $private 'score-discrimination-v28.md'
python .\tools\audit-score-discrimination.py --manifest $manifest --output $output
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic audit failed.' }
Get-Content $output
```

Only run against an automatic export of the same image as the matching
reference truth. Separate photographs are necessary before proposing a
production scoring adjustment; do not infer time or week-date accuracy here.
