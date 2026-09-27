# ShiftWatch Premium v20.6.1 — Kotlin Compilation Hotfix

- Capture a stable local Bitmap reference before disposing an image or rendering the review viewer. This removes the three reported smart-cast errors caused by accessing a nullable delegated Compose state repeatedly.
- Keep bitmap disposal tied to the Bitmap instance owned by its corresponding DisposableEffect.
- Remove verified unused imports from PlannerActivity, RotaPerceptionEngine and RotaStructuralTimeEngine.
- Retain v20.6 nonblocking review, manual date/time entry, corrections, and diagnostics unchanged.

The remaining IDE suggestions (redundant qualifiers, spelling, some unused implementation parameters and stylistic modernizations) do not represent this reported Kotlin compilation failure. Those were intentionally not mass-changed in this focused hotfix.

A complete Android Gradle compilation is still required in Android Studio; the Android SDK and Gradle distribution are not installed in this environment.
