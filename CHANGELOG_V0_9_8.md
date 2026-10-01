# ShiftWatch 0.9.8

## Column-centered marker rendering

- Keeps recognition scoring and stored match coordinates unchanged.
- Renders the highlight and center dot at the center of the detected weekday column.
- Prevents a match whose source x is close to a grid divider from visually appearing on the split between two days.
- Keeps labels and highlights inside the same weekday cell.
