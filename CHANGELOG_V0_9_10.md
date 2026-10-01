# ShiftWatch 0.9.10

## Stable handwritten weekday rendering

- Keeps recognition scoring and marker ownership unchanged.
- For handwritten seven-day grids, renders marker cells from a uniform seven-column visual grid across the source image.
- This avoids malformed or shifted detected vertical rules placing a marker on a visible day divider.
- Non-handwritten document types continue to use ScheduleImporter.columnBounds().
