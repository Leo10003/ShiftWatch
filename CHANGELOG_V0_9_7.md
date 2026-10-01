# ShiftWatch 0.9.7

## Strict marker cell clipping

- Keeps recognition scoring unchanged.
- Insets the blue/green highlight from weekday-column borders so it cannot cross into another day.
- Limits highlight width to 78% of the current weekday cell and keeps it centered on the detected match.
- Caps detected row height and centers the highlight on the matcher y coordinate so it stays on one handwritten line.
- Centers the black label inside the same weekday column with an explicit inset.
