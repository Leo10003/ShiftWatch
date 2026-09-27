# ShiftWatch permanent development memory

## Verified baseline

- Starting point: shipped v20.7 source ZIP, versionCode 208.
- This update: **v20.7.1 / code 209**. Corrects `assistMessage` declaration ordering and
  explicit Compose `Set<String>` state inference; adds repository, CI and regression-data workflow.
- This environment compiled/tested standalone Kotlin subsystems (18 actual-source tests passed) but lacks a verified Android SDK and downloaded Gradle distribution. This release is **NOT full Android build/device verified**.
- Preserve package/application ID `com.example.workshifttracker`, current app artwork, widget,
  shift persistence, import/undo/confirm controls and offline-first behavior.

## Observed ongoing problems (do not call these resolved without device evidence)

- Manually entered shifts may remain CHECK DATE until the week is explicitly confirmed; ensure
  controls are obvious and verified, without auto-approving wrong weeks.
- Handwritten identity recall and physical time block ownership require measurable gold data.
- Inference is slow on some devices. Manual add/edit/delete must remain responsive while scanning;
  cancelled/late recognition must never replace user-confirmed changes.
- Repeated taps and zoom interactions have caused freezes in earlier versions; device ANR traces
  and stage timings are still needed.

## Implementation checkpoints

- v20.6: independent manual review while scan continues; session-generation stale-result protection,
  sanitized diagnostic export, manual correction precedence.
- v20.7: candidate-aware multi-shift/day merge, independent identity/date/time readiness policy,
  staged trace and opt-in labeled metadata. Full ViewModel migration and trained neural model
  remain **proposed**, not completed.
- v20.7.1: minimal compile corrections + CI/reproducible workflow; no recognition-threshold changes.

## Next release priorities

1. Complete Android CI run after GitHub upload; address any Android compile errors exposed by CI.
2. Resolve CHECK DATE usability with one explicit week confirmation and independent time checks.
3. Migrate remaining review Compose state to one generation-keyed ViewModel/StateFlow reducer,
   add instrumented slow-scan gesture and lifecycle tests.

## Evidence and testing rules

- Keep raw rota photos, handwriting exemplars and any coworker details private. Opt in explicitly
  before sharing labeled crops; anonymize diagnostics.
- Save regressions using `test-data/cases/schema.json`. A label-only example is not image proof.
- Report tested command, test result and limitation for every release; do not claim full Android
  validation when only isolated Kotlin or syntax checks ran.
- Update this file with every accepted patch, preserving past failure modes and rejected approaches.
