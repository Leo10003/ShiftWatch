# v20.8.12 — Controlled vertical-crop investigation (diagnostics only)

The current reference is reproducible 4/6 weekday locations. Thursday and Saturday remain misses, and Friday is correctly OFF. This release tests a fixed set of **vertical** crop boundaries against the saved positive/negative handwriting model. It does not perform alternate image preprocessing, learn new handwriting or adjust production recognition.

- Schema 13 exports `cropExperiments` for Thursday, Friday (OFF control) and Saturday, assessing up to two distinct top-ranked candidates **inside physical block 2** per day.
- Variants are `original`, `widen_28`, `widen_52`, `trim_12`, with clipping/deduplication. Each exports adjusted score, positive and confuser scores, raw separation, penalty and adjustment. Only coarse vertical decile and candidate origin are shared, never actual crop coordinates/images/OCR text.
- Export approximate overlap between two selected candidates to investigate Thursday's near-tie.
- Analyzer compares original to the altered candidate with the highest raw separation (score as tie-breaker) and exposes Friday's control result alongside Thursday/Saturday. The analyzer does **not** recommend accepting an experimental crop.
- The recognition engine scores experimental variants after finishing the **unchanged production ranking**, and these scores cannot affect matching, thresholds, runner margins or learning. No stored profile or shifts are modified.
- Scope: vertical crop geometry only. The diagnostic exports cannot prove which crop is the actual employee; this requires inspecting the source photograph locally with user confirmation.

To test: run both established preview-timing workflows after CI passes, export two schema-13 scans and use `tools/analyze-scans.ps1`. Check that weekday decisions and scores remain unchanged compared with v20.8.11, Friday OFF remains rejected, and compare separation increases for Thu/Sat versus Fri. Never uninstall or wipe application data.
