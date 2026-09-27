# ShiftWatch v17.1 — Time False-Positive Guard

- Fixed employee names being interpreted as start times through OCR letter/digit substitutions (for example S -> 5 creating false 05:00 evidence).
- Added a strict time-likeness gate before any OCR confusion repair.
- Semantic time-band learning now ignores tokens deep inside employee-name regions of a physical rota block.
- Preserves legitimate repairs such as O9.3O -> 09:30, I6:OO -> 16:00 and 13OO -> 13:00.
- Added regression tests using names visible in the failing rota screenshot.
