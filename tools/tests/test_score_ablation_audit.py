"""Fixed-rule exported ranking ablations are diagnostics, never predictions."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('ablation_evaluator', SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def candidate(block, adjusted, positive, separation, origin='ocr_token_band'):
    return {'physicalBlockIndex': block, 'adjustedScore': adjusted,
            'positiveScore': positive, 'rawSeparation': separation,
            'candidateOrigin': origin}


def row(day, candidates, accepted=False):
    decision = 'accepted_normal' if accepted else 'rejected_below_rescue_floor'
    return {'weekdayColumn': day, 'decision': decision, 'topCandidates': candidates,
            'shadowReplay': {'baselineParity': True, 'originalStatus': decision,
                             'replayStatus': decision,
                             'replayWinnerBlock': candidates[0]['physicalBlockIndex'] if candidates else None}}


class ScoreAblationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.truth = {0: 0, 1: 2, 2: 2, 3: 2, 4: None, 5: 2, 6: 2}
        self.rows = {day: row(day, [candidate(self.truth[day] or 0, .7, .8, .1)],
                              accepted=self.truth[day] is not None) for day in range(7)}
        self.rows[2] = row(2, [candidate(2, .429, .549, .031),
                               candidate(0, .422, .542, -.016, 'ink_gap_probe')])
        self.rows[4] = row(4, [candidate(2, .431, .551, -.024),
                               candidate(0, .428, .548, -.010, 'ink_gap_probe')])
        self.rows[5] = row(5, [candidate(0, .443, .563, -.025, 'ink_gap_probe'),
                               candidate(2, .431, .551, -.017),
                               candidate(2, .428, .548, -.035)])

    def result(self):
        return [{'id': 'fixture', 'scans': [{'path': 'automatic.json', 'fingerprint': 'fp',
                    'truth': self.truth, 'rows': self.rows}]}]

    def test_all_seven_days_and_friday_control_are_visible(self):
        report = m.score_ablation_report(self.result())
        self.assertEqual(sum(report.count('| '+day+' |') for day in m.DAYS), 7)
        self.assertIn('Friday | OFF', report)
        self.assertIn('OFF control; no acceptance simulated', report)
        self.assertIn('not be reported as recovered shifts', report)

    def test_wednesday_and_saturday_ablation_is_descriptive(self):
        report = m.score_ablation_report(self.result())
        wednesday = next(line for line in report.splitlines() if line.startswith('| Wednesday |'))
        saturday = next(line for line in report.splitlines() if line.startswith('| Saturday |'))
        self.assertIn('B3 / 0.429; label rank 1', wednesday)
        self.assertIn('B1 / 0.443; label rank 2', saturday)
        self.assertIn('B3 / 0.431; label rank 1', saturday)  # OCR-only subset observation

    def test_duplicate_same_block_collapsed_and_top_three_only(self):
        ranked, observed = m._ablation_order(self.rows[5], 'adjustedScore')
        self.assertEqual(3, observed)
        self.assertEqual([(0, .443), (2, .431)], ranked)
        self.rows[5]['topCandidates'].append(candidate(2, .99, .99, .99))
        ranked, observed = m._ablation_order(self.rows[5], 'adjustedScore')
        self.assertEqual([(0, .443), (2, .431)], ranked)
        self.assertEqual(3, observed)

    def test_missing_fields_and_nonfinite_scores_are_not_invented(self):
        self.rows[2]['topCandidates'] = [candidate(0, float('nan'), .5, -.1),
                                         {'physicalBlockIndex': 2, 'candidateOrigin': 'ocr_token_band'}]
        report = m.score_ablation_report(self.result())
        wednesday = next(line for line in report.splitlines() if line.startswith('| Wednesday |'))
        self.assertIn('not observable', wednesday)
        self.assertIn('label absent in compared export subset', wednesday)

    def test_repeated_fingerprint_warning(self):
        result = self.result()
        result[0]['scans'].append(dict(result[0]['scans'][0], path='repeat.json'))
        self.assertIn('NOT an independent photograph', m.score_ablation_report(result))

    def test_cli_report_output_path_collisions_fail_before_writing(self):
        truth = {'schemaVersion': 1, 'shifts': [
            {'weekday': d, 'physicalBlock': self.truth[d]} for d in range(7)
            if self.truth[d] is not None], 'offWeekdays': [4]}
        scan = {'schemaVersion': 15, 'scanInProgress': False, 'scanStage': 'COMPLETE',
                'sessionId': 's1', 'viewerMarkers': {'recognitionRunId': 'r1',
                'savedProfileDecisions': list(self.rows.values())}}
        manifest = {'schemaVersion': 2, 'cases': [{'id': 'fixture', 'groundTruth': 'truth.json',
                    'predictions': [{'path': 'scan.json', 'origin': 'automatic_export'}]}]}
        for name, content in [('truth.json', truth), ('scan.json', scan), ('manifest.json', manifest)]:
            (self.root/name).write_text(json.dumps(content), encoding='utf-8')
        dest = self.root/'ablation.md'
        self.assertEqual(1, m.main(['--manifest', str(self.root/'manifest.json'),
                                    '--output', str(dest), '--score-ablation', str(dest)]))
        self.assertFalse(dest.exists())
        self.assertEqual(0, m.main(['--manifest', str(self.root/'manifest.json'),
                                    '--score-ablation', str(dest)]))
        self.assertIn('Friday | OFF', dest.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
