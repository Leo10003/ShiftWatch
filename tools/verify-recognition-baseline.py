#!/usr/bin/env python3
"""Offline checks of *sanitized diagnostic metadata*. This cannot rerun OCR or prove accuracy."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
fixture = json.loads((root/'test-data/recognition/v2088-sanitized-decision-baseline.json').read_text())
assert fixture['kind'] == 'sanitized-diagnostic-baseline-not-ocr-replay'
assert len(fixture['baseline']) == 7
seen = set()
accepted = 0
for day in fixture['baseline']:
    col = day['weekdayColumn']
    assert col not in seen and col in range(7)
    seen.add(col)
    expected = fixture['expectedByWeekday'][str(col)]
    positive = day['decision'].startswith('accepted')
    if col == 1:
        assert not positive and day['physicalBlockIndex'] == 2
        assert day['eligibleOcrTokensByBlock'].get('2') == 2
        assert day['ocrMergedByBlock'].get('2') == 2
        assert day['ocrAddedByBlock'].get('2', 0) == 0
    elif expected is None:
        assert not positive
    else:
        assert positive and day['physicalBlockIndex'] == expected
        accepted += 1
    assert day['ocrMerged'] + day['ocrAdded'] == day['eligibleOcrTokens']
assert accepted == 5
print('PASS: anonymized reference baseline validates 5/6 and protects Friday (metadata only, not an OCR replay)')
