# ShiftWatch 0.9.13

## Recognition rollback and handwriting-bound overlay

- Restores the pre-0.9.12 vertical-rule detector after the 0.9.12 geometry change reduced recognition recall.
- Carries the runtime handwriting ink box already measured during RotaVision signature extraction.
- Uses the detected handwriting width for viewer highlights while clipping to the owning weekday.
- Keeps persisted handwriting profiles backward compatible.
- Recognition score thresholds are unchanged.
