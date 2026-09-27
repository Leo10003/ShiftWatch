# v20.8.4 — Viewer recognition state stability

**Observed failure:** Two v20.8.3 diagnostic exports shared the same import session and page-OCR timings, yet displayed different saved-profile suggestions. The full-screen viewer owned its `remember` markers and recognition flags, so closing/reopening its Dialog destroyed completed results and could launch a second recognition pass under the original import session ID.

- Introduce a session-only recognition snapshot owned by the parent review composition. Completed profile results, anonymous scored decisions, OCR hit counts and suggested marker locations can be restored when the image viewer is reopened.
- Match cached results against the encoded profile fingerprint, OCR pass count, OCR token count and review mode. Invalidate rather than reuse results when those inputs differ. Avoid restoring an automatic suggestion over an existing manually confirmed shift in the same weekday/block.
- Export diagnostic schema v6 with a `recognitionRunId` independent of import `sessionId`, plus `recognitionLifecycle` (`fresh_viewer_run` or `reused_completed`). Distinct JSON export times for the same run ID are not independent recognition tests.
- Add cache tests for identical-input reuse, input invalidation and suppression of markers overlapping user-reviewed slots. No handwriting thresholds, dates or structural-time scoring changed.

**Limits:** This fixes state disposal on dialog reopening, not every possible nondeterministic scorer input. It is a session-memory cache, not persistent across process death. Only fully completed saved-profile runs qualify for reuse. The Android build and lint require validation in CI.
