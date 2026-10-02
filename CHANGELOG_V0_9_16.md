# ShiftWatch 0.9.16

## Week-level row consensus

- Adds a conservative within-physical-block row-consensus policy for handwriting identity matching.
- Strong automatic matches that are clear row outliers against at least two accepted peer weekdays are quarantined instead of being shown as automatic matches.
- Missing weekdays may receive one explicit-review row candidate when its normalized position inside the dominant physical block agrees with the weekly row cluster.
- Lower-ranked recovery is limited to the top five candidates, requires OCR-derived evidence, and must remain within 0.035 adjusted-score points of the column leader.
- Recovery remains review-only: it never becomes an automatic planner selection or training sample.
- Raw identity scoring, acceptance floor, rescue floor, and confuser thresholds are unchanged.
