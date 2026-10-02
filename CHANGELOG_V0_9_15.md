# ShiftWatch 0.9.15

## Row-aware labelled regression validation

- Adds privacy-safe normalized row position (`rowYPermille`, 0..999) to viewer marker diagnostics.
- Labelled truth export schema advances to v3 and records the confirmed marker row position.
- Labelled corpus replay now validates weekday + physical block + normalized row position.
- Default row tolerance is 18 permille (1.8% of image height).
- Schema-v2 truth remains supported through legacy vertical-decile fallback with a warning.
- Recognition scoring and production acceptance thresholds are unchanged.
