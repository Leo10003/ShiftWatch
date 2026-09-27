# ShiftWatch Premium v19.1 — Global Band Time Recovery

## Time recognition
- Added hour-focused and superscript-minute-focused time-atlas views for every structural rota block.
- The dedicated hour view enlarges the left-hand hour glyphs (9/13/16/18) independently from tiny 00/30 minute marks.
- The dedicated minute view enlarges the elevated minute area so split labels such as 9 + 30 and 16 + 00 can be recombined inside the same physical block.
- Focused atlas OCR coordinates are mapped back into the original block, preserving strict row ownership.
- The structural solver may now accept one exceptionally clear atlas observation when the alternative margin is strong and chronological structure is consistent.
- A repeatedly confirmed, contradiction-free learned block mapping can resolve a block even when the current photo's time handwriting is unreadable.
- Cross-row borrowing remains forbidden; unresolved blocks stay unresolved.

## Review UX
- Renamed the misleading `Read` zoom pill to `Inspect`.
- Added an explicit `Start times · X/Y resolved` status beneath the rota so identity recall and time-resolution completeness are reported separately.

## Compatibility
- Keeps v19 canonical grid alignment, missed-day recovery, durable block-time learning, adaptive identity/confuser learning, strict row ownership, verification, resumable sessions, duplicate detection, and manual correction.
