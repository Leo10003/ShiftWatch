# ShiftWatch v20.8.31.1 — measured scoring-boundary diagnostics

Diagnostic-only additive export: retain each already-scored production candidate's
actual `requiredSeparation` and `separationBranch` from the original Android scoring
call. `requiredSeparation` is null only when there is no confuser model. The branch
then reads `no_confuser_profile`. Export a `scoringBoundaryProvenanceVersion` marker
for fresh exports. Existing schema-16 readers can ignore these additive fields.

Neither production scores, thresholds, shift decisions nor local learning is modified.
Old scans are intentionally *not* eligible for the fresh boundary-provenance audit.
Do not combine old and new scans as independent photographs. Friday remains an
explicit OFF control; a good trace does not justify changing acceptance rules.
