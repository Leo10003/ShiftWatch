# ShiftWatch v20.7 verified development handoff

Exact source baseline: user-provided v20.6.1 compilation-fix project. Latest patch source: v20.7 Adaptive Foundation / code 208. The detailed historical handoff follows this update.

## Confirmed root causes from current code
- AssistedScheduleImage previously opened the photo as a modal full-screen dialog as soon as the review appeared; this hid the supposedly independent manual controls while slow analysis ran. v20.7 requires explicit user action to open that viewer.
- Review suggestions were deduplicated by weekday; a valid second shift on a weekday could disappear. New slot-aware merge and protected user weekday tombstones coexist.
- On session resume v20.6.x intentionally discarded ALL handwritten drafts, including confirmed user edits; v20.7 saves and restores explicit human evidence and week/selection state.
- Old checkpoints could be serialized after a successful planner commit and resurrect a completed import; v20.7 uses epoch pause/close guards.
- Live recognition did not expose intermediate OCR evidence/timings; v20.7 adds early provisional header metadata, named progress and sanitized stage duration export.
- Pending OCR and visual matcher CPU work could be repeatedly restarted by review draft recomposition; v20.7 avoids initial auto full-screen analysis and binds expensive viewer matching to a single worker.

## Release gates run here
- 31/31 pure source-unit tests across nine offline-safe classes passed with a local JUnit-compatible test harness. This was not the Android Gradle test task.
- 11/11 additional synthetic regressions passed in the same source-development cycle.
- Kotlin PSI syntax parser: see docs/RELEASE_VALIDATION.md after final check.
- Android Gradle build and physical device benchmark NOT verified.

## Next three milestones, without guesswork
1. Device-level latency/ANR/integration testing of the v20.7 nonblocking review and atomic import, including widget smoke tests and restart/resume. Resolve all actual Android compilation errors if any appear.
2. Accumulate user-authorized, labeled rota examples via the opt-in export; add crop sharing only with explicit permission. Measure identity-day precision/recall, wrong READY rate, block-time ownership and photo quality effects. Never treat an OCR suggestion as proof.
3. Train/evaluate a small offline handwriting embedding model only AFTER the labeled set exists; add model-versioned descriptors and a measured improvement gate before integrating TFLite/ONNX. Add perspective rectification only with exact preview/OCR inverse-coordinate tests.


---

# v20.6 VERIFIED SOURCE UPDATE (2026-09-27)

**Source inspected:** user-uploaded `ShiftWatch-Premium-v20.5-RecognitionStateIntegrity(1).zip`, `app/build.gradle.kts` confirmed version 20.5 / code 205. The uploaded ZIP contained build caches; this source release deliberately excludes them. Earlier sections were authored before that v20.5 source inspection and are retained as historical context, not the latest status.

**v20.6 work performed from that exact source:** review opens immediately on photo selection; photo decoding is off main thread; provisional metadata/preview become available during OCR; independently accessible manual calendar and clock entry, one-tap week confirmation, and cancel-analysis controls; live analysis uses generation-based result rejection; explicit manual dates, corrections, removal and checkboxes survive late automated results; result merging uses weekday-scoped tombstones; session checkpoint writes have shared monotonic revision protection against asynchronous stale writes; header/time suggestions are prepared off main; privacy-preserving opt-in JSON diagnostic export is in the review UI. Preview decoding now uses the same sampling convention as the OCR image to avoid pixel-coordinate drift. v20.5 tracking, widgets, visual handwriting prototype learning and styling remain in source.

**Verified checks for v20.6:** pure Kotlin merge regression run; pure date/time/structural Kotlin compilation run; Kotlin UI frontend syntax sweep with Android SDK dependencies unavailable. **NOT Android Gradle/device-build verified.** Do not label this build production-ready without `Build > Make Project` plus manual slow-OCR stress tests on the target phone.

**Regression to run first on a device:** open photo and immediately add two manual shifts without waiting; change week, remove a wrong detected candidate, correct an existing time, deliberately let late OCR finish and ensure all explicit decisions remain intact. Cancel scanning and save while an expensive handwriting pass is still running. Confirm no stale session reappears after successful planner commit. Export one sanitized JSON, verify it contains no employee name, image or raw OCR transcript.

