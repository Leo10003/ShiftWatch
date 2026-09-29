"""No candidate-level score mixing, no relabelled B4, no truth-dependent ranking."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


SOURCE = Path(__file__).parents[1] / 'audit-same-crop-evidence.py'
spec = importlib.util.spec_from_file_location('same_crop_evidence', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def crop(block, rank, adjusted, raw, origin='ocr_token_band'):
    return {'physicalBlockIndex': block, 'rank': rank,
            'adjustedScore': adjusted, 'rawSeparation': raw,
            'positiveScore': .55, 'confuserScore': .57,
            'confuserPenalty': 0., 'separationAdjustment': -.12,
            'candidateOrigin': origin}


class Stub:
    DAYS = ('Monday', 'Tuesday', 'Wednesday', 'Thursday',
            'Friday', 'Saturday', 'Sunday')

    class EvaluationError(Exception):
        pass

    @staticmethod
    def _validated_complete_candidates(row, _source, _day):
        return row['completeProductionCandidates']


class Tests(unittest.TestCase):
    def test_adjusted_and_raw_must_be_actual_different_crops(self):
        crops = [crop(2, 1, .429, -.019), crop(2, 2, .404, .031),
                 crop(0, 3, .422, -.016)]
        blocks, extras = m.select_representatives(crops)
        self.assertEqual({}, extras)
        self.assertEqual(1, blocks[2]['adjusted']['rank'])
        self.assertEqual(-.019, blocks[2]['adjusted']['rawSeparation'])
        self.assertEqual(2, blocks[2]['raw']['rank'])
        self.assertEqual(.404, blocks[2]['raw']['adjustedScore'])

    def test_ties_stable_and_extra_regions_preserved(self):
        crops = [crop(3, 1, .99, .99), crop(None, 2, .99, .99),
                 crop(0, 4, .5, .1), crop(0, 3, .5, .1)]
        blocks, extras = m.select_representatives(crops)
        self.assertEqual({'B4': 1, 'null': 1}, extras)
        self.assertEqual(3, blocks[0]['adjusted']['rank'])
        self.assertEqual(3, blocks[0]['raw']['rank'])

    def test_report_preserves_off_and_is_truth_independent(self):
        crops = [crop(2, 1, .43, -.017), crop(0, 2, .42, -.016), crop(3, 3, .9, .9)]
        rows = {d: {'decision': 'rejected_below_rescue_floor',
                    'completeProductionCandidates': crops} for d in range(7)}
        truth = {d: 2 for d in range(7)}
        truth[4] = None
        scan = {'path': 'automatic.json', 'schemaVersion': 16,
                'rows': rows, 'truth': truth}
        first = m.report([{'id': 'fixture', 'scans': [scan]}], Stub)
        self.assertIn('Friday | rejected_below_rescue_floor / OFF', first)
        self.assertIn('B4:1', first)
        self.assertNotIn('B4 (reference)', first)
        truth[4] = 0
        second = m.report([{'id': 'fixture', 'scans': [scan]}], Stub)
        day1 = next(line for line in first.splitlines() if line.startswith('| Friday |'))
        day2 = next(line for line in second.splitlines() if line.startswith('| Friday |'))
        self.assertEqual(day1.split(' | ')[2:], day2.split(' | ')[2:])

    def test_old_schema_rejected(self):
        with self.assertRaises(Stub.EvaluationError):
            m.report([{'id': 'fixture', 'scans': [
                {'path': 'old.json', 'schemaVersion': 15}]}], Stub)

    def test_refuses_existing_output_without_touching_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'existing.md'
            target.write_text('precious', encoding='utf-8')
            self.assertEqual(2, m.main(['--manifest', str(Path(tmp)/'manifest.json'),
                                        '--output', str(target)]))
            self.assertEqual('precious', target.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
