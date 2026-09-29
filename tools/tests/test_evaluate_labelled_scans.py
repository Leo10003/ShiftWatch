"""Synthetic offline fixtures; no user data, OCR text or photos."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('labelled', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class TestEvaluation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.truth = {'schemaVersion': 1, 'shifts': [
            {'weekday': d, 'physicalBlock': 0 if d == 0 else 2}
            for d in (0, 1, 2, 3, 5, 6)], 'offWeekdays': [4]}
        self.scan = {'schemaVersion': 15, 'scanStage': 'COMPLETE', 'scanInProgress': False,
                     'sessionId': 'sessionA', 'viewerMarkers': {'recognitionRunId': 'runA',
                     'savedProfileDecisions': [self.day(d) for d in range(7)]}}

    def day(self, d):
        good = d in (0, 1, 2, 6)
        score = .72 if good else .43
        block = 0 if d == 0 else 2
        return {'weekdayColumn': d, 'decision': 'accepted_normal' if good else 'rejected_below_rescue_floor',
                'topCandidates': [{'physicalBlockIndex': block, 'adjustedScore': score}],
                'shadowReplay': {'originalStatus': 'accepted_normal' if good else 'rejected_below_rescue_floor',
                                 'baselineParity': True,
                                 'replayStatus': 'accepted_normal' if good or d == 5 else 'rejected_below_rescue_floor',
                                 'replayWinnerBlock': block}}

    def write(self, name, data):
        p = self.root / name
        p.write_text(json.dumps(data), encoding='utf-8')
        return p

    def manifest(self):
        self.write('truth.json', self.truth)
        self.write('scan.json', self.scan)
        return self.write('manifest.json', {'schemaVersion': 1, 'cases': [
            {'id': 'synthetic-one', 'groundTruth': 'truth.json', 'scans': ['scan.json']}]})

    def test_production_four_replay_five_and_friday_off(self):
        result = m.evaluate(self.manifest())
        scan = result[0]['scans'][0]
        self.assertEqual(scan['production']['hits'], 4)
        self.assertEqual(scan['replay']['hits'], 5)
        self.assertEqual(scan['production']['falseSuggestions'], 0)
        self.assertEqual(scan['replay']['falseSuggestions'], 0)
        self.assertIn('hypothetical', m.markdown(result).lower())

    def v2_manifest(self, predictions=None):
        self.write('truth.json', self.truth)
        self.write('scan.json', self.scan)
        if predictions is None:
            predictions = [{'path': 'scan.json', 'origin': 'automatic_export'}]
        return self.write('manifest.json', {'schemaVersion': 2, 'cases': [
            {'id': 'reference', 'groundTruth': 'truth.json', 'predictions': predictions}]})

    def test_explicit_automatic_provenance_counts_only_prediction(self):
        result = m.evaluate(self.v2_manifest())
        self.assertEqual(len(result[0]['scans']), 1)
        self.assertEqual(result[0]['scans'][0]['origin'], 'automatic_export')
        self.assertEqual(result[0]['scans'][0]['production']['hits'], 4)
        self.assertNotIn('Legacy manifest', m.markdown(result))

    def test_manual_file_cannot_be_prediction(self):
        path = self.v2_manifest([{'path': 'scan.json', 'origin': 'manual_reference'}])
        with self.assertRaisesRegex(m.EvaluationError, 'manual'):
            m.evaluate(path)

    def test_unlabelled_prediction_is_rejected_in_v2(self):
        path = self.v2_manifest([{'path': 'scan.json'}])
        with self.assertRaisesRegex(m.EvaluationError, 'automatic_export'):
            m.evaluate(path)

    def test_legacy_manifest_warns_unverified_provenance(self):
        report = m.markdown(m.evaluate(self.manifest()))
        self.assertIn('Legacy manifest has no prediction provenance', report)

    def test_ground_truth_cannot_double_as_prediction(self):
        path = self.v2_manifest([{'path': 'truth.json', 'origin': 'automatic_export'}])
        with self.assertRaisesRegex(m.EvaluationError, 'different files'):
            m.evaluate(path)

    def test_concatenated_export_rejected(self):
        p = self.manifest()
        scan = self.root / 'scan.json'
        scan.write_text(scan.read_text() + '\n{"leftover": true}')
        with self.assertRaisesRegex(m.EvaluationError, 'additional text'):
            m.evaluate(p)

    def test_parity_failure_rejected(self):
        self.scan['viewerMarkers']['savedProfileDecisions'][5]['shadowReplay']['baselineParity'] = False
        with self.assertRaisesRegex(m.EvaluationError, 'parity failed'):
            m.evaluate(self.manifest())

    def test_incomplete_truth_rejected(self):
        self.truth['offWeekdays'] = []
        with self.assertRaisesRegex(m.EvaluationError, 'explicitly label'):
            m.evaluate(self.manifest())

    def test_duplicate_scan_rejected(self):
        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data['cases'][0]['scans'].append('scan.json')
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(m.EvaluationError, 'duplicate exports'):
            m.evaluate(manifest)

    def test_malformed_json_rejected(self):
        manifest = self.manifest()
        (self.root / 'scan.json').write_text('{bad')
        with self.assertRaisesRegex(m.EvaluationError, 'malformed'):
            m.evaluate(manifest)

    def test_off_replay_false_positive_is_reported(self):
        self.scan['viewerMarkers']['savedProfileDecisions'][4]['shadowReplay']['replayStatus'] = 'accepted_normal'
        result = m.evaluate(self.manifest())
        self.assertEqual(result[0]['scans'][0]['replay']['falseSuggestions'], 1)


if __name__ == '__main__':
    unittest.main()
