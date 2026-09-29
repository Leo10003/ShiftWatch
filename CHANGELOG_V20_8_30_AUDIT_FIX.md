# v20.8.30 adjustment audit compatibility fix

- Remove unverified `adjustedScore == positiveScore + separationAdjustment` and raw-score arithmetic assertions, which could reject valid production exports.
- Keep the existing strict schema-16 evaluator validation (field types/finite scores, rank/order, counts, top-three consistency).
- Record deviations from simple hypothetical arithmetic identities without claiming the identities describe the Android scoring implementation.
- Add tests for both residual types and preservation of Friday OFF. No Android recognition, version metadata, thresholds, or private data are changed.
