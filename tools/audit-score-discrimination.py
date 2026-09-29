#!/usr/bin/env python3
"""Offline, label-blind score discrimination audit for schema-16 ShiftWatch exports.

Uses the existing strict labelled evaluator for provenance and schema validation.
Ground-truth labels annotate findings AFTER fixed features have been computed.
No OCR, acceptance simulation, threshold selection, or app-data modification.
"""
import argparse
import importlib.util
from pathlib import Path
import sys


FIELDS = ('adjustedScore', 'positiveScore', 'rawSeparation',
          'confuserScore', 'confuserPenalty', 'separationAdjustment')
ORIGINS = ('all', 'ocr_token_band', 'ink_gap_probe')


def _load_evaluator():
    path = Path(__file__).with_name('evaluate-labelled-scans.py')
    spec = importlib.util.spec_from_file_location('shiftwatch_labelled_evaluator', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def block_features(full):
    """Fixed features independent of labels. Only B1-B3 are comparable labels.

    Out-of-layout candidates (B4+, null) are counted, never silently mapped
    to a labelled shift block or erased from diagnostic coverage.
    """
    labelled = [c for c in full if type(c['physicalBlockIndex']) is int
                and 0 <= c['physicalBlockIndex'] <= 2]
    extra = [c for c in full if c['physicalBlockIndex'] is None or
             type(c['physicalBlockIndex']) is not int or
             c['physicalBlockIndex'] > 2 or c['physicalBlockIndex'] < 0]
    features = {}
    for origin in ORIGINS:
        subset = (labelled if origin == 'all' else
                  [c for c in labelled if c['candidateOrigin'] == origin])
        for block in range(3):
            group = [c for c in subset if c['physicalBlockIndex'] == block]
            if not group:
                continue
            # Keep all strongest per-signal observations. Different crops can
            # maximize different signals; this must not be treated as a single crop.
            features[(origin, block)] = {
                'count': len(group),
                'maxima': {field: max(c[field] for c in group) for field in FIELDS},
                'bestAdjustedRank': min(c['rank'] for c in group),
            }
    return features, len(extra)


def fmt(value):
    return '—' if value is None else f'{value:+.3f}'


def _best(features, origin, field):
    choices = [(block, rec['maxima'][field]) for (o, block), rec in features.items()
               if o == origin]
    return sorted(choices, key=lambda item: (-item[1], item[0]))


def _cell(feats, origin, field='adjustedScore'):
    order = _best(feats, origin, field)
    if not order:
        return 'none'
    winner, score = order[0]
    runner = order[1][1] if len(order) > 1 else None
    return f'B{winner + 1} {score:.3f} (gap {fmt(score-runner if runner is not None else None)})'


def _evidence_cell(feats, block, origin='all'):
    rec = feats.get((origin, block))
    if not rec:
        return 'absent'
    d = rec['maxima']
    return (f'n={rec["count"]}; adj={d["adjustedScore"]:.3f}; '
            f'pos={d["positiveScore"]:.3f}; neg={d["confuserScore"]:.3f}; '
            f'raw={d["rawSeparation"]:.3f}; '
            f'penalty={d["confuserPenalty"]:.3f}; '
            f'adjΔ={d["separationAdjustment"]:+.3f}')


def discrimination_report(results, evaluator):
    lines = ['# ShiftWatch fixed evidence-discrimination audit', '',
             '**Diagnostic only:** fixed label-blind comparisons of successfully '
             'scored production crops. No acceptance/rescue thresholds, replay '
             'or proposed shift selection. Different signals may peak on '
             'different crops. Scores are not probabilities.', '',
             'B4+ and null-index crops remain validated and counted but are '
             'not mapped to B1–B3. Shadow OCR and unscored crops are excluded.', '']
    for case in results:
        lines += [f'## Case: {case["id"]}', '']
        for scan in case['scans']:
            if scan.get('schemaVersion', 0) < 16:
                raise evaluator.EvaluationError(
                    f'{scan["path"]}: complete candidates require schemaVersion >= 16')
            lines += [f'### Export: {scan["path"]}', '',
                      '| Day | Production / reference | Crops / outside B1–B3 | '
                      'Adjusted leader (gap) | OCR adjusted leader (gap) | '
                      'Ink adjusted leader (gap) | Raw leader (gap) |',
                      '|---|---|---:|---|---|---|---|']
            observations = []
            for day in range(7):
                row = scan['rows'][day]
                full = evaluator._validated_complete_candidates(row, scan['path'], day)
                feat, extra = block_features(full)
                reference = scan['truth'][day]
                truth_text = 'OFF' if reference is None else f'B{reference+1}'
                lines.append(
                    f'| {evaluator.DAYS[day]} | {row["decision"]} / {truth_text} | '
                    f'{len(full)} / {extra} | {_cell(feat, "all")} | '
                    f'{_cell(feat, "ocr_token_band")} | '
                    f'{_cell(feat, "ink_gap_probe")} | '
                    f'{_cell(feat, "all", "rawSeparation")} |')
                observations.append((day, reference, feat))
            lines += ['', '#### Per-block evidence', '',
                      '| Day | Block | All scored crops | OCR-origin | Ink-probe |',
                      '|---|---|---|---|---|']
            for day, reference, feat in observations:
                for block in range(3):
                    lines.append(
                        f'| {evaluator.DAYS[day]} | B{block + 1}'
                        f'{" (label)" if block == reference else ""} | '
                        f'{_evidence_cell(feat, block)} | '
                        f'{_evidence_cell(feat, block, "ocr_token_band")} | '
                        f'{_evidence_cell(feat, block, "ink_gap_probe")} |')
            # Exploratory cross-day envelope, never a classification rule:
            # compare the strongest B1-B3 false-candidate scores on known OFF days
            # with the actual labelled block's observations on working days.
            off_days = [(day, feat) for day, label, feat in observations if label is None]
            work_days = [(day, label, feat) for day, label, feat in observations
                         if label is not None]
            lines += ['', '#### Reference-annotated separation (not a calibrated threshold)', '',
                      '| Signal / origin | Largest OFF-day block max | '
                      'Smallest observed working-day labelled-block max | '
                      'Working minimum minus OFF maximum |',
                      '|---|---:|---:|---:|']
            for origin in ORIGINS:
                for field in ('adjustedScore', 'positiveScore', 'rawSeparation'):
                    off = [rec['maxima'][field] for _, feat in off_days
                           for (o, _), rec in feat.items() if o == origin]
                    work = [feat[(origin, block)]['maxima'][field]
                            for _, block, feat in work_days if (origin, block) in feat]
                    mx = max(off) if off else None
                    mn = min(work) if work else None
                    gap = mn - mx if mn is not None and mx is not None else None
                    lines.append(f'| {field} / {origin} | {fmt(mx)} | '
                                 f'{fmt(mn)} | {fmt(gap)} |')
            lines += ['', 'A positive gap on this case would not establish a safe '
                      'production threshold; omitted labelled crops and additional '
                      'independent OFF photos remain important.', '']
    lines += ['Do not treat repeated exports of one photograph as independent validation.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.manifest.resolve() == args.output.resolve():
            raise ValueError('Output must differ from manifest')
        if args.output.exists():
            raise ValueError('Refusing to overwrite existing diagnostic report')
        evaluator = _load_evaluator()
        report = discrimination_report(evaluator.evaluate(args.manifest), evaluator)
        args.output.write_text(report + '\n', encoding='utf-8')
    except (ValueError, OSError) as error:
        print(f'VALIDATION FAILED: {error}', file=sys.stderr)
        return 2
    except Exception as error:
        if error.__class__.__name__ == 'EvaluationError':
            print(f'VALIDATION FAILED: {error}', file=sys.stderr)
            return 2
        raise
    print(f'Wrote diagnostic report: {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
