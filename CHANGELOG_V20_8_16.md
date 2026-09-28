# ShiftWatch tools v20.8.16 — labelled multi-photo validation and replay report

**Tools-only release** built on the v20.8.15 export truncation fix. It intentionally leaves Android app version 20.8.15 and all recognition behavior unchanged.

- `tools/evaluate-labelled-scans.py`: offline strict JSON and labelled multi-photo evaluation. Requires explicit workday **and** OFF-day labels per case; no date or start-time accuracy claims. Reports production and experimental replay separately and fails closed on baseline parity mismatch, malformed/concatenated JSON, missing weekday decisions, or duplicate scan identities.
- `tools/analyze-scans.ps1`: actually includes the schema-15 **Shadow full-week decision replay** table, with baseline parity and trimmed-winner flags. Improves feedback when malformed JSON is supplied.
- Sample *synthetic* manifest and truth format. Do not commit real photographs, names, OCR text, or identifiable profile data to the repository.
- Includes Python stdlib-only tests for labelled scoring, malformed JSON, duplicate sessions, parity failure, and incomplete labels. Does not imply that one photo or repeated scans establish generalization.

## On Windows

```powershell
py -3 .\tools\evaluate-labelled-scans.py --manifest .\my-private-manifest.json --output .\my-private-evaluation.md
py -3 -m unittest discover -s .\tools\tests -p 'test_evaluate_labelled_scans.py'
```

A manifest names separate cases with distinct photos and lists their exported diagnostics. Ground-truth files must supply `offWeekdays` as well as shifts; omission of any weekday fails rather than silently assuming OFF. Example files are templates only. Run the existing Android CI to validate the v20.8.15 app before device export. The Python tests verify evaluation logic, **not actual OCR accuracy**.
