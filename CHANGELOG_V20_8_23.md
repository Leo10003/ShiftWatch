# ShiftWatch v20.8.23 — visible installed app version

**Android UI release.** This is the first APK-version change since v20.8.18; versions v20.8.19–v20.8.22 only updated offline developer tooling.

- Bump installed Android app `versionName` to `20.8.23` and `versionCode` to `229`.
- Show the installed APK's `versionName` on the home screen, Planner header, and full-screen Select shifts / Review detected table header. The display queries the installed package; it is not hardcoded independently per screen.
- Maintain existing scan/recognition algorithms, offline-only operation, stored handwriting profiles, saved shifts, and backup formats without changes.
- Validation: run `tools/dev-preflight.ps1` and `:app:assembleDebug` on Windows; install **over** the existing app (`adb install -r`) without uninstalling or clearing data. On-device app/version label and persistence checks remain necessary.

## Verification

1. Launch the newly built app and verify `v20.8.23` on the home header.
2. Open Planner and verify `v20.8.23` beside its title.
3. Open a rota image, enter full-screen Select shifts, and verify `v20.8.23` beside its title.
4. Verify saved handwriting profiles and confirmed shifts are still intact after in-place upgrade; do not reset the app or emulator.
