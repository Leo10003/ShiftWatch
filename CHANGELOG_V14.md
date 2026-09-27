# ShiftWatch v14.0 — Time Intelligence

- Added `RotaTimeRecognitionEngine`, a dedicated tiny-vocabulary time recognizer independent from general OCR.
- Repairs common handwriting/OCR confusions (`O/0`, `I/1`, `G/9`, missing separators and superscript minutes).
- Generates competing hypotheses and exposes ambiguity instead of silently forcing a time.
- Treats learned workplace times as soft priors only; novel valid times remain accepted.
- Integrates specialist time hypotheses into the existing rota candidate collector and v12 perception layer.
- Keeps ambiguous labels weak so v11 verification, row consensus and geometry can resolve them.
- Adds regression tests for 09:30, 13:00, 16:00, ambiguous `900`, and novel learned-time behavior.
