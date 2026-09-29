"""Synthetic fixtures only: global structural time bands are not final day-level times."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('labelled_time', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class TestTimeEvidence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.truth = {'schemaVersion': 1,
                      'shifts': [{'weekday': d, 'physicalBlock': 0 if d == 0 else 2,
                                  'startTime': '09:30' if d == 0 else '16:00'}
                                 for d in (0, 1, 2, 3, 5, 6)],
                      'offWeekdays': [4]}
        def day(d):
            accepted = d in (0, 1, 3, 6)
            status = 'accepted_normal' if accepted else 'rejected_below_rescue_floor'
            block = 0 if d == 0 else 2
            return {'weekdayColumn': d, 'decision': status,
                    'topCandidates': [{'physicalBlockIndex': block}],
                    'shadowReplay': {'originalStatus': status, 'replayStatus': status,
                                     'baselineParity': True, 'replayWinnerBlock': block}}
        self.scan = {'schemaVersion': 15, 'scanStage': 'COMPLETE',
                     'scanInProgress': False, 'sessionId': 's1',
                     'structuralTimeEvidence': [
                         {'physicalBlockIndex': 0, 'proposedTime': '09:00',
                          'requiresReview': True, 'distinctSupportColumns': 1,
                          'strongSupportColumns': 0, 'independentAtlasColumns': 0,
                          'alternatives': [{'time': '09:00'}, {'time': '09:30'}]},
                         {'physicalBlockIndex': 1, 'proposedTime': '13:00',
                          'requiresReview': False, 'distinctSupportColumns': 1,
                          'strongSupportColumns': 1, 'independentAtlasColumns': 0,
                          'alternatives': [{'time': '13:00'}]},
                         {'physicalBlockIndex': 2, 'proposedTime': '09:00',
                          'requiresReview': True, 'distinctSupportColumns': 1,
                          'strongSupportColumns': 0, 'independentAtlasColumns': 0,
                          'alternatives': [{'time': '09:00'}, {'time': '16:00'}]}],
                     'viewerMarkers': {'recognitionRunId': 'r1',
                                       'savedProfileDecisions': [day(d) for d in range(7)]}}

    def write(self, name, obj):
        path = self.root / name
        path.write_text(json.dumps(obj), encoding='utf-8')
        return path

    def evaluate(self):
        self.write('truth.json', self.truth)
        self.write('automatic.json', self.scan)
        manifest = self.write('manifest.json', {'schemaVersion': 2, 'cases': [
            {'id': 'fixture', 'groundTruth': 'truth.json',
             'predictions': [{'path': 'automatic.json', 'origin': 'automatic_export'}]}]})
        return m.evaluate(manifest)

    def test_preliminary_proposals_are_not_final_day_accuracy(self):
        report = m.time_evidence_report(self.evaluate())
        self.assertIn('NOT start-time recognition accuracy', report)
        self.assertIn('B1 | 09:30 | 09:00 | 09:00, 09:30', report)
        self.assertIn('B3 | 16:00 | 09:00 | 09:00, 16:00', report)
        self.assertIn('confirmed time appears only as an alternative', report)
        self.assertNotIn('B2 |', report)
        self.assertIn('No final time-accuracy percentage', report)

    def test_matching_proposal_explicitly_not_final_prediction(self):
        self.scan['structuralTimeEvidence'][0]['proposedTime'] = '09:30'
        report = m.time_evidence_report(self.evaluate())
        self.assertIn('proposal matches label; final day assignments unknown', report)

    def test_missing_time_labels_remain_missing_not_zero_accuracy(self):
        for shift in self.truth['shifts']:
            shift.pop('startTime')
        report = m.time_evidence_report(self.evaluate())
        self.assertIn('No confirmed startTime labels', report)

    def test_missing_time_evidence_remains_unknown(self):
        self.scan.pop('structuralTimeEvidence')
        report = m.time_evidence_report(self.evaluate())
        self.assertIn('No exported structural evidence', report)

    def test_duplicate_structural_block_rejected(self):
        self.scan['structuralTimeEvidence'].append(self.scan['structuralTimeEvidence'][0].copy())
        with self.assertRaisesRegex(m.EvaluationError, 'unique'):
            self.evaluate()

    def test_invalid_confirmed_time_rejected(self):
        self.truth['shifts'][0]['startTime'] = '25:99'
        with self.assertRaisesRegex(m.EvaluationError, '24-hour HH:MM'):
            self.evaluate()

    def test_conflicting_expected_times_in_same_block_not_conflated(self):
        self.truth['shifts'][1]['startTime'] = '15:00'
        report = m.time_evidence_report(self.evaluate())
        self.assertIn('15:00, 16:00', report)
        self.assertIn('Multiple labelled times for this block', report)

    def test_time_evidence_output_and_collision(self):
        self.write('truth.json', self.truth)
        self.write('automatic.json', self.scan)
        manifest = self.write('manifest.json', {'schemaVersion': 2, 'cases': [
            {'id': 'fixture', 'groundTruth': 'truth.json',
             'predictions': [{'path': 'automatic.json', 'origin': 'automatic_export'}]}]})
        outfile = self.root / 'times.md'
        self.assertEqual(m.main(['--manifest', str(manifest), '--time-evidence', str(outfile),
                                 '--output', str(outfile)]), 1)
        self.assertFalse(outfile.exists())
        self.assertEqual(m.main(['--manifest', str(manifest), '--time-evidence', str(outfile),
                                 '--output', str(self.root / 'evaluation.md')]), 0)
        self.assertIn('09:30', outfile.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
