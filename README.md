# ShiftWatch Premium v20.7.1 — Android Studio source

Open the extracted project directory (the folder containing `settings.gradle.kts`) in Android
Studio. Existing application ID and storage are intentionally unchanged.

**Start here:** [docs/WORKFLOW.md](docs/WORKFLOW.md). The one-time GitHub setup is explained for
Windows beginners; CI will run a full Android build, tests, lint and publish a debug APK once the
project is pushed to the authorized repository. No repository was created automatically.

Run locally on Windows: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
Before that, optional metadata checks: `python tools/verify_regression_cases.py`.

Read [CHANGELOG_V20_7_1.md](CHANGELOG_V20_7_1.md) and
[docs/DEVELOPMENT_MEMORY.md](docs/DEVELOPMENT_MEMORY.md).

**Verification caveat:** the ZIP is a patched source project, not a device-tested release APK.
A successful standalone Kotlin test or static-source check does not replace Android CI.
