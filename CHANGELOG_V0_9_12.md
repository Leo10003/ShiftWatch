# ShiftWatch 0.9.12

## Table-span-aware vertical grid detection

- Keeps recognition scoring unchanged.
- Stops assuming the handwritten rota table spans the full bitmap width.
- Fits the seven weekday cells from observed weekday-header OCR centers when available.
- Searches for the six internal vertical separators around those fitted table positions.
- Falls back to full-image spacing only when header geometry is unavailable.
- Validates separator gaps against the fitted table spacing.
