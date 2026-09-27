# ShiftWatch recognition and responsiveness measurements

This is a **measurement specification**, not a claim about current accuracy. Baseline values
remain UNKNOWN until tested with an explicitly authorized, representative dataset and device.

| Metric | Formula / protocol | Initial baseline |
|---|---|---|
| Document week accuracy | Correct document-week start / labeled photos | Unknown |
| Employee-day precision | Correct proposed employee-day matches / all proposed employee-day matches | Unknown |
| Employee-day recall | Correct proposed employee-day matches / all actual labeled occurrences | Unknown |
| Start-time accuracy | Correct automatically resolved starts / automatically resolved starts with ground truth | Unknown |
| Abstention | Unresolved correctly or incorrectly / all labeled shift occurrences, reported separately | Unknown |
| False READY | Planner-ready entries with any wrong identity/date/time; target zero | Unknown |
| Scan stage timings | Decode/grid/header/time/identity/verification elapsed ms, per device | Unknown |
| Interaction latency | Tap-to-visible response during deliberately slow OCR, median/p95 | Unknown |
| Stability | ANRs, uncaught crashes, max memory in repeated 50-gesture test | Unknown |

For a release comparison, run the SAME frozen permissioned corpus on the SAME device or
emulator and report counts as well as percentages. If the newer algorithm abstains more often,
do not present higher accuracy among resolved cases alone as an unequivocal improvement.

Always compare to the previous accepted release; maintain a change log recording each new
labeled case and expected output. The included synthetic JSON validates tooling only: it has
no photo, image-ground truth or evidence of real-world recognition accuracy.
