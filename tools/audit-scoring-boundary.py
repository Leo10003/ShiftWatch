#!/usr/bin/env python3
"""Validate fresh per-crop Android boundary provenance and report its branches.

Diagnostic only: no production replay, acceptance threshold or reference-driven ranking.
Legacy exports lacking measured boundaries are intentionally rejected.
"""
import argparse
import importlib.util
from pathlib import Path
import sys

BRANCHES = ('below_required_separation', 'within_separation_band',
            'strong_separation_bonus', 'no_confuser_profile')
EPS = 1e-5  # Float-to-JSON tolerance; do not reject exact Float branch boundaries.


def evaluator_module():
    source = Path(__file__).with_name('evaluate-labelled-scans.py')
    spec = importlib.util.spec_from_file_location('shiftwatch_evaluator_boundary', source)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def validate_boundary_crop(crop, source, day):
    """Read actual same-crop boundary; do not infer it from a different candidate."""
    if 'requiredSeparation' not in crop or 'separationBranch' not in crop:
        raise ValueError(f'{source} {day}: requires a new Android boundary-provenance export')
    branch = crop['separationBranch']
    required = crop['requiredSeparation']
    if branch not in BRANCHES:
        raise ValueError(f'{source} {day}: unknown scoring branch')
    if branch == 'no_confuser_profile':
        if required is not None or abs(crop['separationAdjustment']) > EPS:
            raise ValueError(f'{source} {day}: invalid no-confuser provenance')
        return branch
    if (isinstance(required, bool) or not isinstance(required, (float, int))
            or not .0 <= required <= 1.0):
        raise ValueError(f'{source} {day}: invalid required separation')
    raw = crop['rawSeparation']
    adjustment = crop['separationAdjustment']
    expected = {'below_required_separation': -.12,
                'within_separation_band': 0., 'strong_separation_bonus': .025}[branch]
    if abs(adjustment - expected) > EPS:
        raise ValueError(f'{source} {day}: branch/adjustment disagree')
    # JSON contains decimal renderings of Float values. Near the exact Float
    # boundaries, the source branch is authoritative; elsewhere cross-check.
    if branch == 'below_required_separation' and raw > required + EPS:
        raise ValueError(f'{source} {day}: below-boundary branch conflicts with raw score')
    if branch == 'within_separation_band' and (raw < required - EPS or raw > required + .13 + EPS):
        raise ValueError(f'{source} {day}: within-boundary branch conflicts with raw score')
    if branch == 'strong_separation_bonus' and raw < required + .13 - EPS:
        raise ValueError(f'{source} {day}: bonus branch conflicts with raw score')
    return branch


def build_report(results, evaluator):
    lines = [
        '# ShiftWatch measured separation-boundary provenance', '',
        '**Diagnostic only.** Per-crop boundaries are measured by Android, not inferred '
        'from reference labels. No replay, shift proposals, policy changes or thresholds.', '',
        'B4+ and null-index crops remain counted, not mapped to B1-B3. '
        'The reference label is displayed only for context.', ''
    ]
    for case in results:
        lines += [f'## Case: {case["id"]}', '']
        for scan in case['scans']:
            lines += [f'### Export: {scan["path"]}', '',
                      '| Day | Reference | Scored | Outside B1-B3 | Below | Within | Bonus | No confusers | '
                      'Strongest positive-raw negative-adjustment candidate |',
                      '|---|---|---:|---:|---:|---:|---:|---:|---|']
            for day in range(7):
                row = scan['rows'][day]
                if row.get('scoringBoundaryProvenanceVersion') != 1:
                    raise ValueError(f'{scan["path"]} {evaluator.DAYS[day]}: needs fresh provenance v1')
                crops = evaluator._validated_complete_candidates(row, scan['path'], day)
                counts = {branch: 0 for branch in BRANCHES}
                for crop in crops:
                    branch = validate_boundary_crop(crop, scan['path'], evaluator.DAYS[day])
                    counts[branch] += 1
                eligible = [c for c in crops if c['rawSeparation'] > 0 and
                            c['separationBranch'] == 'below_required_separation' and
                            type(c['physicalBlockIndex']) is int and
                            0 <= c['physicalBlockIndex'] <= 2]
                candidate = min(eligible, key=lambda c: (-c['rawSeparation'], c['rank']), default=None)
                trace = ('none' if candidate is None else
                         f'B{candidate["physicalBlockIndex"] + 1}/r{candidate["rank"]}: '
                         f'raw={candidate["rawSeparation"]:+.3f}; '
                         f'required={candidate["requiredSeparation"]:.3f}; '
                         f'adj={candidate["adjustedScore"]:.3f}')
                outside = sum(type(c['physicalBlockIndex']) is not int or
                              not 0 <= c['physicalBlockIndex'] <= 2 for c in crops)
                truth = scan['truth'][day]
                label = 'OFF' if truth is None else f'B{truth + 1}'
                lines.append(f'| {evaluator.DAYS[day]} | {label} | {len(crops)} | {outside} | '
                             f'{counts[BRANCHES[0]]} | {counts[BRANCHES[1]]} | '
                             f'{counts[BRANCHES[2]]} | {counts[BRANCHES[3]]} | {trace} |')
            lines += ['', 'Additional independent photos, including OFF days, are required '
                      'before proposing a scoring or recognition change.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.output.resolve() == args.manifest.resolve() or args.output.exists():
            raise ValueError('Refusing to overwrite manifest or existing report')
        evaluator = evaluator_module()
        content = build_report(evaluator.evaluate(args.manifest), evaluator)
        args.output.write_text(content + '\n', encoding='utf-8')
    except (OSError, ValueError) as exc:
        print(f'VALIDATION FAILED: {exc}', file=sys.stderr)
        return 2
    print(f'Wrote measured-boundary audit: {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
