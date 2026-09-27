#!/usr/bin/env python3
"""No external Python dependencies; validates metadata and release hygiene, not OCR accuracy."""
import json
import pathlib
import re
import sys
from datetime import date, timedelta

ROOT = pathlib.Path(__file__).resolve().parents[1]
CASES = ROOT / 'test-data' / 'cases'
errors = []
version = (ROOT / 'app' / 'build.gradle.kts').read_text(encoding='utf-8')
if not re.search(r'versionCode\s*=\s*\d+', version):
    errors.append('No Android versionCode')
if not re.search(r'versionName\s*=\s*"[^"]+"', version):
    errors.append('No Android versionName')
seen = set()
for case_path in sorted(CASES.glob('*.json')):
    if case_path.name == 'schema.json':
        continue
    try:
        case = json.loads(case_path.read_text(encoding='utf-8'))
        assert case.get('schemaVersion') == 1
        key = case['caseId']
        assert re.fullmatch('[a-z0-9-]+', key)
        assert key not in seen
        seen.add(key)
        assert case.get('privacy') == 'synthetic-or-anonymized'
        expected = case['expected']
        monday = date.fromisoformat(expected['rotaWeekStart'])
        assert monday.weekday() == 0, 'Week must start Monday'
        for row in expected['shifts']:
            assert 1 <= row['weekday'] <= 7
            assert re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', row['start'])
            assert type(row['identityExpected']) is bool
            assert monday <= monday + timedelta(days=row['weekday'] - 1) <= monday + timedelta(days=6)
        # These metadata fixtures deliberately contain no names or photographs.
        assert 'employeeName' not in case and 'imagePath' not in case
    except (ValueError, KeyError, AssertionError, TypeError) as exc:
        errors.append(f'{case_path.name}: {exc}')
if not seen:
    errors.append('At least one synthetic fixture is required')
if errors:
    for e in errors: print('FAIL:', e, file=sys.stderr)
    sys.exit(1)
print(f'PASS: version metadata and {len(seen)} anonymized regression-case fixture(s) validated (not OCR evaluation)')