**Known limitations:** Scan pipeline still returns its OCR assessment at completion rather than fully streaming per-stage progress. Activity-local review state is Compose state rather than a dedicated `ViewModel`/`StateFlow` reducer. No instrumented Compose tests or physical-device latency measurements were possible here. The JSON export includes a total elapsed-scan estimate, not independently measured timings for each ML pipeline phase. Continue architectural migration next rather than loosening OCR/name thresholds.

---

# ShiftWatch — Persistent Development Memory and Next-Patch Specification

**Handoff edition:** 2026-09-27  
**Current user-tested build:** v20.5 (reported in the ongoing conversation and current screenshots).  
**Latest complete project whose source was available for this handoff:** uploaded v20.2 ZIP.  
**Important version caveat:** the v20.3–v20.5 work and behavior below are supported by the ongoing development history and screenshots, **not** by a fresh source-level inspection or Android build of the v20.5 project. Obtain and inspect the latest v20.5 source before applying code changes; do not patch v20.2 and label the result v20.6.

## 0. Read this first (next developer / next conversation)

ShiftWatch is an **offline-first Kotlin/Jetpack Compose Android shift tracker**. It records shifts, rounds tracked time to 30-minute increments, displays monthly hours, supports planned/manual entries and a quick-action widget, and imports photographed printed/handwritten workplace rotas. Its most difficult component is **Smart Rota Import**, which must identify the intended employee, determine the correct document week and day columns, find the employee's *physical shift block*, and assign the start time of *that block only*. Most historical errors came from mixing these inference stages or trusting low-quality data too early.

**Current most urgent user-reported problem:** while image analysis is running, Smart Rota Import locks the user out of manual input. Scans are slow enough that manually importing several days can take minutes. Make manual input available *immediately* and fully independent of ongoing name recognition, time OCR, and training. No expensive job may block tap handling, editing, deletion, week selection, or saving explicitly confirmed shifts. Protect human edits against late automatic updates.

**Priority order:** (1) nonblocking, edit-safe analysis; (2) single authoritative session/state pipeline; (3) diagnostic export + reproducible test cases; (4) rigorous document-week/time-block ownership; (5) improve measured recognition accuracy and speed. Do not loosen name thresholds, promote weak matches, or add fallback heuristics before the state/concurrency layer is correct.

**To continue:** request/upload the current **v20.5 Android Studio source ZIP**, add this document under `docs/SHIFTWATCH_PROJECT_MEMORY.md`, inspect `PlannerActivity.kt`, `ScheduleImporter.kt`, `ScheduleImportCoordinator.kt`, `OfflineRotaVision.kt`, `RotaDateAuthorityEngine.kt`, `RotaStructuralTimeEngine.kt`, `RotaVerificationEngine.kt`, and `ShiftStore.kt`; implement the next patch without removing widget, tracking, review, or manual-edit features.

## 1. Immutable product requirements

- Android Studio project with Kotlin + Jetpack Compose; offline/on-device image processing and local persistence. Do not add a cloud AI dependency without explicit user authorization.
- Shift tracking: clock in/out, rounding to nearest half hour, monthly totals and month navigation, manual add/edit/delete, overnight shifts, functional widget and in-app synchronization, backup/import integrity.
- Premium, responsive UI with usable graphical date/time entry, clear bottom-navigation affordances and polished original branding. Preserve existing logo/typography in the actual project; do not substitute a new logo.
- Smart rota import: printed/mixed/handwritten sheets; weekday/date authority; robust grid and physical block geometry; OCR plus local handwriting examples/hard negatives; *explicit, reversible user review before planner insertion*.
- Case-insensitive employee entry; retain multiple valid handwriting styles. Distinguish a proposed identity from a confirmed identity and from a selected-for-import shift.
- Never synthesize planner data from a weak name score, unsupported start time, an unconfirmed fallback week, or another row's time. Better **REVIEW**/**CHECK DATE**/**CHECK TIME** than a plausible incorrect **READY**.
- The user can remove any candidate, clear selection, edit, undo removal, and add a correct shift manually even while analysis runs.

## 2. What can be verified versus what remains unverified

### Verified from the *uploaded v20.2 source* (not necessarily the current v20.5 implementation)

