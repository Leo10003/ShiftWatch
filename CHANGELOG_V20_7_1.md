# ShiftWatch v20.7.1 — Compilation repair and continuous development workflow

- Fixed unresolved `assistMessage` reference by ordering Compose state before local callbacks.
- Fixed incompatible `HashSet<String>` inference in selection and manual-date state by explicitly
  using `Set<String>`; dynamic +/- operations now have a compatible declared type.
- Added portable Git/GitHub workflow, Android CI build/tests/lint and artifact uploads.
- Added issue and PR templates, safe fixture schema and a standard-library validator, permanent
  development memory and reproducible release checklist.
- Did not modify OCR confidence thresholds or any serialized planner/widget data.
- Build verification must be completed by CI/Android Studio after the project is pushed.
