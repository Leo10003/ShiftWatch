# ShiftWatch 19.4 — Header Date Recovery & Structural Hour Consensus

- Explicit rota header dates are now scanned in a wider top-of-document corridor, independent of the synthetic first block anchor.
- Split handwritten date fragments can be recombined per weekday column (for example `14` + `09` -> `14.09`) before week reconstruction.
- Weak bare day numbers remain restricted to the true header corridor so shift labels cannot become calendar evidence.
- Header-zone geometry now prefers actual detected horizontal rules instead of the synthetic 10.5% block start.
- Repeated two-digit full-hour labels (13:00 / 16:00 / 18:00) across 3+ weekday columns can resolve a structural block even when the tiny superscript `00` is unreadable.
- Morning one-digit hours remain conservative so `9` is never silently converted to 09:00 when 09:30 is possible.
- No identity, confuser, verification, preview, duplicate-detection, or calibration features were removed.
