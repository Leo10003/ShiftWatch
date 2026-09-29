"""Adjustment provenance must use real crops, remain label-blind and preserve OFF."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).parents[1] / 'audit-adjustment-provenance.py'
spec = importlib.util.spec_from_file_location('adjustment_provenance', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def crop(block, rank, pos, conf, delta, origin='ocr_token_band'):
    raw = round(pos - conf, 6)
    return {'rank': rank, 'physicalBlockIndex': block, 'positiveScore': pos,
            'confuserScore': conf, 'rawSeparation': raw,
            'separationAdjustment': delta, 'adjustedScore': round(pos + delta, 6),
            'confuserPenalty': 0., 'candidateOrigin': origin}


class Evaluator:
    DAYS = ('Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday')

    class EvaluationError(Exception):
        pass

    @staticmethod
    def _validated_complete_candidates(row, _source, _day):
        return row['completeProductionCandidates']


class Tests(unittest.TestCase):
    def test_same_crop_classification_and_extra_region(self):
        crops = [crop(2, 1, .549, .518, -.120),
                 crop(0, 2, .563, .588, -.120, 'ink_gap_probe'),
                 crop(3, 3, .9, .1, 0)]
        f = m.features(crops)
        self.assertEqual(1, f['extras'])
        self.assertEqual(1, f['counts']['positive_raw_negative_adjustment'])
        self.assertEqual(1, f['conflicts'][0]['rank'])
        self.assertEqual(2, f['blocks'][2]['adjusted']['physicalBlockIndex'])

    def test_stable_tie_and_unmapped_null(self):
        crops = [crop(None, 1, .7, .2, -.12),
                 crop(2, 3, .6, .5, -.12), crop(2, 2, .6, .5, -.12)]
        f = m.features(crops)
        self.assertEqual(1, f['extras'])
        self.assertEqual(2, f['conflicts'][0]['rank'])

    def test_truth_annotates_only_and_off_retained(self):
        rows = {day: {'decision': 'rejected_below_rescue_floor',
                      'completeProductionCandidates': [crop(2, 1, .549, .518, -.12)]}
                for day in range(7)}
        truth = {day: 2 for day in range(7)}
        truth[4] = None
        scan = {'path': 'scan.json', 'schemaVersion': 16, 'rows': rows, 'truth': truth}
        one = m.report([{'id': 'fixture', 'scans': [scan]}], Evaluator)
        self.assertIn('Friday | rejected_below_rescue_floor / OFF', one)
        truth[4] = 2
        two = m.report([{'id': 'fixture', 'scans': [scan]}], Evaluator)
        first = next(line for line in one.splitlines() if line.startswith('| Friday |'))
        second = next(line for line in two.splitlines() if line.startswith('| Friday |'))
        self.assertEqual(first.split(' | ')[2:], second.split(' | ')[2:])

    def test_reports_raw_identity_residual_without_rejecting(self):
        wrong = crop(2, 1, .549, .518, -.12)
        wrong['rawSeparation'] = .5
        rows = {day: {'decision': 'rejected_below_rescue_floor',
                      'completeProductionCandidates': [wrong]} for day in range(7)}
        scan = {'path': 'scan.json', 'schemaVersion': 16, 'rows': rows,
                'truth': {d: None for d in range(7)}}
        result = m.report([{'id': 'fixture', 'scans': [scan]}], Evaluator)
        monday = next(line for line in result.splitlines() if line.startswith('| Monday |'))
        self.assertTrue(monday.endswith('| 1 | 0 |'), monday)

    def test_reports_adjusted_identity_residual_without_rejecting(self):
        changed = crop(2, 1, .549, .518, -.120)
        changed['adjustedScore'] = .350
        rows = {day: {'decision': 'rejected_below_rescue_floor',
                      'completeProductionCandidates': [changed]} for day in range(7)}
        scan = {'path': 'scan.json', 'schemaVersion': 16, 'rows': rows,
                'truth': {d: None for d in range(7)}}
        result = m.report([{'id': 'fixture', 'scans': [scan]}], Evaluator)
        friday = next(line for line in result.splitlines() if line.startswith('| Friday |'))
        self.assertIn(' / OFF |', friday)
        self.assertTrue(friday.endswith('| 0 | 1 |'), friday)

    def test_old_schema_rejected(self):
        with self.assertRaises(Evaluator.EvaluationError):
            m.report([{'id': 'fixture', 'scans': [{'schemaVersion': 15, 'path': 'old.json'}]}],
                     Evaluator)

    def test_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'existing.md'
            output.write_text('keep', encoding='utf-8')
            self.assertEqual(2, m.main(['--manifest', str(Path(tmp) / 'manifest.json'),
                                        '--output', str(output)]))
            self.assertEqual('keep', output.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
