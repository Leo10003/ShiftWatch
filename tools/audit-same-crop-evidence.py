#!/usr/bin/env python3
"""Diagnostic-only same-crop provenance audit for validated schema-16+ exports.

No recognition, selection, threshold tuning, or OCR. All comparisons are label-blind;
truth annotates only after a block's representative crops have been selected.
"""
import argparse
import importlib.util
from pathlib import Path
import sys


def _evaluator():
    path = Path(__file__).with_name('evaluate-labelled-scans.py')
    spec = importlib.util.spec_from_file_location('shiftwatch_evaluator_samecrop', path)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj


def select_representatives(full):
    """Select genuine individual crops, never independent maxima mixed as one.

    Stable tie-break: score descending, then export rank ascending. Retain counts of
    every validated additional physical region without mapping it to B1-B3.
    """
    groups = {block: [] for block in range(3)}
    extras = {}
    for crop in full:
        block = crop['physicalBlockIndex']
        if type(block) is int and 0 <= block < 3:
            groups[block].append(crop)
        else:
            name = 'null' if block is None else f'B{block + 1}'
            extras[name] = extras.get(name, 0) + 1
    selected = {}
    for block, crops in groups.items():
        if crops:
            selected[block] = {
                'count': len(crops),
                'adjusted': min(crops, key=lambda c: (-c['adjustedScore'], c['rank'])),
                'raw': min(crops, key=lambda c: (-c['rawSeparation'], c['rank'])),
            }
    return selected, extras


def crop_text(crop):
    if crop is None:
        return 'absent'
    origin = 'OCR' if crop['candidateOrigin'] == 'ocr_token_band' else 'ink'
    return (f'r{crop["rank"]}/{origin}; adj={crop["adjustedScore"]:+.3f}; '
            f'raw={crop["rawSeparation"]:+.3f}; '
            f'pos={crop["positiveScore"]:+.3f}; '
            f'conf={crop["confuserScore"]:+.3f}; '
            f'pen={crop["confuserPenalty"]:+.3f}; '
            f'delta={crop["separationAdjustment"]:+.3f}')


def report(results, evaluator):
    lines = [
        '# ShiftWatch same-crop evidence provenance', '',
        '**Diagnostic only.** Each displayed bundle is one scored production crop; '
        'the strongest adjusted and raw crops may differ. No production thresholds, '
        'acceptance, rescue, replay, or OCR are evaluated. Scores are not probabilities.', '',
        'Reference labels annotate fixed selections after ranking. B4+ and null '
        'remain counted but are never mapped to the three labelled blocks.', ''
    ]
    for case in results:
        lines += [f'## Case: {case["id"]}', '']
        for scan in case['scans']:
            if scan.get('schemaVersion', 0) < 16:
                raise evaluator.EvaluationError(
                    f'{scan["path"]}: same-crop audit requires schemaVersion >= 16')
            lines += [f'### Export: {scan["path"]}', '',
                      '| Day | Production / truth | Scored | Outside B1-B3 | '
                      'Adjusted block leader | Raw block leader |',
                      '|---|---|---:|---|---|---|']
            entries = []
            for day in range(7):
                row = scan['rows'][day]
                full = evaluator._validated_complete_candidates(row, scan['path'], day)
                blocks, extra = select_representatives(full)
                adjusted = sorted(blocks, key=lambda b: (-blocks[b]['adjusted']['adjustedScore'], b))
                raw = sorted(blocks, key=lambda b: (-blocks[b]['raw']['rawSeparation'], b))
                truth = scan['truth'][day]
                label = 'OFF' if truth is None else f'B{truth+1}'
                extra_display = ', '.join(f'{key}:{extra[key]}' for key in sorted(extra)) or 'none'
                adj_leader = f'B{adjusted[0]+1}' if adjusted else 'none'
                raw_leader = f'B{raw[0]+1}' if raw else 'none'
                lines.append(f'| {evaluator.DAYS[day]} | {row["decision"]} / {label} | '
                             f'{len(full)} | {extra_display} | {adj_leader} | {raw_leader} |')
                entries.append((day, truth, blocks))
            lines += ['', '#### Same-crop representatives by physical block', '',
                      '| Day | Block | Crops | Adjusted-leading actual crop | '
                      'Raw-leading actual crop | Same crop? |',
                      '|---|---|---:|---|---|---|']
            for day, truth, blocks in entries:
                for block in range(3):
                    record = blocks.get(block)
                    if not record:
                        continue
                    adj, raw = record['adjusted'], record['raw']
                    same = 'yes' if adj['rank'] == raw['rank'] else 'no'
                    annotation = ' (reference)' if truth == block else ''
                    lines.append(f'| {evaluator.DAYS[day]} | B{block+1}{annotation} | '
                                 f'{record["count"]} | {crop_text(adj)} | '
                                 f'{crop_text(raw)} | {same} |')
            lines += ['', 'These comparisons do not establish an acceptance rule. '
                      'Compare independent, labelled photographs including OFF days '
                      'before proposing recognition changes.', '']
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
            raise ValueError('Refusing to overwrite existing diagnostic report')
        evaluator = _evaluator()
        content = report(evaluator.evaluate(args.manifest), evaluator)
        args.output.write_text(content + '\n', encoding='utf-8')
    except (ValueError, OSError) as err:
        print(f'VALIDATION FAILED: {err}', file=sys.stderr)
        return 2
    except Exception as err:
        if err.__class__.__name__ == 'EvaluationError':
            print(f'VALIDATION FAILED: {err}', file=sys.stderr)
            return 2
        raise
    print(f'Wrote same-crop diagnostic: {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
