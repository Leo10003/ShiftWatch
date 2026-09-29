"""Regression tests for the read-only scored-evidence discrimination report."""
import importlib.util
from pathlib import Path
import unittest


SOURCE = Path(__file__).parents[1] / 'audit-score-discrimination.py'
spec = importlib.util.spec_from_file_location('shiftwatch_discrimination', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def crop(block, rank, score, origin='ink_gap_probe', raw=.01):
    return {'physicalBlockIndex': block, 'rank': rank, 'adjustedScore': score,
            'positiveScore': min(1., score + .12), 'confuserScore': .50,
            'rawSeparation': raw, 'confuserPenalty': .12,
            'separationAdjustment': -.12, 'candidateOrigin': origin}


class FakeEvaluator:
    DAYS = ('Monday', 'Tuesday', 'Wednesday', 'Thursday',
            'Friday', 'Saturday', 'Sunday')

    class EvaluationError(Exception):
        pass

    @staticmethod
    def _validated_complete_candidates(row, source, day):
        return row['completeProductionCandidates']


class DiscriminationTests(unittest.TestCase):
    def test_extra_blocks_remain_counted_and_never_win(self):
        full = [crop(3, 1, .99), crop(2, 2, .43, 'ocr_token_band'),
                crop(0, 3, .44)]
        features, extras = m.block_features(full)
        self.assertEqual(1, extras)
        self.assertEqual([(0, .44), (2, .43)],
                         m._best(features, 'all', 'adjustedScore'))

    def test_different_crops_maximize_different_signals(self):
        a = crop(2, 1, .43, raw=.01)
        b = crop(2, 2, .40, raw=.21)
        features, _ = m.block_features([a, b])
        self.assertEqual(.43, features[('all', 2)]['maxima']['adjustedScore'])
        self.assertEqual(.21, features[('all', 2)]['maxima']['rawSeparation'])
        self.assertEqual(1, features[('all', 2)]['bestAdjustedRank'])

    def test_reference_annotations_do_not_change_fixed_features(self):
        data = [crop(2, 1, .429, 'ocr_token_band'), crop(0, 2, .422),
                crop(3, 3, .2)]
        rows = {day: {'decision': 'rejected_below_rescue_floor',
                      'completeProductionCandidates': data}
                for day in range(7)}
        reference = {day: 2 for day in range(7)}
        reference[4] = None
        case = {'id': 'fixture', 'scans': [{'path': 'fixture.json',
                'schemaVersion': 16, 'rows': rows, 'truth': reference}]}
        output = m.discrimination_report([case], FakeEvaluator)
        self.assertIn('| Friday | rejected_below_rescue_floor / OFF | 3 / 1 |', output)
        self.assertIn('B3 (label)', output)
        self.assertNotIn('B4 (label)', output)
        self.assertIn('not a calibrated threshold', output)
        # Change ground truth, not features or ranking.
        reference[4] = 0
        output2 = m.discrimination_report([case], FakeEvaluator)
        friday1 = next(line for line in output.splitlines() if line.startswith('| Friday |'))
        friday2 = next(line for line in output2.splitlines() if line.startswith('| Friday |'))
        self.assertEqual(friday1.split(' | ')[2:], friday2.split(' | ')[2:])

    def test_old_schema_is_rejected(self):
        case = {'id': 'fixture', 'scans': [{'path': 'old.json', 'schemaVersion': 15}]}
        with self.assertRaises(FakeEvaluator.EvaluationError):
            m.discrimination_report([case], FakeEvaluator)


if __name__ == '__main__':
    unittest.main()
