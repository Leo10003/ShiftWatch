#!/usr/bin/env python3
"""Label-blind, same-crop adjustment and raw-evidence conflict diagnostics.

All input passes the existing strict labelled evaluator. No OCR, inference,
threshold tuning, replay, production selection or raw text is performed.
"""
import argparse
import importlib.util
from pathlib import Path
import sys


EPSILON = 0.0001  # Exploratory arithmetic residual display tolerance; not a scoring rule.


def _evaluator():
    source = Path(__file__).with_name('evaluate-labelled-scans.py')
    spec = importlib.util.spec_from_file_location('shiftwatch_evaluator_adjustments', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def classify_crop(crop):
    """Classify one ACTUAL crop; do not combine independent score maxima."""
    raw = crop['rawSeparation']
    adjustment = crop['separationAdjustment']
    if raw > 0 and adjustment < 0:
        return 'positive_raw_negative_adjustment'
    if raw <= 0 and adjustment > 0:
        return 'nonpositive_raw_positive_adjustment'
    if raw > 0:
        return 'positive_raw_other'
    return 'nonpositive_raw_other'


def features(full):
    """Compute immutable summaries before reference labels are inspected."""
    in_layout = [crop for crop in full if type(crop['physicalBlockIndex']) is int
                 and 0 <= crop['physicalBlockIndex'] <= 2]
    extra_count = len(full) - len(in_layout)
    buckets = {key: [] for key in ('positive_raw_negative_adjustment',
                                  'nonpositive_raw_positive_adjustment',
                                  'positive_raw_other', 'nonpositive_raw_other')}
    for crop in in_layout:
        buckets[classify_crop(crop)].append(crop)
    block_leaders = {}
    for block in range(3):
        block_crops = [c for c in in_layout if c['physicalBlockIndex'] == block]
        if block_crops:
            block_leaders[block] = {
                'adjusted': min(block_crops, key=lambda c: (-c['adjustedScore'], c['rank'])),
                'raw': min(block_crops, key=lambda c: (-c['rawSeparation'], c['rank'])),
            }
    # Sort by actual raw evidence (descending), then stable production rank.
    conflicts = sorted(buckets['positive_raw_negative_adjustment'],
                       key=lambda c: (-c['rawSeparation'], c['rank']))
    return {'counts': {name: len(crops) for name, crops in buckets.items()},
            'extras': extra_count, 'blocks': block_leaders, 'conflicts': conflicts}


def crop_description(crop):
    if crop is None:
        return 'none'
    origin = 'OCR' if crop['candidateOrigin'] == 'ocr_token_band' else 'ink'
    block = crop['physicalBlockIndex']
    return (f'B{block + 1}/r{crop["rank"]}/{origin}: '
            f'adj={crop["adjustedScore"]:+.3f}, '
            f'raw={crop["rawSeparation"]:+.3f}, '
            f'pos={crop["positiveScore"]:+.3f}, '
            f'conf={crop["confuserScore"]:+.3f}, '
            f'delta={crop["separationAdjustment"]:+.3f}')


def report(results, evaluator):
    lines = [
        '# ShiftWatch same-crop adjustment provenance', '',
        '**Diagnostic only:** fixed, label-blind classifications of individual scored '
        'production crops. Scores are not probabilities. This does not simulate '
        'acceptance, rescue, OCR or production ranking changes.', '',
        'Positive raw evidence with negative adjustment is a *trace for inspection*, '
        'not proof of a true employee match or a reason to disable the adjustment. '
        'Reference labels annotate results only after selection.', '',
        'B4+ and null candidates remain counted but are not compared to B1-B3.', '',
        'Residuals compare naive arithmetic identities; Android score formulas are not asserted.', ''
    ]
    for case in results:
        lines += [f'## Case: {case["id"]}', '']
        for scan in case['scans']:
            if scan.get('schemaVersion', 0) < 16:
                raise evaluator.EvaluationError(
                    f'{scan["path"]}: adjustment audit requires schemaVersion >= 16')
            lines += [f'### Export: {scan["path"]}', '',
                      '| Day | Production / reference | Scored | Outside B1-B3 | '
                      'Positive raw, negative delta | Nonpositive raw, positive delta | '
                      'Adjusted-leading crop | Raw-leading crop | Raw residual count | Adjusted residual count |',
                      '|---|---|---:|---:|---:|---:|---|---|---:|---:|']
            detail = []
            for day in range(7):
                row = scan['rows'][day]
                full = evaluator._validated_complete_candidates(row, scan['path'], day)
                # Preserve valid exports: arithmetic identities have not been
                # verified against Android's full production scoring expression.
                raw_deviations = sum(abs(c['rawSeparation'] -
                    (c['positiveScore'] - c['confuserScore'])) > EPSILON for c in full)
                adjusted_deviations = sum(abs(c['adjustedScore'] -
                    (c['positiveScore'] + c['separationAdjustment'])) > EPSILON for c in full)
                feat = features(full)
                adj = min((v['adjusted'] for v in feat['blocks'].values()),
                          key=lambda c: (-c['adjustedScore'], c['rank']), default=None)
                raw = min((v['raw'] for v in feat['blocks'].values()),
                          key=lambda c: (-c['rawSeparation'], c['rank']), default=None)
                truth = scan['truth'][day]
                label = 'OFF' if truth is None else f'B{truth + 1}'
                counts = feat['counts']
                lines.append(f'| {evaluator.DAYS[day]} | {row["decision"]} / {label} | '
                             f'{len(full)} | {feat["extras"]} | '
                             f'{counts["positive_raw_negative_adjustment"]} | '
                             f'{counts["nonpositive_raw_positive_adjustment"]} | '
                             f'{crop_description(adj)} | {crop_description(raw)} | '
                             f'{raw_deviations} | {adjusted_deviations} |')
                detail.append((day, feat))
            lines += ['', 'Residual counts are observations against unverified simple arithmetic identities, not corrupt-export findings.', '',
                      '#### Strongest actual positive-raw / negative-adjustment crops', '',
                      'Shows at most two crops per day; counts above include all crops. '
                      'The ranking here is raw separation only, regardless of reference label.', '',
                      '| Day | Count | Actual crop traces |', '|---|---:|---|']
            for day, feat in detail:
                traces = '; '.join(crop_description(c) for c in feat['conflicts'][:2])
                lines.append(f'| {evaluator.DAYS[day]} | {len(feat["conflicts"])} | '
                             f'{traces or "none"} |')
            lines += ['', 'No new production decision rule follows from this audit. '
                      'Inspect the Android score-adjustment branch and test independent '
                      'labelled photos, including OFF controls, before changing it.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.manifest.resolve() == args.output.resolve():
            raise ValueError('Report output must differ from manifest')
        if args.output.exists():
            raise ValueError('Refusing to overwrite existing report')
        evaluator = _evaluator()
        content = report(evaluator.evaluate(args.manifest), evaluator)
        args.output.write_text(content + '\n', encoding='utf-8')
    except (OSError, ValueError) as err:
        print(f'VALIDATION FAILED: {err}', file=sys.stderr)
        return 2
    except Exception as err:
        if err.__class__.__name__ == 'EvaluationError':
            print(f'VALIDATION FAILED: {err}', file=sys.stderr)
            return 2
        raise
    print(f'Wrote adjustment provenance: {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
