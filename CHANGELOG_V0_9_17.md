# ShiftWatch 0.9.17

## Review-only row consensus and full-name marker rendering

- Removes automatic row quarantine. Week-level row geometry is now review-only and can no longer delete an otherwise accepted handwriting match.
- Review-row tolerance expands conservatively when accepted rows show genuine photographed skew.
- Keeps lower-ranked recovery constrained to the dominant physical block and top five candidates.
- Viewer markers now enforce a minimum name-sized width instead of collapsing to a tiny detected ink fragment.
- Marker clipping uses a larger cell safety inset so mildly slanted weekday divider lines are less likely to be crossed.
- Removes the separate center circle that visually dominated short highlights.
- Recognition score floors and confuser thresholds remain unchanged.
