# ShiftWatch 20.8.35 — Stable anchored review under score jitter

- Keep automatic acceptance, rescue floors, confuser penalties, and OFF-day protection unchanged.
- Make the amber review-only path tolerate small run-to-run identity-score jitter when two independently accepted weekdays anchor the same physical block.
- A review hint may have slightly negative raw separation only when the leading crop stays in the anchored block, positive identity score is at least 0.54, confuser penalty is at most 0.02, and measured boundary shortfall is at most 0.05.
- Preserve rank-1-only selection and explicit confirmation. Review hints are never imported automatically and never become positive training examples.
- This change does not alter time recognition or automatic acceptance.
