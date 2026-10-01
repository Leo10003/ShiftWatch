# ShiftWatch 0.9.14

## Mixed-grid viewer alignment + anchored near-tie review

- Viewer-only: MIXED rota documents now use weekday-header-derived visual column bounds, like HANDWRITTEN_GRID documents.
- Recognition geometry and automatic acceptance thresholds are unchanged.
- Adds a narrow review-only recovery when the leading candidate is inside the accepted row envelope, has a strong positive identity score and positive raw separation, and its same-block near-tie runner sits outside the accepted row envelope.
- Requires adjusted-score gap <= 0.010 and separation shortfall <= 0.030.
