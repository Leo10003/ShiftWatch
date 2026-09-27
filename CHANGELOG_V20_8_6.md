# v20.8.6 — Candidate provenance and faster triage

**Motivation:** Independent v20.8.5 scans of the same reference photo vary in candidate availability despite equal total page OCR token counts. Recognition thresholds must remain unchanged until the missing evidence is traced.

- Export schema 8 adds `candidatePipeline` to saved-profile weekday decisions. Counts trace strict and loose text bands, OCR token eligibility and coarse vertical distribution, OCR merge versus addition, probe windows and rejection reasons, final geometric filters, and anonymous final candidate counts per physical block. These diagnostics contain no image coordinates or OCR text.
- The same candidate-build cache retains the pipeline evidence, so reviewing and exporting does not rerun segmentation or OCR.
- Extend the existing PowerShell comparison script to print all pipeline counts side by side. No Python installation, copying JSON fields by hand or additional scan pass is required; the script remains compatible with earlier schemas.
- Add JVM-only invariant tests for candidate-funnel accounting to catch instrumentation regressions without an emulator.
- Mark `OfflineRotaVision` internal to resolve the Kotlin inspection that its public `Report` exposes internal diagnostic types. If this one-line visibility correction is already present in the local checkout, check the patch before applying and report any context conflict rather than overwriting your source.
- Preserve all saved profile and shift data, viewer cache, scoring, thresholds, and manually confirmed shifts. No recognition-accuracy improvement is claimed yet.

**Test protocol:** After CI passes, capture two independent scans of the identical photo without retraining. Export after opening the viewer. Supply the two schema-8 JSON files (different session IDs and recognition-run IDs). Run the local PowerShell comparison to identify whether differences originate before OCR eligibility, at OCR token merging, in gap probing or only at scoring.

**Development iteration:** Work on one falsifiable stage at a time using reproducible diagnostics; keep the fast metadata validator and JVM-only tests ahead of full CI, but do not skip Android build and lint. Reference fixture metadata is not an OCR benchmark.
