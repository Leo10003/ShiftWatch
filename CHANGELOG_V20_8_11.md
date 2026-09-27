# v20.8.11 — Per-block candidate diagnostics (no recognition changes)

The two v20.8.10 workflows now agree but both score only 4/6 correct locations. Tuesday is consistently recovered; Thursday and Saturday fail. This diagnostic release exports the two best **production** candidate scores for every physical block, and separate best two **experimental shadow** scores per block. Scores include confuser penalties and source origins; only approximate vertical deciles are exported. No photographs, OCR text, names or exact crop coordinates leave the device.

- Diagnostic schema 12 adds `productionByBlock` and `shadowByBlock` to the two viewer decision arrays.
- The unified analyzer now displays the expected block 2 evidence for Thursday and Saturday separately.
- Two additional JVM-focused tests verify deterministic per-block reporting and no change to recognition decisions.
- No production acceptance, candidate generation, time extraction, week inference or profile persistence changes. The v20.8.10 lifecycle gating remains intact.
- Even if an experimental shadow candidate scores highly, it is **not** accepted as a shift.

After CI passes, scan the same image using both preview-timing workflows, export two schema-12 JSON diagnostics and run `tools/analyze-scans.ps1`. Compare expected block 2 scores on Thursday and Saturday before considering any crop or scorer change. Do not uninstall or clear application data.
