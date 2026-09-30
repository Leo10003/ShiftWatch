# ShiftWatch 20.8.34 — Explicit review of anchored near-boundary crops

- Preserve automatic match acceptance, rescue floors, confuser penalties and OFF-day protection unchanged.
- When **two separately accepted weekdays** agree on a physical block, expose the actual first-ranked candidate of a missing day for **explicit manual review only** if its positive score is >=0.54, raw identity separation is strictly positive, and it falls below its measured dynamic boundary by <=0.030.
- Keep these amber hints separate from accepted matches, OCR name markers, shift drafts and training examples. Tapping still requires explicit user confirmation. Do not interpret review hints as correct predictions or import them automatically.
- The rule is a limited usability improvement, not a demonstrated accuracy improvement. The two supplied labelled rotas are useful regression controls, not evidence of generalization.
- B1 time on the second rota remains review-required (09:00 proposed, 09:30 labelled); never force the labelled value into OCR production logic.
