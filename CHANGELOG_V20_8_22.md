# ShiftWatch v20.8.22 — labelled structural time evidence

**Developer tools only.** Android app remains at v20.8.18. No production recognition logic, acceptance floors, handwriting profiles, shift data, APK, or saved user data are modified.

- Add optional `--time-evidence` output to the existing offline labelled evaluator. It compares independently labelled `startTime` values with **preliminary global block-level** `structuralTimeEvidence` proposals and exported alternative times, including review flags, confidence and independent support counts.
- Explicitly do **not** count block-level matches as final per-day start-time detections. The current sanitized export does not supply an authoritative final start time for every detected weekday; a final start-time accuracy percentage cannot be calculated.
- Only compare one confirmed time per physical block. If truth labels contain different start times in the same physical block, report them as ambiguous rather than silently selecting one.
- Treat absent truth-time labels or absent scan time evidence as unavailable, never as successful or failed automatic detections. Reject duplicate structural block indices and malformed user-provided HH:MM labels.
- Leave schemaVersion 1/2 manifests, the existing block/OFF evaluator and candidate audit compatible. All provenance safeguards remain.
- Eight additional synthetic tests cover preliminary vs final time separation, matching proposals, missing evidence, malformed and conflicting labels, duplicate blocks, output and path collisions.

## Run locally (private data outside the repository)

Assuming you already have `manifest-v21.json`, the confirmed truth file with start times, and the user-identified **unaltered** automatic scan in `Desktop\ShiftWatch-private`:

```powershell
$private = "$env:USERPROFILE\Desktop\ShiftWatch-private"
python .\tools\evaluate-labelled-scans.py `
    --manifest "$private\manifest-v21.json" `
    --output "$private\evaluation-v22.md" `
    --candidate-audit "$private\missed-candidate-audit-v22.md" `
    --time-evidence "$private\time-evidence-v22.md"
```

Do not put manually corrected diagnostic scans into `predictions`. Do not add the private scans or these reports to Git.

## Observed reference, not a general validation

In the provided one-photo reference, correct times 09:30 (block 1) and 16:00 (block 3) appear only as **structural alternatives** to proposed 09:00 for both, each marked for review with 1 distinct supporting weekday column, 0 strong columns and 0 independent atlas columns. The correct time being present as an alternative does not establish successful recognition or justify enabling it automatically. At least one independently labelled different photograph and final weekday-level time evidence would be needed to evaluate actual start-time detection.
