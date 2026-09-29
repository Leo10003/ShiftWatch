#!/usr/bin/env python3
"""Strict, offline, labelled multi-photo diagnostic evaluator. Python stdlib only.

This does not perform OCR and never uses experimental replay as a production suggestion.
"""
import argparse
import json
import re
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


def parse_truth_times(doc, source):
    """Optional user-confirmed start times; these are NOT observed app predictions."""
    times = {}
    for entry in doc.get('shifts', []):
        if 'startTime' not in entry:
            continue
        value = entry['startTime']
        require(isinstance(value, str) and re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d', value),
                f'{source}: startTime must use 24-hour HH:MM')
        times[entry['weekday']] = value
    return times


def structural_time_rows(doc, source):
    """A global block-level hypothesis; NOT each day's final assigned start time."""
    raw = doc.get('structuralTimeEvidence')
    if raw is None:
        return None
    require(isinstance(raw, list), f'{source}: structuralTimeEvidence must be an array')
    results = {}
    for band in raw:
        require(isinstance(band, dict), f'{source}: invalid structural time evidence')
        b = band.get('physicalBlockIndex')
        require(type(b) is int and 0 <= b <= 2 and b not in results,
                f'{source}: structural time block must be unique, zero-based, 0 to 2')
        proposed = band.get('proposedTime')
        require(proposed is None or isinstance(proposed, str),
                f'{source}: invalid structural proposed time for block {b}')
        alternatives = band.get('alternatives', [])
        require(isinstance(alternatives, list), f'{source}: invalid alternatives in block {b}')
        times = []
        for alt in alternatives:
            require(isinstance(alt, dict) and isinstance(alt.get('time'), str),
                    f'{source}: invalid structural alternative in block {b}')
            times.append(alt['time'])
        results[b] = {'proposal': proposed, 'alternatives': times,
                      'requiresReview': band.get('requiresReview'),
                      'confidence': band.get('confidence'),
                      'supportColumns': band.get('distinctSupportColumns'),
                      'strongColumns': band.get('strongSupportColumns'),
                      'independentAtlasColumns': band.get('independentAtlasColumns')}
    return results


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
    require(isinstance(manifest, dict) and manifest.get('schemaVersion') in (1, 2)
            and isinstance(manifest.get('cases'), list) and manifest['cases'],
            'Manifest requires schemaVersion 1 or 2 and a nonempty cases array')
    result, seen_id, sessions, runs, fingerprints = [], set(), set(), set(), {}
    for case in manifest['cases']:
        require(isinstance(case, dict) and isinstance(case.get('id'), str) and case['id'].strip(),
                'Each case must have an id')
        cid = case['id']
        require(cid not in seen_id, f'Duplicate case id {cid}')
        seen_id.add(cid)
        is_v2 = manifest['schemaVersion'] == 2
        scans = case.get('predictions') if is_v2 else case.get('scans')
        require(isinstance(case.get('groundTruth'), str) and isinstance(scans, list)
                and scans, f'{cid}: groundTruth and nonempty predictions/scans required')
        truth_path = manifest_path.parent / case['groundTruth']
        truth_doc = read_json(truth_path)
        truth = parse_truth(truth_doc, truth_path)
        truth_times = parse_truth_times(truth_doc, truth_path)
        entry = {'id': cid, 'scans': [], 'warnings': []}
        if not is_v2:
            entry['warnings'].append(
                'Legacy manifest has no prediction provenance; use schemaVersion 2. '
                'Never evaluate manually corrected scan files as automatic predictions.')
        for item in scans:
            if is_v2:
                require(isinstance(item, dict), f'{cid}: schemaVersion 2 predictions must be objects')
                require(item.get('origin') == 'automatic_export',
                        f'{cid}: prediction origin must be automatic_export; '
                        'manually corrected files belong in groundTruth only')
                scan = item.get('path')
            else:
                scan = item
            require(isinstance(scan, str) and bool(scan), f'{cid}: prediction path must be a string')
            p = manifest_path.parent / scan
            require(p.resolve() != truth_path.resolve(),
                    f'{cid}: prediction and groundTruth must be different files')
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
            entry['scans'].append({'path': scan, 'origin': 'automatic_export' if is_v2 else 'unverified_legacy',
                                   'production': prod, 'replay': replay,
                                   'productionDays': p_days, 'replayDays': r_days,
                                   'rows': rows, 'truth': truth, 'truthTimes': truth_times,
                                   'timeBands': structural_time_rows(doc, p),
                                   'fingerprint': fingerprint})
        result.append(entry)
    return result


