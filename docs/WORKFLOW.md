# ShiftWatch development workflow (start here)

This repository replaces the ZIP-as-source-of-truth approach. Do not publish workplace rotas,
identifying handwriting crops, unredacted logs or raw diagnostic exports in the repository.

## One-time setup (Windows / first-time Git user)

1. Create a **private** GitHub repository named `ShiftWatch`; leave it empty.
2. Extract this ZIP, open the project in Android Studio, and run **Build > Make Project**.
3. Open Android Studio's terminal in the project folder. Run `git init`, `git branch -M main`,
   `git add .` and `git commit -m "Baseline v20.7.1: workflow and compile corrections"`.
4. Add the private repository as a remote using the URL GitHub shows on its setup page:
   `git remote add origin <your private repository URL>` followed by `git push -u origin main`.
   Authenticate through Git Credential Manager or GitHub's normal sign-in; never paste an access
   token into this chat.
5. In GitHub, open **Actions** and check the **Android quality gate** workflow. On success it
   publishes a debug APK and test/lint reports for that exact commit. Never treat a manually
   compiled/untested ZIP as an approved release.
6. Create the `develop` branch (`git switch -c develop && git push -u origin develop`). Use short
   `fix/...` or `feature/...` branches based on `develop`, open a pull request to `develop`, and
   only merge after the Android quality gate and the applicable on-device checks pass. Protect
   `main` and `develop` from direct pushes in repository settings when ready.

Connecting the optional ChatGPT GitHub integration permits repository-oriented follow-up work
once you authorize it. Nothing in this ZIP automatically creates a repository or pushes code.

## Every bug report

- Use GitHub Issues (`Recognition regression` or `Build or stability failure` templates).
- Record **expected vs actual**, exact version, Android/emulator model, sanitized evidence and
  minimal reproduction steps. Keep private photographs and ground truth off public repositories.
- For handwriting/date/time issues, record a permissioned **gold case** in your private dataset.
  Use `test-data/cases/schema.json` for metadata; the included sample is synthetic, **not** a
  tested production photograph.
- Translate the bug into a test that fails before changing the recognition engine.

## Every code change

1. Reproduce; isolate **one subsystem** and preserve a failing test (or document why not yet
   automatable). Do not change identity thresholds to hide date/time ownership problems.
2. Fix on a branch. Explicit user-confirmed shifts always override asynchronous recognition.
3. Run `python tools/verify_regression_cases.py`, `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
   (`gradlew.bat` on Windows); inspect the full Gradle build output, not IDE inspections alone.
4. Compare the gold dataset's identity precision/recall, document-week accuracy, start-time
   accuracy, false READY count, scan timing and input responsiveness **where labeled data exist**.
   A missing dataset is **unverified**, not a zero-error result.
5. Open a PR with issue link, test evidence, source compatibility and known limitations.
6. Device smoke test: while OCR runs, add/edit/remove a manual shift, confirm week, navigate
   the image, cancel analysis, save, resume, and check widget/monthly history.
7. Only then update `CHANGELOG`, `docs/DEVELOPMENT_MEMORY.md` and release the new version.

## Release gates

- Android compile, offline unit tests and lint on CI. Review and resolve actionable lint output.
- No false planner insertions; no late scan overwrites of manual decisions.
- No regression in previously failing anonymized cases; record precision, recall and abstentions
  separately. Never interpret a synthetic metadata fixture as an OCR accuracy test.
- Physical-device import and widget smoke testing is required before labeling **production-ready**.
- A patch not yet built with Android Gradle is labeled **source-only / unverified build**.

## Recommended near-term issues

1. v20.7.1 compile blockers: fix `assistMessage` scope and state `Set<String>` inference (included).
2. Make date confirmation a visible one-tap action and verify all manual shifts become importable
   if their identity, date and time are genuinely confirmed.
3. Replace remaining Compose-local review state with a single ViewModel/StateFlow session owner;
   instrument cancellation and measure manual-input latency during full OCR.
4. Build a private, consented reference dataset before training/deploying a handwriting neural model.

## Fast local command on Windows

Open PowerShell in the project root and run `powershell -ExecutionPolicy Bypass -File tools/check-local.ps1`
(if permitted by your system policy). It validates fixture metadata, then runs the complete
Android build, unit tests and lint. It does not silently suppress compiler or lint failures.

Read `docs/QUALITY_BASELINE.md` before claiming that recognition is faster or smarter.
