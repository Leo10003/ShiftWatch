# Next major recognition milestone (requires real evidence)

An on-device neural handwriting matcher cannot be honestly shipped without a licensed/trained model and labeled, authorized rota examples. The current source still uses locally confirmed handwriting prototypes and hard negatives.

## Prerequisite regression data
- v20.7 adds **Export my confirmed example**. Export is opt-in and contains only manually confirmed weekday/start-time/physical-block labels, no employee names, photos, exact dates, or raw OCR text.
- To measure name matching, additional handwritten crop/identity labels are required; ask for explicit permission and anonymize other employees' information. Do NOT silently export third-party handwriting.
- Separate data into train/validation/test by distinct rota photos and handwriting style; never test on the same crop used for training.

## Future offline embedding gateway
- Versioned feature extractor plus local multi-prototype nearest neighbor search and hard-negative margins.
- Always keep manual confirmation as the final planner gate, independent from model confidence.
- A photo template hash and scale/deskew metadata must accompany the model cache; invalidate on image/template/model-version change.
- Measure false-positive employee-days, recall, wrong READY, accuracy by block, p50/p95 latency, and peak image-decoding RAM on the target device.

## Future perspective correction
- Locate page corners/grid lines, rectify one normalized bitmap, and store the inverse projective transform.
- Every tap, preview marker and OCR box must pass a round-trip coordinate test before perspective correction is enabled.
- If corners/grid are uncertain, leave the original image unchanged and use the existing piecewise per-column geometric normalization.