def markdown(result):
    lines = ['# ShiftWatch labelled diagnostic evaluation', '',
             '**Diagnostic only:** working-day block identity and OFF suggestions; '
             'start times and week dates are not evaluated. Replay is hypothetical, never a production suggestion.', '']
    photo_count = len(result)
    lines += [f'Labelled cases: {photo_count}. Different case IDs alone do not prove different photographs.',
              '**Provenance:** automatic_export is user-declared, not verified by sanitized JSON. '
              'Manually corrected scan files are ground truth, never predictions.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '', '| Prediction file | Production hits | Production OFF false | Replay hits | Replay OFF false |',
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


def time_evidence_report(result):
    """Compare labelled times with preliminary structural hypotheses ONLY."""
    lines = ['# ShiftWatch labelled structural time evidence', '',
             '**Evidence audit, NOT start-time recognition accuracy.** Structural block proposals '
             'are preliminary shared hypotheses, not final per-day shift start times. '
             'A match is not proof the app assigned that hour to the correct day.', '',
             'Ground-truth start times are independently user-declared; automatic-export '
             'provenance is also user-declared. No manual corrections are predictions.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '']
        for scan in case['scans']:
            times, truth, bands = scan['truthTimes'], scan['truth'], scan['timeBands']
            lines += [f'### Automatic export: {scan["path"]}', '',
                      'These global block bands must not be counted once per weekday. '
                      'No final day-level start-time prediction is available in this export.', '']
            if not times:
                lines += ['No confirmed startTime labels. No structural time comparison possible.', '']
                continue
            by_block = {}
            for d, start in times.items():
                by_block.setdefault(truth[d], set()).add(start)
            lines += ['| Block | Confirmed time(s) | Structural proposal | Alternatives | Review | '
                      'Confidence | Distinct / strong / atlas columns | Evidence assessment |',
                      '|---|---|---|---|---|---|---|---|']
            for b, confirmed in sorted(by_block.items()):
                evidence = bands.get(b) if bands is not None else None
                labels = ', '.join(sorted(confirmed))
                if len(confirmed) > 1:
                    assessment = 'Multiple labelled times for this block; no single block-level comparison'
                elif evidence is None:
                    assessment = 'No exported structural evidence for this block'
                elif evidence['proposal'] in confirmed:
                    assessment = 'Preliminary structural proposal matches label; final day assignments unknown'
                elif confirmed.intersection(evidence['alternatives']):
                    assessment = 'Preliminary proposal differs; confirmed time appears only as an alternative'
                else:
                    assessment = 'Preliminary proposal differs; confirmed time absent from alternatives'
                proposal = evidence['proposal'] if evidence else 'not exported'
                alts = ', '.join(evidence['alternatives']) if evidence else 'not exported'
                review = evidence['requiresReview'] if evidence else 'not exported'
                confidence = fmt_score(evidence['confidence']) if evidence else 'not exported'
                supports = (f'{evidence["supportColumns"]} / {evidence["strongColumns"]} / '
                            f'{evidence["independentAtlasColumns"]}' if evidence else 'not exported')
                lines.append(f'| B{b+1} | {labels} | {proposal or "none"} | {alts} | '
                             f'{review} | {confidence} | {supports} | {assessment} |')
            lines += ['', '**No final time-accuracy percentage can be calculated from these fields.**', '']
    return '\n'.join(lines)


def fmt_score(value):
    return f'{value:.3f}' if type(value) in (int, float) else 'n/a'


def candidate_audit(result):
    """Label-dependent, read-only candidate evidence; never proposes acceptance."""
    lines = ['# ShiftWatch labelled missed-candidate audit', '',
             '**Research only:** rejected working days and accepted OFF days. '
             'Scores and crop variants are observations, not calibrated probabilities. '
             'Experimental replay never changes production.', '',
             'Photos are not available in sanitized exports. Shared OCR fingerprints '
             'do not establish independent photographic evidence.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '']
        seen_fingerprints = set()
        for scan in case['scans']:
            fp = scan['fingerprint']
            duplicate = bool(fp and fp in seen_fingerprints)
            if fp:
                seen_fingerprints.add(fp)
            lines += [f'### Scan: {scan["path"]}', '',
                      ('**Warning:** OCR fingerprint repeats within this case; '
                       'additional runs are not independent-photo evidence.' if duplicate else
                       'OCR geometry fingerprint: ' + (str(fp) if fp else 'unavailable')), '']
            rows, truth = scan['rows'], scan['truth']
            problems = [d for d in range(7) if
                        (truth[d] is not None and not rows[d]['decision'].startswith('accepted_'))
                        or (truth[d] is None and rows[d]['decision'].startswith('accepted_'))]
            if not problems:
                lines += ['No rejected working days or accepted OFF days in this scan.', '']
                continue
            for d in problems:
                row = rows[d]
                replay = row['shadowReplay']
                expected = 'OFF' if truth[d] is None else f'block {truth[d]+1}'
                reason = ('FALSE OFF-DAY SUGGESTION' if truth[d] is None
                          else 'MISSED WORKING DAY')
                lines += [f'#### {DAYS[d]} — {reason}', '',
                          f'Label: {expected}; production: {row["decision"]}; '
                          f'hypothetical replay: {replay.get("replayStatus", "missing")}.', '',
                          '| Evidence | Value |', '|---|---:|',
                          f'| Production top score | {fmt_score(row.get("topScore"))} |',
                          f'| Production runner score | {fmt_score(row.get("runnerScore"))} |',
                          f'| Acceptance floor | {fmt_score(row.get("acceptanceFloor"))} |',
                          f'| Candidate lines / scored | {row.get("candidateLineCount", "n/a")} / {row.get("scoredLineCount", "n/a")} |',
                          f'| Replay top block (not a suggestion if rejected) | {replay.get("replayWinnerBlock", "n/a")} (zero-based) |',
                          f'| Replay winner score | {fmt_score(replay.get("replayScore"))} |',
                          f'| Replay runner / margin | {fmt_score(replay.get("replayRunner"))} / {fmt_score(replay.get("replayMargin"))} |',
                          f'| Replay trimmed winner | {replay.get("trimmedWinner", "n/a")} |', '']
                pipeline = row.get('candidatePipeline')
                if isinstance(pipeline, dict):
                    lines += [f'Candidate pipeline: final={pipeline.get("finalCandidates", "n/a")}, '
                              f'OCR={pipeline.get("finalOcrCandidates", "n/a")}, '
                              f'probes added={pipeline.get("probesAdded", "n/a")}, '
                              f'ink rejected={pipeline.get("probesInkRejected", "n/a")}.', '']
                candidates = row.get('topCandidates')
                if isinstance(candidates, list) and candidates:
                    lines += ['| Rank | Block | Origin | Adjusted | Positive | Confuser | Raw separation |',
                              '|---:|---:|---|---:|---:|---:|---:|']
                    for candidate in candidates[:3]:
                        if not isinstance(candidate, dict):
                            continue
                        block = candidate.get('physicalBlockIndex')
                        lines.append(f'| {candidate.get("rank", "?")} | '
                                     f'{block+1 if type(block) is int and block in range(3) else "n/a"} | '
                                     f'{candidate.get("candidateOrigin", "n/a")} | '
                                     f'{fmt_score(candidate.get("adjustedScore"))} | '
                                     f'{fmt_score(candidate.get("positiveScore"))} | '
                                     f'{fmt_score(candidate.get("confuserScore"))} | '
                                     f'{fmt_score(candidate.get("rawSeparation"))} |')
                    lines += ['']
                experiments = row.get('cropExperiments')
                if isinstance(experiments, list) and experiments:
                    lines += ['| Block / rank | Origin | Eligible | Qualified | Original adj. / separation | Trim adj. / separation |',
                              '|---|---|---|---|---|---|']
                    for experiment in experiments:
                        if not isinstance(experiment, dict):
                            continue
                        variants = {v.get('variant'): v for v in experiment.get('variants', [])
                                    if isinstance(v, dict)}
                        orig, trim = variants.get('original', {}), variants.get('trim_12', {})
                        block = experiment.get('physicalBlockIndex')
                        label = f'B{block+1}' if type(block) is int and block in range(3) else 'n/a'
                        def detail(v):
                            return f'{fmt_score(v.get("adjustedScore"))} / {fmt_score(v.get("rawSeparation"))}'
                        lines.append(f'| {label} / {experiment.get("candidateRankInBlock", "?")} | '
                                     f'{experiment.get("candidateOrigin", "n/a")} | '
                                     f'{experiment.get("selectiveTrimEligible", "n/a")} | '
                                     f'{experiment.get("selectiveTrimQualifies", "n/a")} | '
                                     f'{detail(orig)} | {detail(trim)} |')
                    lines += ['']
                else:
                    lines += ['Crop experiment evidence not exported for this day.', '']
                if truth[d] is not None and replay.get('replayAccepted'):
                    winner = replay.get('replayWinnerBlock')
                    lines += [('**Hypothetical replay:** correct block, still not a production result.'
                               if type(winner) is int and winner == truth[d] else
                               '**Caution:** replay accepts a different/unknown block; NOT a recovery.'), '']
                if truth[d] is None and replay.get('replayAccepted'):
                    lines += ['**SAFETY FLAG:** hypothetical replay also suggests an OFF day.', '']
    lines += ['All labels are externally supplied. Same fingerprint and separate session IDs '
              'establish repeat scans, not independent photos. No training data, names, '
              'photos or OCR text are required.', '']
    return '\n'.join(lines)



def ranking_safety_report(result):
    """Read-only label-dependent ranking and negative-control audit, not a new decision rule."""
    lines = ['# ShiftWatch labelled ranking-safety audit', '',
             '**Research only:** this inspects exported top-three production candidates and the '
             'reported acceptance floor. It does not rescore crops, replay altered thresholds, '
             'or propose automatic shifts.', '',
             'Ground-truth labels and prediction provenance are user-declared. '
             'Candidate scores are not calibrated probabilities. '
             'Top-three absence does not prove a candidate was never generated.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '']
        seen = set()
        for scan in case['scans']:
            fp = scan['fingerprint']
            lines += [f'### Automatic export: {scan["path"]}', '']
            if fp and fp in seen:
                lines += ['**Warning:** repeated OCR fingerprint; do not count this as an '
                          'independent photograph.', '']
            if fp:
                seen.add(fp)
            lines += ['| Day | Label | Production | Top block / score | Top-three label rank | '
                      'Floor gap | Top margin | Top raw separation | Diagnostic classification |',
                      '|---|---|---|---|---|---:|---:|---:|---|']
            for d in range(7):
                row = scan['rows'][d]
                label = scan['truth'][d]
                status = row['decision']
                accepted = status.startswith('accepted')
                candidates = row.get('topCandidates')
                candidates = candidates[:3] if isinstance(candidates, list) else []
                valid = [c for c in candidates if isinstance(c, dict) and
                         type(c.get('physicalBlockIndex')) is int and
                         0 <= c['physicalBlockIndex'] <= 2]
                top = valid[0] if valid else None
                top_block = top['physicalBlockIndex'] if top else None
                top_score = top.get('adjustedScore') if top else None
                floor = row.get('acceptanceFloor')
                next_score = valid[1].get('adjustedScore') if len(valid) > 1 else None
                margin = (top_score - next_score if type(top_score) in (int, float)
                          and type(next_score) in (int, float) else None)
                gap = (top_score - floor if type(top_score) in (int, float)
                       and type(floor) in (int, float) else None)
                if label is None:
                    label_text, label_rank = 'OFF', 'n/a'
                    category = ('OFF FALSE SUGGESTION' if accepted else
                                'OFF correctly rejected; negative control')
                else:
                    label_text = f'B{label+1}'
                    correct = [i + 1 for i, c in enumerate(valid)
                               if c['physicalBlockIndex'] == label]
                    label_rank = str(correct[0]) if correct else 'not in exported top three'
                    if accepted:
                        category = ('accepted correct block' if top_block == label else
                                    'WRONG BLOCK ACCEPTED')
                    elif not valid:
                        category = 'rejected; no exported top-three candidate'
                    elif top_block == label:
                        category = 'rejected; correct block leads'
                    elif correct:
                        category = 'rejected; competing block leads'
                    else:
                        category = 'rejected; labelled block absent from exported top three'
                top_text = (f'B{top_block+1} / {fmt_score(top_score)}' if top else 'not exported')
                lines.append(f'| {DAYS[d]} | {label_text} | {status} | {top_text} | '
                             f'{label_rank} | {fmt_score(gap)} | {fmt_score(margin)} | '
                             f'{fmt_score(top.get("rawSeparation") if top else None)} | '
                             f'{category} |')
            lines += ['', 'Floor gap is top adjusted score minus the exported acceptance floor; '
                      'it is descriptive and does **not** capture all production gates. '
                      'Top margin uses only the top two exported candidates, if available.', '']
    lines += ['**Safety:** never lower thresholds or override OFF protection based on this '
              'report alone. Obtain independently photographed and labelled rosters, '
              'and evaluate false suggestions and wrong blocks before production changes.', '']
    return '\n'.join(lines)




def _finite_number(value):
    """Only exported real, finite numeric fields can support an ablation comparison."""
    import math
    return type(value) in (int, float) and math.isfinite(value)


def _ablation_order(row, field, ocr_only=False):
    """Rank *exported* top-three crops without using ground-truth labels.

    The production candidate list is truncated: these are not full-search replays.
    Comparing crops within the same physical block would misstate block ranking,
    so show the strongest observed crop per block for each fixed ranking rule.
    """
    exported = row.get('topCandidates')
    if not isinstance(exported, list):
        return [], 0
    observed = exported[:3]
    best = {}
    for candidate in observed:
        if not isinstance(candidate, dict):
            continue
        block = candidate.get('physicalBlockIndex')
        score = candidate.get(field)
        if (type(block) is not int or block not in (0, 1, 2) or
                not _finite_number(score) or
                (ocr_only and candidate.get('candidateOrigin') != 'ocr_token_band')):
            continue
        # The sorting fields are predeclared; the expected truth label is never a feature.
        if block not in best or score > best[block]:
            best[block] = score
    # Stable, transparent numerical tie-breaking without claiming a resolved decision.
    ordered = sorted(best.items(), key=lambda item: (-item[1], item[0]))
    return ordered, len(observed)


def _ablation_cell(row, truth, field, ocr_only=False):
    ordered, observed_count = _ablation_order(row, field, ocr_only)
    if not ordered:
        return 'not observable'
    top_score = ordered[0][1]
    ties = sum(1 for _, score in ordered if score == top_score)
    leader = 'tie ' + '/'.join(f'B{b+1}' for b, score in ordered if score == top_score) if ties > 1 else f'B{ordered[0][0]+1}'
    # A missing labelled block is missing from *this export*, not the actual candidate pool.
    if truth is None:
        label_status = 'OFF control; no acceptance simulated'
    else:
        ranks = [i + 1 for i, (block, _) in enumerate(ordered) if block == truth]
        label_status = ('label rank ' + str(ranks[0]) if ranks else
                        'label absent in compared export subset')
    return f'{leader} / {top_score:.3f}; {label_status}'


def score_ablation_report(result):
    """Fixed, descriptive ranking ablations only; NO gates or thresholds are replayed."""
    variants = [('Adjusted', 'adjustedScore', False),
                ('Positive only', 'positiveScore', False),
                ('Raw separation', 'rawSeparation', False),
                ('OCR-origin adjusted only', 'adjustedScore', True)]
    lines = ['# ShiftWatch fixed-score ranking ablations', '',
             '**Research only, NOT production replay:** four predeclared ways to order only '
             'the exported top-three production crops. Labels are used to annotate results, '
             'never to choose or fit the ranking rules. No crop is rescored, no acceptance '
             'or OFF gate is simulated, and these results must not be reported as recovered shifts.', '',
             'The exported top three may include several crops in one block and omit better '
             'unexported candidates. This audit collapses candidates by physical block using '
             'the highest observed value for each variant. An absent block is **not** '
             'evidence that it was never generated. Scores are not calibrated probabilities.', '']
    for case in result:
        lines += [f'## Case: {case["id"]}', '']
        seen = set()
        for scan in case['scans']:
            lines += [f'### Automatic export: {scan["path"]}', '']
            fp = scan['fingerprint']
            if fp and fp in seen:
                lines += ['**Warning:** repeated OCR fingerprint; NOT an independent photograph.', '']
            if fp:
                seen.add(fp)
            lines += ['| Weekday | Confirmed | Production | ' + ' | '.join(v[0] for v in variants) + ' |',
                      '|---|---|---|' + '|'.join(['---'] * len(variants)) + '|']
            for d in range(7):
                row = scan['rows'][d]
                truth = scan['truth'][d]
                label = 'OFF' if truth is None else f'B{truth+1}'
                cells = [_ablation_cell(row, truth, field, ocr)
                         for _, field, ocr in variants]
                lines += [f'| {DAYS[d]} | {label} | {row["decision"]} | ' +
                          ' | '.join(cells) + ' |']
            lines += ['', 'Each cell identifies a **ranking observation, not an accepted shift**. '
                      'OCR-only filtering excludes ink-gap crops *only within the exported top '
                      'three*, and must not be read as a safe production policy.', '']
    lines += ['**Safety check:** explicitly inspect OFF days and any wrong-block leaders '
              'alongside missed days. Do not select a rule or change thresholds using this '
              'single image or its repeated OCR fingerprint. Collect independently photographed '
              'and labelled rosters before testing a production change.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Optional Markdown output filename')
    parser.add_argument('--candidate-audit', type=Path,
                        help='Optional separate labelled missed-candidate audit in Markdown')
    parser.add_argument('--time-evidence', type=Path,
                        help='Optional preliminary structural-time evidence report; NOT time accuracy')
    parser.add_argument('--ranking-safety', type=Path,
                        help='Optional all-seven-day ranking and OFF negative-control audit')
    parser.add_argument('--score-ablation', type=Path,
                        help='Optional fixed-rule top-three ranking comparison; never accepts shifts')
    args = parser.parse_args(argv)
    try:
        results = evaluate(args.manifest)
        report = markdown(results)
        destinations = [p.resolve() for p in (args.candidate_audit, args.output,
                                              args.time_evidence, args.ranking_safety,
                                              args.score_ablation) if p]
        if len(destinations) != len(set(destinations)):
            raise EvaluationError('All report output paths must differ')
        if args.candidate_audit:
            args.candidate_audit.write_text(candidate_audit(results), encoding='utf-8')
        if args.time_evidence:
            args.time_evidence.write_text(time_evidence_report(results) + '\n', encoding='utf-8')
        if args.ranking_safety:
            args.ranking_safety.write_text(ranking_safety_report(results) + '\n', encoding='utf-8')
        if args.score_ablation:
            args.score_ablation.write_text(score_ablation_report(results) + '\n', encoding='utf-8')
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