- `app/src/main/java/com/example/workshifttracker/PlannerActivity.kt`: Smart Rota Import and `ZoomableRotaImage` Compose UI, review draft/selection state, manual time picker, saved identity model, multiple `LaunchedEffect`-based recognition paths, `visionBusy` guard, marker/tap logic, and session persistence. In this version, saved-profile and text-first matching each have a separate launch path; high-scoring suggestions can invoke the expensive `onTap()` resolver. **Check whether these remain in v20.5 before changing them**.
- `ScheduleImportCoordinator.kt`: suspend/cancellation-aware IO entry into the older `ScheduleImporter.recognize` callback engine. Cancellation support at startup does not guarantee every downstream CPU operation responds promptly.
- `ScheduleImporter.kt`: OCR/token geometry, document/week analysis, time atlas, candidate/draft generation and physical-block time ownership. This is large and historically prone to duplicate fallback logic.
- `OfflineRotaVision.kt`: employee OCR match and local handwriting-prototype matching, learned styles and hard negatives.
- `RotaDateAuthorityEngine.kt`: document header date inference; `RotaGridModel.kt`: normalized weekday and block geometry; `RotaStructuralTimeEngine.kt`: consensus/time-band solving; `RotaVerificationEngine.kt`: separates proposal from verification; `ShiftStore.kt`: local planner/session/profile storage.
- The uploaded v20.2 ZIP includes 15+ focused Kotlin test files covering dates, time recognition, identity policy, structural time and verification. Preserve and expand these tests instead of replacing them.

### Later release history / user-observed behavior (must be checked in current source)

- v20.3 aimed to improve date-sequence inference, Unicode superscript recognition, handwriting recall, and tap guards.
- v20.4 aimed to use dedicated header OCR, unify document-week authority, make review selection explicit and put physical block diagnostics in the viewer.
- v20.5 aimed to remove competing automatic time paths, use structural blocks for automatic time, keep blue marker suggestions unconfirmed, and show optional row diagnostics rather than heavy overlays.
- Recent user screenshots show: (a) blue identity suggestions are found, (b) manual marker confirmations can produce correctly formatted times, (c) the review has at times shown `CHECK DATE` for every checked row despite recognizable printed week headers, and (d) the user now reports UI input locking during slow analysis. **These screens are evidence of interface state, not independent ground truth for identity/date/time accuracy**.
- The latest reviewed image showed four proposed/checked shifts for **21, 22, 23 and 26 Sep 2026**, all labelled `CHECK DATE` with disabled planner import. Do not infer those are necessarily the true shifts without user-provided ground truth.

## 3. Known failure modes and non-regression contract

| ID | Failure / symptom | Correct behavior | Test required |
|---|---|---|---|
| CONC-01 | Manual name/date/time input locked while OCR/handwriting job works | Manual controls respond while scan runs; edits remain authoritative | Start slow fake analysis, tap/add/edit 4 shifts and confirm week immediately |
| CONC-02 | Repeated taps cause freeze, ANR or duplicated work | Tap and view actions stay responsive; expensive jobs are deduplicated/cancelled or serialized | Stress 50 mixed zoom/tap/edit actions during analysis; inspect ANRs |
| STATE-01 | Late analysis erases/changes manually confirmed candidate | Confirmed user data immutable to inference; background suggestions merge separately | Confirm Monday time, emit stale conflicting scan result, assert unchanged |
| STATE-02 | New rota inherits previous selected shifts/week/markers | Session keyed by document fingerprint plus import session generation | Start rota A then B while A runs; ensure A's result never reaches B |
| STATE-03 | UI shows selected READY although date/time unresolved | Explicit selection and independent date/time state; import gated correctly | Recreate checked `CHECK DATE` screenshot; verify clear actionable week confirmation |
| DATE-01 | Date OCR reads wrong year/week despite header | Consistent, explicitly auditable header evidence; one-click manual week confirmation | 14–20 Sep and 21–27 Sep test; wrong fallback Dec 2024 |
| DATE-02 | Fully consistent header still leaves all rows `CHECK DATE` | Shared `RotaWeekEvidence` exposed to all draft rows or one confirmation action | Seven coherent header dates, four selected rows |
| TIME-01 | 16:00 employee receives 09:30/13:00/20:00 | Physical weekday column + block ID must agree before auto time | Wrong-row taps; 16:00 majority with isolated 20:00 OCR |
| TIME-02 | `9³⁰`, `13⁰⁰`, `16⁰⁰` unrecognized | Specialized small-vocabulary time recognizer and Unicode normalization | Parametrized superscript/subscript cases |
| ID-01 | Trained profile finds 0–1 of 4–6 actual workdays | Prioritize measured recall via suggestion queue, not unsafe auto-approval | Gold-labelled example across seven columns; count precision/recall |
| VIEW-01 | Tiny photo and heavy footer obstruct tap/zoom | Fit-width overview, interactive inspect, optional diagnostics outside photo | Screen sizes/emulator gestures + real device |
| DATA-01 | Invalid/overlapping records or false duplicate flags | Commit-aware planner writes and week-aware duplicate identity | Partial commit, duplicate retry and backup-overlap tests |

