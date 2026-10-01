# ShiftWatch 0.9.1

## Corrective review stability

- Removes the temporary OCR-only relaxed near-boundary review edge.
- Restores the tighter ordinary review gate for all leading candidates.
- Preserves the 0.9.0 ambiguous-time review UI.
- Preserves same-block OCR corroboration for the original-rota Wednesday case.
- Leaves automatic acceptance and rescue thresholds unchanged.

This correction follows paired evaluation where the automatic scan surfaced
Wednesday and Thursday review markers even though the manually confirmed
ground truth marked those days OFF, while Friday remained a missed working day.
