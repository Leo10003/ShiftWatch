# v20.7.1 validation and handoff

**Baseline:** exact v20.7 project archive. **Release:** v20.7.1 / versionCode 209.

**Scoped Kotlin compilation correction:** `assistMessage` was referenced in a local function
before its declaration; the definition now precedes `confirmDisplayedWeek`. `selected` and
`manuallyConfirmedDates` explicitly use Compose `Set<String>` state to accept results of `+`,
`-`, `intersect` and `emptySet` without conflicting with inferred `HashSet<String>`.

**Workflow added:** GitHub CI with complete Android build / unit tests / lint and downloadable
reports/debug APK (runs only after repository push); issue and PR templates, privacy-preserving
reference-case schema and validation script, documented branch/release procedure.

**Checks actually completed in this environment:** metadata validator passed for one synthetic
fixture; 18 actual-source unit test methods passed through a temporary local JUnit-compatible
runner (RotaSessionEngine, RotaReviewMerge, RotaReviewPolicy and RotaDateAuthorityEngine);
source-level invariants for the compile corrections passed. `./gradlew --offline` did **not**
reach compilation: missing Gradle 9.5 distribution and `services.gradle.org` DNS resolution
failed. CI YAML was authored, not executed. The local test runner is not shipped as a substitute
for Gradle/JUnit.

**Important:** no auto-generated neural model or measured improvement in handwriting accuracy
is claimed. An Android Studio build and actual-device stress test still gate production release.
