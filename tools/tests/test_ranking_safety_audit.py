"""Synthetic labels only; output is research evidence, never a production rule."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('ranking_safety', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def row(day, candidates, accepted=False):
    state = 'accepted_normal' if accepted else 'rejected_below_rescue_floor'
    return {'weekdayColumn': day, 'decision': state, 'acceptanceFloor': .55,
            'topCandidates': candidates, 'shadowReplay': {'originalStatus': state,
            'replayStatus': state, 'baselineParity': True,
            'replayWinnerBlock': candidates[0]['physicalBlockIndex'] if candidates else None}}


def candidate(block, score, separation=0.0):
    return {'physicalBlockIndex': block, 'adjustedScore': score,
            'rawSeparation': separation}


class RankingSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.truth = {0: 0, 1: 2, 2: 2, 3: 2, 4: None, 5: 2, 6: 2}
        self.rows = {d: row(d, [candidate(self.truth[d] if self.truth[d] is not None else 0, .7)],
                            accepted=self.truth[d] is not None) for d in range(7)}
        self.rows[2] = row(2, [candidate(2, .429, .031), candidate(0, .422, -.016)])
        self.rows[4] = row(4, [candidate(0, .43, -.01), candidate(2, .41, -.02)])
        self.rows[5] = row(5, [candidate(0, .443, -.025), candidate(2, .431, -.017)])

    def result(self):
        return [{'id': 'fixture', 'scans': [{'path': 'automatic.json',
                  'fingerprint': 'fp', 'rows': self.rows, 'truth': self.truth}]}]

    def test_all_seven_and_negative_control(self):
        report = m.ranking_safety_report(self.result())
        self.assertEqual(sum(report.count('| '+day+' |') for day in m.DAYS), 7)
        self.assertIn('Friday | OFF', report)
        self.assertIn('OFF correctly rejected; negative control', report)
        self.assertIn('Wednesday | B3 |', report)
        self.assertIn('rejected; correct block leads', report)
        self.assertIn('Saturday | B3 |', report)
        self.assertIn('rejected; competing block leads', report)
        self.assertIn('0.007', report)

    def test_false_off_and_wrong_block_are_flagged(self):
        self.rows[4]['decision'] = 'accepted_normal'
        self.rows[0] = row(0, [candidate(2, .80)], accepted=True)
        report = m.ranking_safety_report(self.result())
        self.assertIn('OFF FALSE SUGGESTION', report)
        self.assertIn('WRONG BLOCK ACCEPTED', report)

    def test_missing_candidates_cannot_be_interpreted_as_absence(self):
        self.rows[2]['topCandidates'] = []
        report = m.ranking_safety_report(self.result())
        self.assertIn('no exported top-three candidate', report)
        self.assertIn('does not prove', report)

    def test_labelled_block_outside_exported_top_three(self):
        self.rows[2] = row(2, [candidate(0, .45), candidate(1, .43)])
        self.assertIn('labelled block absent from exported top three',
                      m.ranking_safety_report(self.result()))

    def test_repeated_fingerprint_warning(self):
        result = self.result()
        result[0]['scans'].append(dict(result[0]['scans'][0], path='other.json'))
        self.assertIn('repeated OCR fingerprint', m.ranking_safety_report(result))

    def test_cli_output_collision_fails_without_writing(self):
        truth = {'schemaVersion': 1, 'shifts': [
            {'weekday': d, 'physicalBlock': self.truth[d]} for d in range(7)
            if self.truth[d] is not None], 'offWeekdays': [4]}
        scan = {'schemaVersion': 15, 'scanInProgress': False, 'scanStage': 'COMPLETE',
                'sessionId': 's1', 'viewerMarkers': {'recognitionRunId': 'r1',
                'savedProfileDecisions': list(self.rows.values())}}
        for name, doc in [('truth.json', truth), ('scan.json', scan),
                          ('manifest.json', {'schemaVersion': 2, 'cases': [
                              {'id': 'fixture', 'groundTruth': 'truth.json',
                               'predictions': [{'path': 'scan.json', 'origin': 'automatic_export'}]}]})]:
            (self.root/name).write_text(json.dumps(doc), encoding='utf-8')
        report = self.root/'report.md'
        self.assertEqual(1, m.main(['--manifest', str(self.root/'manifest.json'),
                                   '--output', str(report), '--ranking-safety', str(report)]))
        self.assertFalse(report.exists())
        self.assertEqual(0, m.main(['--manifest', str(self.root/'manifest.json'),
                                   '--ranking-safety', str(report)]))
        self.assertIn('Friday | OFF', report.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
