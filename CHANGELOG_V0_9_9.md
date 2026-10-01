# ShiftWatch 0.9.9

## Preserve authoritative weekday ownership

- Keeps recognition scoring unchanged.
- Preserves OfflineRotaVision.Match.column on automatic viewer markers.
- Stops re-deriving a recognized marker's weekday from its raw x coordinate when the matcher already knows the column.
- Uses the preserved column for rendering, diagnostics, and marker merging.
- Falls back to x-based column lookup only for legacy/cache/manual markers.
