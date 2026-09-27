# ShiftWatch 17.2 — Strict Row Time Ownership

- Fixed preview markers showing times that the final resolver could not justify.
- Added one precision-first owned-row resolver shared by automatic drafts and marker previews.
- Time evidence is restricted to a narrow label corridor at the top-left of the employee's own physical schedule row.
- Cross-column rescue is allowed only from the same vertical row with tight perspective tolerance.
- Removed permissive handwritten fallbacks that could borrow 09:00/20:00 from older rows.
- Historical semantic priors can no longer override missing evidence on the current image.
- When no trustworthy row-owned time exists, the UI shows `match` / `TIME UNRESOLVED` instead of a fabricated clock time.
