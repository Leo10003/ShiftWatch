#!/usr/bin/env python3
"""Strict, offline, labelled multi-photo diagnostic evaluator. Python stdlib only.

This does not perform OCR and never uses experimental replay as a production suggestion.
"""
import argparse
import json
from pathlib import Path
import sys

DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']


class EvaluationError(ValueError):
    pass


def read_json(path):
    """Reject concatenated JSON or stale trailing bytes with a useful source filename."""
    try:
        raw = Path(path).read_text(encoding='utf-8-sig')
        decoder = json.JSONDecoder()
        doc, end = decoder.raw_decode(raw.lstrip())
        if raw.lstrip()[end:].strip():
            raise EvaluationError(f'{path}: additional text after the first JSON document; '
                                  'possibly a non-truncating export or mixed clipboard content')
        return doc
    except (UnicodeError, OSError, json.JSONDecodeError) as exc:
        raise EvaluationError(f'{path}: malformed or unreadable JSON ({exc}); re-export '
                              'using v20.8.15+ and choose a new filename') from exc


def require(cond, message):
    if not cond:
        raise EvaluationError(message)


def parse_truth(doc, source):
    require(isinstance(doc, dict) and doc.get('schemaVersion') == 1, f'{source}: truth schemaVersion must be 1')
    shifts, off = doc.get('shifts'), doc.get('offWeekdays')
    require(isinstance(shifts, list) and isinstance(off, list),
            f'{source}: provide both shifts and explicit offWeekdays (0=Mon through 6=Sun)')
    expected = {}
    for item in shifts:
        require(isinstance(item, dict), f'{source}: each shift must be an object')
        d, block = item.get('weekday'), item.get('physicalBlock')
        require(type(d) is int and 0 <= d <= 6, f'{source}: invalid weekday')
        require(type(block) is int and 0 <= block <= 2, f'{source}: invalid physicalBlock')
        require(d not in expected, f'{source}: duplicate weekday {d}')
        expected[d] = block
    for d in off:
        require(type(d) is int and 0 <= d <= 6, f'{source}: invalid offWeekday')
        require(d not in expected, f'{source}: duplicate/conflicting weekday {d}')
        expected[d] = None
    require(len(expected) == 7, f'{source}: incomplete truth: explicitly label all seven weekdays')
    return expected


def scan_rows(doc, source):
    require(isinstance(doc, dict) and type(doc.get('schemaVersion')) is int
            and doc['schemaVersion'] >= 15, f'{source}: requires scan schemaVersion >= 15')
    markers = doc.get('viewerMarkers')
    require(isinstance(markers, dict), f'{source}: missing viewerMarkers')
    session, run = doc.get('sessionId'), markers.get('recognitionRunId')
    require(isinstance(session, str) and bool(session) and isinstance(run, str) and bool(run),
            f'{source}: missing session or recognition run ID')
    require(doc.get('scanInProgress') is False and doc.get('scanStage') == 'COMPLETE',
            f'{source}: scan is incomplete')
    decisions = markers.get('savedProfileDecisions')
    require(isinstance(decisions, list) and len(decisions) == 7,
            f'{source}: expected exactly seven saved-profile decisions')
    rows = {}
    for item in decisions:
        require(isinstance(item, dict), f'{source}: invalid weekday decision')
        d = item.get('weekdayColumn')
        require(type(d) is int and 0 <= d <= 6 and d not in rows,
                f'{source}: invalid or duplicate weekday column {d}')
        replay = item.get('shadowReplay')
        require(isinstance(replay, dict) and type(replay.get('baselineParity')) is bool,
                f'{source}: missing schema-15 shadowReplay or baselineParity')
        rows[d] = item
    return session, run, rows


def winning_block(item, experimental):
    if experimental:
        return item['shadowReplay'].get('replayWinnerBlock')
    candidates = item.get('topCandidates')
    return candidates[0].get('physicalBlockIndex') if isinstance(candidates, list) and candidates else None


def tally(rows, truth, experimental=False):
    counts = dict(hits=0, misses=0, falseSuggestions=0, wrongBlock=0)
    outcomes = []
    for weekday in range(7):
        item = rows[weekday]
        if experimental:
            state = item['shadowReplay'].get('replayStatus')
        else:
            state = item.get('decision')
        require(isinstance(state, str) and (state.startswith('accepted') or state.startswith('rejected')),
                f'{DAYS[weekday]}: missing/unrecognized decision status {state!r}')
        accepted = state.startswith('accepted')
        block = winning_block(item, experimental)
        expected = truth[weekday]
        if expected is None:
            result = 'FALSE SUGGESTION' if accepted else 'correct OFF'
            counts['falseSuggestions'] += int(accepted)
        elif not accepted:
            result = 'missed working day'
            counts['misses'] += 1
        elif type(block) is int and block == expected:
            result = 'correct block'
            counts['hits'] += 1
        else:
            result = 'WRONG BLOCK'
            counts['wrongBlock'] += 1
        outcomes.append((DAYS[weekday], result, state, block))
    return counts, outcomes


