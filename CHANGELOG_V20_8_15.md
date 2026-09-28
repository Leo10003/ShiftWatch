# ShiftWatch 20.8.15 — Export truncation hotfix

- Use explicit Android ContentResolver output mode `"wt"` when exporting both sanitized scan diagnostics and opt-in confirmed-example JSON. Reusing an existing document previously used the provider-dependent default `"w"`, which may overwrite a shorter document without removing the old trailing bytes.
- Treat a `null` output stream as an export failure in the confirmed-example path, matching the diagnostics path. These exports remain separate.
- Update app version to 20.8.15 (versionCode 226).
- No changes to handwriting recognition, candidate acceptance, profiles, confirmed shifts, or stored data.

## Validation

The patch has been checked against the packaged v20.8.14 baseline and both export call sites inspected. Run GitHub Actions Android build/tests and verify exporting each document over an existing *longer* file, as well as export to a new file, on the actual Android file provider.
