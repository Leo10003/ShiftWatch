# ShiftWatch 0.9.11

## Header-aligned handwritten marker geometry

- Keeps recognition scoring and weekday ownership unchanged.
- Adds a header-derived weekday geometry path based on observed weekday-name OCR centers.
- Handwritten marker rendering uses header-derived cell bounds instead of full-image equal-width cells.
- If weekday headers are unavailable, rendering falls back to the existing ScheduleImporter column geometry.
