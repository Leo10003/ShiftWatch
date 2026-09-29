#!/usr/bin/env python3
"""Label-blind near-boundary telemetry for review research, never shift selection.

Validate all complete scored production crops first. Reference labels annotate the
result after candidates are selected, without influencing ranking or thresholds.
A shared geometry fingerprint is not evidence of independent photographs.
"""
import argparse
import importlib.util
import math
from pathlib import Path
import sys


def sibling_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def summarize(crops, boundary, source, day):
    """Return the actual closest positive-raw, below-boundary B1-B3 crop."""
    eligible = []
    outside = 0
    for crop in crops:
        boundary.validate_boundary_crop(crop, source, day)
        block = crop['physicalBlockIndex']
        if type(block) is not int or not 0 <= block <= 2:
            outside += 1
            continue
        if (crop['rawSeparation'] > 0 and
                crop['separationBranch'] == 'below_required_separation'):
            shortfall = crop['requiredSeparation'] - crop['rawSeparation']
            if not math.isfinite(shortfall) or shortfall <= 0:
                raise ValueError(f'{source} {day}: invalid boundary shortfall')
            eligible.append((shortfall, crop['rank'], crop))
    eligible.sort(key=lambda item: (item[0], item[1]))
    return {'count': len(eligible), 'outside': outside,
            'closest': eligible[0][2] if eligible else None,
            'shortfall': eligible[0][0] if eligible else None}


def verify_exported_telemetry(row, calculated, source, day):
    """New v20.8.32 field is optional for older measured-boundary exports."""
    exported = row.get('boundaryReviewTelemetry')
    if exported is None:
        return 'legacy: recalculated from per-crop boundaries'
    if not isinstance(exported, dict) or (
            type(exported.get('positiveRawBelowBoundaryCount')) is not int or
            type(exported.get('outsideLabelledBlocksCount')) is not int or
            exported['positiveRawBelowBoundaryCount'] != calculated['count'] or
            exported['outsideLabelledBlocksCount'] != calculated['outside']):
        raise ValueError(f'{source} {day}: boundary review telemetry counts disagree')
    actual = exported.get('closestPositiveRawBelowBoundary')
    expected = calculated['closest']
    if (actual is None) != (expected is None):
        raise ValueError(f'{source} {day}: boundary review telemetry nearest crop disagree')
    if expected is not None:
        if not isinstance(actual, dict) or any(actual.get(k) != expected.get(k) for k in
                ('rank', 'physicalBlockIndex', 'candidateOrigin')):
            raise ValueError(f'{source} {day}: nearest crop identity disagree')
        for key, value in [('rawSeparation', expected['rawSeparation']),
                           ('requiredSeparation', expected['requiredSeparation']),
                           ('shortfall', calculated['shortfall'])]:
            if (isinstance(actual.get(key), bool) or
                    not isinstance(actual.get(key), (int, float)) or
                    not math.isfinite(actual[key]) or
                    abs(actual[key] - value) > 1e-5):
                raise ValueError(f'{source} {day}: nearest crop {key} disagree')
    return 'verified new export'


def build_report(results, evaluator, boundary, *, require_independent_controls=False):
    scans = [(case, scan) for case in results for scan in case['scans']]
    fingerprints = [scan['fingerprint'] for _, scan in scans if scan['fingerprint']]
    distinct = len(set(fingerprints))
    off_fingerprints = {scan['fingerprint'] for _, scan in scans if scan['fingerprint'] and
                        any(value is None for value in scan['truth'].values())}
    if require_independent_controls and (len(fingerprints) != len(scans) or
            distinct < 2 or len(off_fingerprints) < 2):
        raise ValueError('Independent-photo gate requires at least two distinct nonempty '
                         'OCR geometry fingerprints with labelled OFF controls; '
                         'fingerprints are only a heuristic, not photographic proof')
    lines = ['# ShiftWatch boundary review research', '',
             '**Diagnostic only.** This report does not create suggestions, replay recognition '
             'or relax any production rule. Scores and distances are not probabilities.', '',
             f'Exports: {len(scans)}; distinct nonempty OCR geometry fingerprints: {distinct}. '
             'Fingerprints cannot prove photographic independence.', '']
    if distinct < 2:
        lines += ['**Independent-photo validation NOT established.** Do not tune thresholds '
                  'from this report.', '']
    if len(fingerprints) != len(set(fingerprints)):
        lines += ['**Repeated geometry detected:** repeat scans do not establish generalization.', '']
    for case, scan in scans:
        lines += [f'## Case: {case["id"]} — {scan["path"]}', '',
                  '| Day | Production | Label | Positive-raw below boundary | Outside B1-B3 | Closest actual crop (review research only) |',
                  '|---|---|---|---:|---:|---|']
        for d, day in enumerate(evaluator.DAYS):
            row = scan['rows'][d]
            if row.get('scoringBoundaryProvenanceVersion') != 1:
                raise ValueError(f'{scan["path"]} {day}: fresh measured boundary export required')
            full = evaluator._validated_complete_candidates(row, scan['path'], d)
            calc = summarize(full, boundary, scan['path'], day)
            verify_exported_telemetry(row, calc, scan['path'], day)
            candidate = calc['closest']
            description = ('none' if candidate is None else
                           f'B{candidate["physicalBlockIndex"] + 1}/r{candidate["rank"]}/'
                           f'{candidate["candidateOrigin"]}; '
                           f'raw={candidate["rawSeparation"]:+.4f}; '
                           f'required={candidate["requiredSeparation"]:.4f}; '
                           f'shortfall={calc["shortfall"]:.4f}')
            reference = scan['truth'][d]
            label = 'OFF' if reference is None else f'B{reference+1}'
            lines.append(f'| {day} | {row["decision"]} | {label} | {calc["count"]} | '
                         f'{calc["outside"]} | {description} |')
        lines += ['', 'Reference labels are annotations only. No acceptance or rescue was simulated.', '']
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--require-independent-controls', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.output.exists() or args.output.resolve() == args.manifest.resolve():
            raise ValueError('Refusing to overwrite existing report or manifest')
        evaluator = sibling_module('shiftwatch_evaluator_review', 'evaluate-labelled-scans.py')
        boundary = sibling_module('shiftwatch_boundary_review', 'audit-scoring-boundary.py')
        report = build_report(evaluator.evaluate(args.manifest), evaluator, boundary,
                              require_independent_controls=args.require_independent_controls)
        args.output.write_text(report, encoding='utf-8')
    except (ValueError, OSError) as exc:
        print(f'VALIDATION FAILED: {exc}', file=sys.stderr)
        return 2
    print(f'Wrote review-only boundary audit: {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
