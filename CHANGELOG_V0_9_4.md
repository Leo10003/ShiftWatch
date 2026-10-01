# ShiftWatch 0.9.4

## Row diagnostics and bounded highlighting

- Keeps recognition scoring and acceptance thresholds unchanged.
- Exports a sanitized vertical decile for every viewer marker so correct-block/wrong-row cases can be measured.
- Makes the viewer highlight stay inside the current weekday column.
- Expands the highlight across the usable width of the weekday cell instead of using a fixed 68% pill.
- Reduces highlight height and tap radius to reduce overlap with adjacent handwritten rows.