## 4. Next high-impact implementation: nonblocking, human-first analysis

### Required interaction contract

1. As soon as a photo is selected, open the image/review UI and show **Add shift manually**, **Tap a name**, **Set rota week**, **Edit time**, **Remove**, **Undo** and **Save confirmed**; do not wait for OCR to finish. Show scan progress unobtrusively (`Analyzing in background · Stop scan`).
2. A user can select and edit independent of `analysisStatus`. `visionBusy` controls only duplicate expensive **recognition jobs**, not pointer taps, panning, dragging, explicit marker confirmation, manual time picker, checkboxes, or planner save.
3. If a tap needs expensive inference, show an immediate tappable **pending marker** and optional time picker. Resolve on `Dispatchers.Default`/IO and deliver suggestions later; do not synchronously call repeated structural/OCR calculations in Compose UI or `Canvas.draw`.
4. Use a single job coordinator **per import session** with a new monotonically increasing `sessionGeneration` on every photo change/restart. Every result includes the generation and document fingerprint. Ignore any stale completion. Cancel the previous job when replacing the photo or pressing Stop.
5. **Never overwrite user decisions.** Keep `UserDecision` records separate from `RecognitionSuggestion`/`DocumentAnalysisSnapshot`; merge by stable image-normalized candidate ID, not by list index or weekday column alone. Track a per-candidate `userRevision`. Any model result older than that revision is display-only or discarded.
6. Preserve confirmed drafts and the current review even when analysis is cancelled, crashes, or times out. Cancelling means stop recommendations, **not undo user input**. Persist review state asynchronously and debounce frequent writes.
7. `READY` requires *explicitly selected*, confirmed employee, verified or manually confirmed rota week, and block-supported or manually confirmed start time. `REVIEW`, `CHECK DATE`, and `CHECK TIME` must remain independently correct. Manual corrections may supersede low-confidence model evidence without making other candidates READY.
8. `Select ready` must not select `CHECK DATE`/`CHECK TIME` entries. `Clear selection` must never delete a manually confirmed identity or erase a time correction.

### Suggested state ownership (illustrative, adapt to the actual latest code)

- `ImportSessionState`: session ID, document digest, generation, image URI, nullable document-week resolution with provenance, analysis progress and cancel handle, immutable confirmed/rejected decisions, visible suggestions and stable selected IDs.
- `RotaWeekEvidence`: source (`HEADER_GEOMETRY`, `TEXT_SEQUENCE`, `USER_CONFIRMED`, `FALLBACK`), candidate week, confidence, contradictions, token locations. **Fallback is always unverified**.
- `TimeEvidence`: time, weekday column, exact physical block ID + crop bounds, support columns, superscript/OCR variants, mode (`BLOCK_VERIFIED`, `USER_CONFIRMED`, `UNRESOLVED`). Never propagate a time to a different block by ordinal index alone.
- `Candidate`: stable `candidateId = hash(documentDigest, normalizedColumn, normalizedRect, employeeProfileId)`; independent `identityState`, `dateState`, `timeState`, `selectionState`, `userRevision`, `source`, reasons and last-updated timestamp. A confirmed manual entry may need an independent UUID when no OCR candidate exists.
- `AnalysisEvent`: generation, stage (`DECODE`, `GRID`, `HEADER`, `TIMES`, `IDENTITY`, `VERIFICATION`), progress/status, elapsed duration, diagnostics and suggestions; emitted as Flow/StateFlow and safely merged on main.

Use one immutable `StateFlow<ImportSessionState>` or equivalent unidirectional state owner (e.g. ViewModel). A Compose screen should render it and dispatch short intent actions; do not allow multiple unrelated `LaunchedEffect` callbacks to mutate shared marker lists directly. Put decoding, grid, OCR, handwriting matching and image descriptors on background dispatchers. Keep an in-memory analysis cache keyed by image digest and model version. Limit CPU concurrency to avoid starving the UI; cancellation checks must exist between expensive stages and inside long-running bitmap loops where possible.

### Suggested two-stage latency strategy