def evaluate(manifest_path):
    manifest_path = Path(manifest_path).resolve()
    manifest = read_json(manifest_path)
    require(isinstance(manifest, dict) and manifest.get('schemaVersion') == 1
            and isinstance(manifest.get('cases'), list) and manifest['cases'],
            'Manifest must have schemaVersion 1 and a nonempty cases array')
    result, seen_id, sessions, runs, fingerprints = [], set(), set(), set(), {}
    for case in manifest['cases']:
        require(isinstance(case, dict) and isinstance(case.get('id'), str) and case['id'].strip(),
                'Each case must have an id')
        cid = case['id']
        require(cid not in seen_id, f'Duplicate case id {cid}')
        seen_id.add(cid)
        require(isinstance(case.get('groundTruth'), str) and isinstance(case.get('scans'), list)
                and case['scans'], f'{cid}: groundTruth and nonempty scans required')
        truth_path = manifest_path.parent / case['groundTruth']
        truth = parse_truth(read_json(truth_path), truth_path)
        entry = {'id': cid, 'scans': [], 'warnings': []}
        for scan in case['scans']:
            require(isinstance(scan, str) and bool(scan), f'{cid}: scan path must be a string')
            p = manifest_path.parent / scan
            doc = read_json(p)
            session, run, rows = scan_rows(doc, p)
            require(session not in sessions and run not in runs,
                    f'{cid}: repeated session/recognition ID; duplicate exports do not count as independent scans')
            sessions.add(session)
            runs.add(run)
            for d, row in rows.items():
                replay = row['shadowReplay']
                require(replay['baselineParity'] is True,
                        f'{cid} {scan} {DAYS[d]}: baseline replay parity failed; disregard hypothetical results')
                require(replay.get('originalStatus') == row.get('decision'),
                        f'{cid} {scan} {DAYS[d]}: original replay status disagrees with production')
            prod, p_days = tally(rows, truth)
            replay, r_days = tally(rows, truth, experimental=True)
            fingerprint = doc.get('ocrGeometryFingerprint')
            if fingerprint:
                if fingerprint in fingerprints and fingerprints[fingerprint] != cid:
                    entry['warnings'].append('OCR geometry fingerprint is also present in case '
                                             f'{fingerprints[fingerprint]}; distinct photo is NOT verified')
                fingerprints[fingerprint] = cid
            entry['scans'].append({'path': scan, 'production': prod, 'replay': replay,
                                   'productionDays': p_days, 'replayDays': r_days})
        result.append(entry)
    return result


def markdown(result):
    lines = ['# ShiftWatch labelled diagnostic evaluation', '',
             '**Diagnostic only:** working-day block identity and OFF suggestions; '
             'start times and week dates are not evaluated. Replay is hypothetical, never a production suggestion.', '']
    photo_count = len(result)
    lines += [f'Labelled cases: {photo_count}. Different case IDs alone do not prove different photographs.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '', '| Scan | Production hits | Production OFF false | Replay hits | Replay OFF false |',
                  '|---|---:|---:|---:|---:|']
        for scan in case['scans']:
            p, r = scan['production'], scan['replay']
            lines.append(f'| {scan["path"]} | {p["hits"]} | {p["falseSuggestions"]} | '
                         f'{r["hits"]} | {r["falseSuggestions"]} |')
        for warning in sorted(set(case['warnings'])):
            lines += ['', f'**Warning:** {warning}']
        lines += ['', '| Day | Production | Hypothetical replay |', '|---|---|---|']
        for (day, p_result, _, _), (_, r_result, _, _) in zip(
                case['scans'][0]['productionDays'], case['scans'][0]['replayDays']):
            lines.append(f'| {day} | {p_result} | {r_result} |')
        lines += ['', 'Scans of the same photo assess repeatability, not generalization.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Optional Markdown output filename')
    args = parser.parse_args(argv)
    try:
        results = evaluate(args.manifest)
        report = markdown(results)
        if args.output:
            args.output.write_text(report + '\n', encoding='utf-8')
        else:
            print(report)
    except EvaluationError as exc:
        print(f'VALIDATION FAILED: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
