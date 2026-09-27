# v20.8.5 — Candidate-source stability and audit

**Observed baseline:** v20.8.4 correctly reuses the same viewer recognition result, but its saved-profile matcher still rejects Thursday and Saturday in the reference case. Separate runs can favor OCR-derived rows or ink-gap probes. A high raw similarity is not by itself proof of employee identity when trained confusers also score highly.

- Process OCR name tokens in a canonical geometric order before merging them into candidate bands. OCR pass order can otherwise affect nearest-band merging and crop geometry. Use stable ordering for tied final candidate bands.
- Export diagnostic schema v7 with `candidateSources` for each weekday, including the number of scored OCR-token candidates and ink-gap probes, each source's strongest adjusted and raw positive scores, confuser score, and associated physical block. This exposes alternatives even if they fall outside the global top three.
- Preserve existing acceptance floors, confuser penalties, rescue logic, saved examples, manual confirmations and cached viewer recognition. No forced recognition result or learned reference-week answer is introduced.
- Add source-aggregation regression checks, including input-order independence and preservation of losing OCR evidence.

**Validation required:** Full Android build, unit tests and lint in GitHub Actions. Repeat independent scans of the same photo, without profile changes, to determine whether candidate-source ordering stabilizes and where real identity improvements are safe. Date and time OCR are separate unresolved workstreams.
