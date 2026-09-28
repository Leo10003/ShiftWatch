# ShiftWatch v20.8.14 — Shadow full-week decision replay (diagnostic only)

- Replay every weekday’s **complete scored candidate list** with each qualifying `trim_12` score substituted **in place** for its original candidate. No extra runner candidate is introduced.
- Re-evaluate normal and near-floor decisions, then weekly rescue and mature-profile recovery as a read-only, complete seven-day hypothetical replay. Record anonymous winner block, best, runner, margin, replay status, and whether the hypothetical winner is a trim.
- New `shadowReplay` evidence appears in both schema 15 diagnostic export paths and in the PowerShell read-only analysis/repeatability tools; no names, OCR text, crop boundaries, images, or profiles are exported.
- Actual recognition ranking, match output, stored profiles, offline-first behavior, week header, and shift-time inference remain unchanged. The proposed crop qualification limits still originate from one reference photo; replay is not evidence of generalization.
- Version 20.8.14/code 225. Requires GitHub Actions Android compilation and actual Android tests before use.
- The unchanged whole-week baseline is separately replayed and compared with actual production decisions, top scores, and winner blocks. Any parity failure invalidates the experimental conclusion.
