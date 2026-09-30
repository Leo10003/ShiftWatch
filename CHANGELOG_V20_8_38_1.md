# ShiftWatch v20.8.38.1 — OCR-backed review edge stability

- Keeps the v20.8.38 ambiguous-time review UI unchanged.
- Keeps automatic profile acceptance and rescue thresholds unchanged.
- Adds a narrow review-only stability edge for leading OCR-token candidates: raw separation > -0.035 and boundary shortfall <= 0.070.
- Probe-led candidates retain the tighter v20.8.36 review gate.
- High confuser penalties remain excluded, preserving the original-rota Friday OFF protection.
- Adds regression coverage for the fresh v20.8.38 Friday jitter and adjacent Thursday OFF case.
