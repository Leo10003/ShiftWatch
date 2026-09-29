"""Complete scored-crop export validation and all-candidate, fixed ranking comparisons."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('complete_scored_evaluator', MODULE_PATH)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def candidate(block, score, positive, negative, origin='ink_gap_probe', rank=0):
    return {'rank': rank, 'physicalBlockIndex': block, 'verticalDecile': 6,
            'adjustedScore': score, 'positiveScore': positive, 'confuserScore': negative,
            'rawSeparation': positive - negative, 'confuserPenalty': 0,
            'separationAdjustment': score - positive, 'candidateOrigin': origin}


def row(day, alternatives):
    all_rows = [dict(item, rank=i + 1) for i, item in enumerate(
        sorted(alternatives, key=lambda c: -c['adjustedScore']))]
    summaries = []
    for block in sorted({c['physicalBlockIndex'] for c in all_rows
                         if c['physicalBlockIndex'] is not None}):
        matches = [c for c in all_rows if c['physicalBlockIndex'] == block]
        if matches:
            summaries.append({'physicalBlockIndex': block, 'candidateCount': len(matches),
                              'best': {'adjustedScore': matches[0]['adjustedScore']}})
    return {'weekdayColumn': day, 'decision': 'rejected_below_rescue_floor',
            'scoredLineCount': len(all_rows), 'completeProductionCandidates': all_rows,
            'topCandidates': all_rows[:3], 'productionByBlock': summaries,
            'shadowReplay': {'baselineParity': True,
                             'originalStatus': 'rejected_below_rescue_floor',
                             'replayStatus': 'rejected_below_rescue_floor'}}


class CompleteCandidateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.truth = {0: 0, 1: 2, 2: 2, 3: 2, 4: None, 5: 2, 6: 2}
        standard = [candidate(0, .69, .69, .56, 'ocr_token_band'),
                    candidate(2, .40, .52, .56),
                    candidate(1, .35, .47, .52),
                    candidate(2, .30, .50, .54, 'ocr_token_band')]
        self.rows = {d: row(d, standard) for d in range(7)}
        # Saturday: stronger raw separation on B3 only in fourth adjusted crop.
        self.rows[5] = row(5, [candidate(0, .443, .563, .588),
                               candidate(2, .431, .551, .568, 'ocr_token_band'),
                               candidate(2, .428, .548, .583),
                               candidate(2, .39, .65, .56)])
        # Friday OFF is always reported regardless of the ranking rule.
        self.rows[4] = row(4, [candidate(2, .431, .551, .575),
                               candidate(0, .425, .545, .572)])
        # Sunday correctly identifies B3 by an ink gap; shadow OCR is irrelevant.
        self.rows[6] = row(6, [candidate(2, .699, .699, .57),
                               candidate(0, .442, .56, .58, 'ocr_token_band')])

    def result(self):
        return [{'id': 'reference', 'scans': [{'path': 'automatic-v27.json',
                 'fingerprint': 'a', 'schemaVersion': 16, 'truth': self.truth, 'rows': self.rows}]}]

    def test_full_ranking_includes_fourth_candidate_and_off_control(self):
        text = m.complete_candidate_report(self.result())
        saturday = next(line for line in text.splitlines() if line.startswith('| Saturday |'))
        self.assertIn('4 / 1', saturday)
        self.assertIn('B3 / 0.090; label rank 1', saturday)
        self.assertIn('B1 / 0.443; label rank 2', saturday)
        friday = next(line for line in text.splitlines() if line.startswith('| Friday |'))
        self.assertIn('OFF control; no acceptance tested', friday)
        sunday = next(line for line in text.splitlines() if line.startswith('| Sunday |'))
        self.assertIn('B3 / 0.699; label rank 1', sunday)
        self.assertIn('B1 / 0.442; label absent from scored subset', sunday)
        self.assertEqual(7, sum(text.count('| ' + d + ' |') for d in m.DAYS))

    def test_reject_truncated_or_invalid_complete_exports(self):
        base = self.rows[0]
        for alteration in (
            lambda r: r.pop('completeProductionCandidates'),
            lambda r: r['completeProductionCandidates'].pop(),
            lambda r: r['completeProductionCandidates'][1].update(rank=1),
            lambda r: r['completeProductionCandidates'][0].update(rawSeparation=float('nan')),
            lambda r: r['topCandidates'][0].update(adjustedScore=0.0),
            lambda r: r['productionByBlock'][0].update(candidateCount=50),
            lambda r: r['completeProductionCandidates'][0].update(candidateOrigin='unknown'),
        ):
            import copy
            modified = copy.deepcopy(base)
            alteration(modified)
            with self.subTest(alteration=alteration):
                with self.assertRaises(m.EvaluationError):
                    m._validated_complete_candidates(modified, 'scan.json', 0)

    def test_empty_scored_list_and_nullable_blocks(self):
        empty = row(0, [])
        self.assertEqual([], m._validated_complete_candidates(empty, 's', 0))
        unknown = row(0, [candidate(None, .4, .5, .2)])
        self.assertEqual(1, len(m._validated_complete_candidates(unknown, 's', 0)))
        self.assertEqual([], m._complete_order(unknown['completeProductionCandidates'], 'adjustedScore'))

    def test_fourth_detected_region_is_preserved_but_not_a_labelled_block(self):
        # A real export can contain out-of-layout scored crops. These must
        # match the production block summary, but cannot create a B4 label.
        extra = row(3, [candidate(3, .99, .99, .10),
                        candidate(2, .40, .52, .56)])
        full = m._validated_complete_candidates(extra, 'scan.json', 3)
        self.assertEqual(2, len(full))
        self.assertEqual(3, full[0]['physicalBlockIndex'])
        self.assertEqual([(2, .40)], m._complete_order(full, 'adjustedScore'))
        self.assertEqual([], m._complete_order(full[:1], 'adjustedScore'))
        extra['completeProductionCandidates'][0]['physicalBlockIndex'] = -1
        with self.assertRaises(m.EvaluationError):
            m._validated_complete_candidates(extra, 'scan.json', 3)

    def test_cli_schema15_rejected_before_any_report_is_written(self):
        truth = {'schemaVersion': 1, 'shifts': [
            {'weekday': d, 'physicalBlock': block} for d, block in self.truth.items()
            if block is not None], 'offWeekdays': [4]}
        import copy
        scan = {'schemaVersion': 15, 'scanInProgress': False, 'scanStage': 'COMPLETE',
                'sessionId': 'session', 'viewerMarkers': {'recognitionRunId': 'run',
                'savedProfileDecisions': copy.deepcopy(list(self.rows.values()))}}
        manifest = {'schemaVersion': 2, 'cases': [{'id': 'reference',
                    'groundTruth': 'truth.json', 'predictions': [
                    {'path': 'automatic.json', 'origin': 'automatic_export'}]}]}
        for name, document in [('truth.json', truth), ('automatic.json', scan),
                               ('manifest.json', manifest)]:
            (self.folder / name).write_text(json.dumps(document), encoding='utf-8')
        target = self.folder / 'all.md'
        old = scan['viewerMarkers']['savedProfileDecisions'][3]
        old.pop('completeProductionCandidates')
        (self.folder / 'automatic.json').write_text(json.dumps(scan), encoding='utf-8')
        self.assertEqual(1, m.main(['--manifest', str(self.folder / 'manifest.json'),
                                    '--output', str(self.folder / 'base.md'),
                                    '--complete-candidates', str(target)]))
        self.assertFalse(target.exists())
        self.assertFalse((self.folder / 'base.md').exists())
        scan['schemaVersion'] = 16
        scan['viewerMarkers']['savedProfileDecisions'][3] = self.rows[3]
        (self.folder / 'automatic.json').write_text(json.dumps(scan), encoding='utf-8')
        self.assertEqual(0, m.main(['--manifest', str(self.folder / 'manifest.json'),
                                    '--output', str(self.folder / 'base.md'),
                                    '--complete-candidates', str(target)]))
        self.assertIn('Friday', target.read_text())

    def test_report_requires_schema16_not_just_similar_fields(self):
        data = self.result()
        data[0]['scans'][0]['schemaVersion'] = 15
        with self.assertRaisesRegex(m.EvaluationError, 'schemaVersion'):
            m.complete_candidate_report(data)

    def test_report_has_no_effect_on_production_rows(self):
        import copy
        before = copy.deepcopy(self.rows)
        m.complete_candidate_report(self.result())
        self.assertEqual(before, self.rows)


if __name__ == '__main__':
    unittest.main()
