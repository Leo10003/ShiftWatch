# ShiftWatch v20.7 — validation report

**Baseline:** uploaded v20.6.1, updated to versionCode 208 / versionName 20.7.

## Checks executed in this development environment

- Kotlin compiler compiled nine standalone pure recognition/reducer/telemetry/evaluation source units.
- A local JUnit-compatible runner compiled and invoked the **actual source test methods** from nine offline-safe test classes: **31 passed, 0 failed** after addressing three pre-existing regression expectations (bare recent header proposal, ambiguous 900, and repeated learned block prior). The runner is not Gradle/JUnit and does not exercise Android UI code.
- Eleven extra, focused synthetic Kotlin regressions passed in the same source-development cycle (multiple daily slots, stale sessions, independent evidence, geometry, time/date and evaluation precision).
- Kotlin PSI parser checked all **47 Kotlin source and test files: 0 grammar/syntax errors**. Parser checks cannot establish Android API compatibility or type-check all Compose code.
- Attempted `./gradlew :app:compileDebugKotlin --offline --no-daemon`. The Gradle wrapper still attempted to retrieve its missing distribution from `services.gradle.org` and failed with `UnknownHostException`; **no full Android build or runtime assertion is claimed**.

## Must-run acceptance test in Android Studio/on device

1. `Build > Make Project` on the extracted v20.7 root. Share any compiler errors from the exact source. Preserve app package and local data when installing over v20.6.1.
2. Choose an actual handwritten rota. **The manual review must be available immediately** without forcing open the full-screen image while OCR continues. Add two correct manual dates/times and press week confirmation or leave those explicitly dated entries independently importable.
3. While analysis runs: tap/correct another shift, remove one wrong candidate, zoom the photo on demand, and try changing the week. Verify manual entries and checkboxes remain unchanged after a late scan result.
4. Turn off recognition mid-scan. Verify all explicit decisions remain and no extra shifts appear. Confirm a manually chosen calendar date does not rebase after another header suggestion.
5. Test two *non-overlapping* shifts on the same day and confirm both survive resume. Attempt an overlapping reviewed import and verify **nothing** is partially added.
6. Test a clear 16:00 employee in the presence of a weak 20:00 OCR result. Points close to uncertain row boundaries must stay unresolved until corrected; a learned prior without current-photo evidence must not silently assign time.
7. Viewer: open it explicitly, select Fit Page then Fit Width, scroll vertically at 1x and double-tap near a name. Confirm overlay boxes stay aligned with the photo while zooming/panning.
8. Save while OCR still runs, reopen the app and verify no stale session is resurrected. Check the clock widget/rounding/monthly history still function.
9. Export ordinary sanitized diagnostics and verify no employee name, photo, full OCR transcript or calendar dates appear. Separately export opt-in confirmed labels and check only weekday/time/block metadata are present.
10. Measure real-device scan duration per stage, peak memory, input latency and repeated-tap ANRs. This environment **has no real device measurements**.

## Not yet demonstrated

- A trained/offline neural handwriting embedding model; requires permissioned image/label data and independent train/test metrics.
- Full automatic photo perspective correction with round-trip preview/tap/OCR transforms.
- Complete migration of review drafts and learning to a single ViewModel/StateFlow owner (a generation-guarded pure reducer has been wired into the scan lifecycle; legacy Compose review state still exists).
- A successful Android Gradle build, instrumented Compose gesture tests or production readiness.
