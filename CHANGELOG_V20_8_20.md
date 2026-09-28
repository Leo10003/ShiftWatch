# ShiftWatch v20.8.20 — labelled missed-candidate audit

**Developer tools only.** Android application remains at **v20.8.18 (versionCode 228)**. No changes to OCR, handwriting scoring, production acceptance or stored user data.

- Extend `tools/evaluate-labelled-scans.py` with optional separate `--candidate-audit` Markdown output. It uses explicitly verified working-day block/OFF labels to inspect **rejected working days** and **accepted OFF days** for each scan rather than guessing truth from the winning candidate. Other existing summary output remains unchanged.
- Report source, positive, confuser and adjusted scores, rank and physical block for top candidates; production threshold/runner, candidate-pipeline evidence, and original-vs-trim experimental scores and raw separation. Flag a replay that accepts the wrong block or creates OFF suggestions; never auto-enable a hypothetical change.
- Label same-fingerprint scans as repeat observations rather than independent photographic validation. Exports are sanitized, so genuinely distinct photographs require external verification.
- Add seven synthetic unit tests to the existing Python validation suite. No private diagnostics or photos ship in the release.

From the repository root, provide a **private** manifest and full explicit truth for each photo (same schema as v20.8.16), then run:

```powershell
python .\tools\evaluate-labelled-scans.py `
    --manifest "$env:USERPROFILE\Desktop\ShiftWatch-private\manifest.json" `
    --output "$env:USERPROFILE\Desktop\ShiftWatch-private\evaluation.md" `
    --candidate-audit "$env:USERPROFILE\Desktop\ShiftWatch-private\missed-candidate-audit.md"
```

This does not perform OCR, change the app, calibrate scores or establish true photo independence. It also does not validate time labels or week-date inference. Store the private files outside Git.
