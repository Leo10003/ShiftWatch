# ShiftWatch v20.8.21 — explicit evaluation provenance

**Developer tools only.** Android application remains at v20.8.18. No recognition scoring, thresholds, crops, APK, user profiles or confirmed shifts are modified.

- Add a `schemaVersion: 2` evaluator manifest with `predictions: [{"path": "scan1.json", "origin": "automatic_export"}]`. This is an explicit **user-declared** origin; sanitized scan JSON cannot independently attest whether it has been manually edited.
- The manually confirmed schedule belongs only in separate `groundTruth` JSON, including `offWeekdays: [4]`. Never feed manually edited `scan2.json` into `predictions` or count it as a second automatic result.
- Reject missing/manual origin tags, predictions pointing directly at the ground-truth file, and retain all existing duplicate session and shadow-replay guards.
- Keep legacy schema-1 manifests readable but add a visible warning: their provenance is unverified. Existing automation and fixture compatibility are preserved.
- The evaluator **only** scores weekday shift-block recognition and OFF false suggestions. Supplied `startTime` labels are preserved but start-time accuracy is **not** evaluated, and no validation across distinct photos is claimed for repeated fingerprints.
- Five additional synthetic tests cover explicit automatic provenance, prohibited manual predictions, missing labels, legacy warnings and prediction/truth separation.

## Private v2 manifest example

Place the real, unedited automatic diagnostic export in a private folder as `scan1.json`; create the truth file there as `reference.truth.json` from your independently confirmed schedule. Create `manifest.json` as:

```json
{
  "schemaVersion": 2,
  "cases": [
    {
      "id": "reference-rota",
      "groundTruth": "reference.truth.json",
      "predictions": [
        {"path": "scan1.json", "origin": "automatic_export"}
      ]
    }
  ]
}
```

With your reference rota, `reference.truth.json` should include six shift entries (weekdays 0,1,2,3,5,6), their correct physical blocks, and `"offWeekdays": [4]`. Keep the truth file, diagnostic scans and generated reports **outside the Git repository**.

Run from the project root:

```powershell
$private = "$env:USERPROFILE\Desktop\ShiftWatch-private"
python .\tools\evaluate-labelled-scans.py `
    --manifest "$private\manifest.json" `
    --output "$private\evaluation.md" `
    --candidate-audit "$private\missed-candidate-audit.md"
```

The auditor never interprets an edited diagnostic scan as objective truth, and the new manifest does not grant manually corrected files automatic-export provenance.
