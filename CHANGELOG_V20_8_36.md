# ShiftWatch v20.8.36 — Anchored Review Recall

- Extends the manual-review-only anchored handwriting path to tolerate moderately negative raw separation down to -0.030 when the candidate remains the rank-1 crop in a uniquely anchored block.
- Expands the maximum measured boundary shortfall to 0.065 while retaining the existing minimum positive score and confuser-penalty ceiling.
- Keeps automatic acceptance, rescue floors, profile training and normal saved-profile matches unchanged.
- Adds regression coverage for the second labelled rota: Friday, Saturday and Sunday become reviewable while Wednesday and Thursday OFF remain excluded.
- Retains the original rota Friday OFF protection through the confuser-penalty gate.