- **Fast path:** render image and session controls first; attempt cached grid/document week and cheap OCR matches; reveal any available hints progressively. The UI must be fully interactive from the first frame. Avoid promising a fixed number of seconds until benchmarked on the target phone.
- **Deep path:** focused header OCR, time atlas, handwriting embedding/prototype comparisons, multiple OCR variants, confidence fusion; yield results as separate cancellable events and coalesce UI updates rather than recomputing the entire document after each tap.
- Expensive analyses may finish after the user manually completes all shifts; on completion, enrich diagnostics/remaining suggestions only. Never rebase or edit confirmed manual records automatically.

### Pseudocode (intent, not a drop-in patch)

```kotlin
fun dispatch(intent: ImportIntent) {
    when (intent) {
        is ImportIntent.UserConfirmed -> updateState { it.withConfirmedDecision(intent) }
        is ImportIntent.UserEdited -> updateState { it.withUserEdit(intent) }
        is ImportIntent.UserDeleted -> updateState { it.withRemovedCandidate(intent) }
        is ImportIntent.StartScan -> launchNewGeneration(intent.imageDigest)
        is ImportIntent.CancelScan -> cancelAnalysisOnly()
    }
}

suspend fun receiveAnalysis(event: AnalysisEvent) {
    if (event.sessionId != state.value.sessionId || event.generation != state.value.generation) return
    updateState { current ->
        current.mergeSuggestionsPreservingUserRevisions(event)
    }
}
```

**Do not** implement the above only by removing `if (visionBusy) return`: that avoids the visible lock but can queue duplicate heavy work and cause another ANR. Decouple input from inference and guarantee single-flight background analysis instead.

## 5. Recognition/verification strategy (after concurrency)

- Resolve and persist **document week once per document**, not independently per draft. Use multi-column, coherent OCR header observations and focused header image crop; expose exact evidence. If uncertain, offer **Confirm detected week** at the top. Manual confirmation updates all draft dates but does **not** approve time or identity.
- Resolve grid geometry into normalized seven weekday columns with per-column row boundaries and a **spatial** block ID. Only then associate a proposed identity bbox with a day/block. Never use a nearest arbitrary number on the page as a time.
- Independently solve a time label for each physical block from the block's label corner, time atlas and aligned cross-column consensus. Superscript time variants should be normalized. A chronological-order prior may break ties but must not invent unsupported later hours.
- Keep identity matching as candidate generation with clear source/provenance. Saved handwriting prototypes and negative examples inform suggestions; **no recognition threshold alone** turns a suggestion into a planner shift. User corrections should grow the local profile without poisoning the next run.
- Use a small gold-labelled dataset from user-authorized rotas. Before additional threshold changes, measure employee-day precision/recall, date-week accuracy, block ownership, time accuracy, false READY rate, analysis latency and UI responsiveness by device.

## 6. Proposed built-in, user-controlled diagnostic export

**High leverage:** introduce **Export import diagnostics** in the review menu. It should write an ordinary local `.json` file containing `schemaVersion`, `appVersion`, `modelVersion`, `sessionId`, **hashed** image digest, normalized resolution, stage timings, OCR source pass, weekday-header hypotheses, grid boundaries, per-block time evidence, suggestion/verification reasons, candidate states, user-confirmation events, job cancellations and sanitized error codes. Create one small output per problematic rota, named `shiftwatch-diagnostic-YYYYMMDD-session.json`.

**Privacy:** employee names, saved handwriting crops, raw rota photographs, full OCR text and precise location must be OFF by default. Export an optional anonymized crop/ground-truth dataset **only** after an explicit user opt-in, since a workplace rota contains third-party personal data. Store diagnostics locally and let the user decide whether to share.

The companion `DIAGNOSTIC_TEMPLATE.json` in this handoff shows the target versioned shape; it is **not** a log from the running app. The same structured log should make future fixes evidence-led instead of based entirely on screenshots.

## 7. Source navigation (verified v20.2 names; recheck in v20.5)

