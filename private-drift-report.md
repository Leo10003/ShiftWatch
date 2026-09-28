# ShiftWatch scan drift comparison

Read-only diagnostic comparison; not a recognition accuracy score or a profile identity test.

Files: `scan1.json` vs `scan2.json`

App versions: 20.8.18 vs 20.8.18

OCR fingerprint: MATCH

Matching OCR geometry supports comparing recognition decisions with the same observed layout, but does not prove identical images, equal training examples, or unchanged runtime conditions.

| Day | Production 1 | Production 2 | Top 1 → 2 | Replay 1 | Replay 2 | Candidate counts 1 → 2 |
|---|---|---|---:|---|---|---:|
| Mon | accepted_normal (B1) | accepted_normal (B1) | 0.700 → 0.700 | accepted_normal (B1) | accepted_normal (B1) | 18 → 18 |
| Tue | accepted_normal (B3) | accepted_normal (B3) | 0.792 → 0.792 | accepted_normal (B3) | accepted_normal (B3) | 26 → 26 |
| Wed | rejected_below_rescue_floor | rejected_below_rescue_floor | 0.429 → 0.429 | rejected_below_rescue_floor | rejected_below_rescue_floor | 28 → 28 |
| Thu | accepted_normal (B3) | accepted_normal (B3) | 0.797 → 0.797 | accepted_normal (B3) | accepted_normal (B3) | 23 → 23 |
| Fri | rejected_below_rescue_floor | rejected_below_rescue_floor | 0.431 → 0.431 | rejected_below_rescue_floor | rejected_below_rescue_floor | 22 → 22 |
| Sat | rejected_below_rescue_floor | rejected_below_rescue_floor | 0.443 → 0.443 | rejected_below_rescue_floor | rejected_below_rescue_floor | 21 → 21 |
| Sun | accepted_normal (B3) | accepted_normal (B3) | 0.699 → 0.699 | accepted_normal (B3) | accepted_normal (B3) | 21 → 21 |

Production decision changes: none.
Top/runner score changes: none.
Hypothetical replay changes: none.
Candidate pipeline changes: none.

Accepted decisions are suggestions only; no confirmed shift labels are inferred. Experimental replay never changes production.
Training-profile changes are not captured by these sanitized diagnostics; do not attribute differences to training without independent evidence.
