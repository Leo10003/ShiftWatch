"""Synthetic, no personal scans or OCR; exercise labelled missed-candidate reporting."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('labelled_audit', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class TestMissedCandidateAudit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.rows = []
        for d in range(7):
            decision = 'rejected_below_rescue_floor' if d in (2, 4, 5) else 'accepted_normal'
            block = 0 if d == 0 else 2
            self.rows.append({'weekdayColumn': d, 'decision': decision,
                              'topScore': .43 if d in (2, 5) else .72,
                              'runnerScore': .42, 'acceptanceFloor': .55,
                              'candidateLineCount': 17, 'scoredLineCount': 14,
                              'topCandidates': [{'rank': 1, 'physicalBlockIndex': block,
                                                 'candidateOrigin': 'ocr_token_band',
                                                 'adjustedScore': .43, 'positiveScore': .55,
                                                 'confuserScore': .52, 'rawSeparation': .03}],
                              'shadowReplay': {'baselineParity': True, 'originalStatus': decision,
                                               'replayStatus': decision, 'replayAccepted': False,
                                               'replayWinnerBlock': block},
                              'cropExperiments': [{'physicalBlockIndex': block,
                                                   'candidateRankInBlock': 1,
                                                   'candidateOrigin': 'ocr_token_band',
                                                   'selectiveTrimEligible': False,
                                                   'selectiveTrimQualifies': False,
                                                   'variants': [{'variant': 'original', 'adjustedScore': .43,
                                                                 'rawSeparation': .03},
                                                                {'variant': 'trim_12', 'adjustedScore': .44,
                                                                 'rawSeparation': .04}]}]})
        self.truth = {'schemaVersion': 1,
                      'shifts': [{'weekday': d, 'physicalBlock': 0 if d == 0 else 2}
                                 for d in (0, 1, 2, 3, 5, 6)], 'offWeekdays': [4]}

    def write(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value), encoding='utf-8')
        return path

    def manifest(self, repeat=False):
        self.write('truth.json', self.truth)
        scans = []
        for n in range(2 if repeat else 1):
            name = f'scan{n}.json'
            self.write(name, {'schemaVersion': 15, 'scanStage': 'COMPLETE',
                              'scanInProgress': False, 'sessionId': f's{n}',
                              'ocrGeometryFingerprint': 'same',
                              'viewerMarkers': {'recognitionRunId': f'r{n}',
                                                'savedProfileDecisions': self.rows}})
            scans.append(name)
        return self.write('manifest.json', {'schemaVersion': 1, 'cases': [
            {'id': 'synthetic-photo', 'groundTruth': 'truth.json', 'scans': scans}]})

    def test_only_missed_working_days_in_normal_audit(self):
        audit = m.candidate_audit(m.evaluate(self.manifest()))
        self.assertIn('Wednesday — MISSED WORKING DAY', audit)
        self.assertIn('Saturday — MISSED WORKING DAY', audit)
        self.assertNotIn('Friday — MISSED WORKING DAY', audit)
        self.assertIn('ocr_token_band', audit)
        self.assertIn('0.430 / 0.030', audit)
        self.assertIn('0.440 / 0.040', audit)

    def test_false_off_and_hypothetical_safety(self):
        self.rows[4]['decision'] = 'accepted_normal'
        self.rows[4]['shadowReplay']['originalStatus'] = 'accepted_normal'
        self.rows[4]['shadowReplay']['replayStatus'] = 'accepted_normal'
        self.rows[4]['shadowReplay']['replayAccepted'] = True
        audit = m.candidate_audit(m.evaluate(self.manifest()))
        self.assertIn('Friday — FALSE OFF-DAY SUGGESTION', audit)
        self.assertIn('SAFETY FLAG', audit)

    def test_replay_wrong_block_not_counted_as_recovery(self):
        self.rows[2]['shadowReplay'].update(replayStatus='accepted_normal', replayAccepted=True,
                                              replayWinnerBlock=0)
        audit = m.candidate_audit(m.evaluate(self.manifest()))
        self.assertIn('NOT a recovery', audit)

    def test_replay_correct_block_is_still_hypothetical(self):
        self.rows[2]['shadowReplay'].update(replayStatus='accepted_normal', replayAccepted=True,
                                              replayWinnerBlock=2)
        audit = m.candidate_audit(m.evaluate(self.manifest()))
        self.assertIn('correct block, still not a production result', audit)

    def test_repeated_fingerprint_warning(self):
        audit = m.candidate_audit(m.evaluate(self.manifest(repeat=True)))
        self.assertIn('repeats within this case', audit)

    def test_output_path_collision_rejected(self):
        manifest = self.manifest()
        output = self.root / 'one.md'
        self.assertEqual(m.main(['--manifest', str(manifest), '--output', str(output),
                                  '--candidate-audit', str(output)]), 1)
        self.assertFalse(output.exists())

    def test_writes_both_reports(self):
        manifest = self.manifest()
        summary, audit = self.root / 'summary.md', self.root / 'audit.md'
        self.assertEqual(m.main(['--manifest', str(manifest), '--output', str(summary),
                                  '--candidate-audit', str(audit)]), 0)
        self.assertIn('labelled diagnostic evaluation', summary.read_text())
        self.assertIn('labelled missed-candidate audit', audit.read_text())


if __name__ == '__main__':
    unittest.main()