| File | Responsibility / likely next edit |
|---|---|
| `PlannerActivity.kt` | `ImportReviewSheet`, `ZoomableRotaImage`, pointer/tap paths, checkboxes, date picker, progress UI; move mutable state/analysis coordination out of UI. |
| `ScheduleImportCoordinator.kt` | Make the complete scan a cancellable event stream; stage and generation ownership. |
| `ScheduleImporter.kt` | Decode, OCR passes, source-tagged tokens, time-block candidates; eliminate conflicting fallback and synchronous per-tap computations. |
| `OfflineRotaVision.kt` | Heavy handwriting comparison: cache image descriptors; batch background matching and expose reasons. |
| `RotaGridModel.kt` | Normalize per-weekday physical block IDs/geometry. |
| `RotaDateAuthorityEngine.kt` | Ranked, auditable source-of-truth week decision; year rollover and OCR-error tests. |
| `RotaTimeRecognitionEngine.kt`, `RotaStructuralTimeEngine.kt` | Superscript parsing; independent block-time support; abstention on ambiguity. |
| `RotaVerificationEngine.kt` | Separate support, contradiction and READY policy; eliminate self-reinforcing date confidence. |
| `ShiftStore.kt` | Safe session snapshot and user-correction persistence; actual successful commit count and duplicate semantics. |
| `app/src/test/...` | Expand pure Kotlin engines plus state reducer concurrency tests and Compose instrumentation. |

Preserve app ID and any migration requirements so users retain existing planner records, handwriting examples and settings across upgrades.

## 8. Test protocol and release gates

**Before implementation:** archive the exact current v20.5 source, export the screenshot/repro scenarios, confirm app build/version, run a baseline Gradle build, then reproduce freeze on a physical phone or emulator with timing logs. Do not silently replace older version sources with inferred newer code.

**Core automated state tests:** load slow fake analyzer; add Monday manually during scanning; confirm wrong-date override; remove Tuesday while a late result is queued; switch to a different photo; cancel and resume; force analyzer exception; verify edits/selected IDs persist and stale results are ignored. Execute stress gestures and pinch-zoom while progress updates emit. Verify no frame-long synchronous OCR calls from Compose drawing or touch callbacks.

**Recognition regression set:** (A) 14–20 Sep header with poisoned December fallback, (B) 21–27 Sep crossing same month, (C) date-like footer notes must not hijack header, (D) 09:30/13:00/16:00/18:00 labels including small superscripts, (E) isolated 20:00 hallucination must not override supported 16:00, (F) employee handwriting style variations/mixed case, (G) missing row boundaries/tilted images, (H) manually confirmed shift must not disappear when a new name prototype is added. Explicitly record expected identity/days from **user-provided ground truth** rather than assuming screenshots contain the correct labels.

**Release gates:** build and run app on Android device; smoke-test widget and ordinary shift tracking; perform import and manual review while scanning; create and edit all planned shifts; ensure no accidental insertion; confirm app state survives rotation/process recreation where supported; inspect crash/ANR traces and memory peaks; export one sanitized diagnostic JSON and validate its schema. Package source with `CHANGELOG`, this handoff, test commands and known limitations. If Android Gradle cannot run in the build environment, label the artifact **not full-build verified** and do not claim it passed.

## 9. Open decisions / information to obtain (do not guess)

- Obtain the **current v20.5 project ZIP** (the only full source uploaded into this execution environment is v20.2). Do not assume code previously claimed patched still exists exactly as described.
- Record the target phone model/Android version and Android Studio compile/ANR logs. Screenshots show emulator testing, but no reliable device performance timings are available here.
- Obtain optional **explicit ground truth** for one representative week: true Monday date, actual employee's working days, corresponding start times and which matches in the photo are real. Anonymize crops if third-party names are visible.
- Determine whether automatic OCR may update unselected review suggestions after the user saves confirmed drafts, or whether the user wants scanning to stop immediately at save. Default: preserve saved drafts, cancel or ignore later results.
- Decide a retention/privacy policy for diagnostic exports and model prototype crops. Default: local only, explicit user action to export.

## 10. Short prompt for the next development session

> Continue development of ShiftWatch from the attached **current Android Studio source project** and `SHIFTWATCH_PROJECT_MEMORY.md`. First verify the version and build the project. Highest priority: manual image-rota entry must **never lock while OCR, time recognition or handwriting analysis is running**, while late inference must **never overwrite user corrections**. Introduce a per-session, generation-keyed state owner and cancellable progressive event pipeline; then add opt-in sanitized diagnostics and the listed regression tests. Preserve existing app identity, planner data, widget, logo, review/remove/undo workflows and offline processing. Analyze current code before patching and report precisely which tests/build checks were run and which remain unverified.

---
**Maintenance rule:** On each release, update this file's edition, verified source version, completed changes, currently reproduced bugs, regression results, open blockers, and next three priorities. Archive past decisions rather than silently erasing failed approaches. This file is a handoff aid, not runtime app memory: the app's diagnostic JSON feature described above must be implemented in code separately.
