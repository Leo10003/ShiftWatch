"""Full-block coverage diagnostics never represent partial exports as full rescoring."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / 'evaluate-labelled-scans.py'
spec = importlib.util.spec_from_file_location('coverage_evaluator', SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def block(index, score, origin='ink_gap_probe', count=2):
    return {'physicalBlockIndex': index, 'candidateCount': count,
            'best': {'adjustedScore': score, 'positiveScore': score+.12,
                     'rawSeparation': score-.5, 'candidateOrigin': origin}}


def row(day):
    top = [{'physicalBlockIndex': 0, 'adjustedScore': .44},
           {'physicalBlockIndex': 2, 'adjustedScore': .43},
           {'physicalBlockIndex': 2, 'adjustedScore': .42}]
    return {'weekdayColumn': day, 'decision': 'rejected_below_rescue_floor',
            'topCandidates': top,
            'productionByBlock': [block(0, .44), block(1, .39), block(2, .43, 'ocr_token_band')],
            'shadowByBlock': [block(0, .37, 'ocr_token_band'), block(2, .43, 'ocr_token_band')],
            'shadowReplay': {'baselineParity': True, 'originalStatus': 'rejected_below_rescue_floor',
                             'replayStatus': 'rejected_below_rescue_floor'}}


class BlockCoverageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.truth = {0: 0, 1: 2, 2: 2, 3: 2, 4: None, 5: 2, 6: 2}
        self.rows = {d: row(d) for d in range(7)}

    def result(self):
        return [{'id': 'reference', 'scans': [{'path': 'automatic.json',
                 'fingerprint': 'same-photo', 'truth': self.truth, 'rows': self.rows}]}]

    def test_all_days_off_control_missing_block_visible(self):
        text = m.block_coverage_report(self.result())
        self.assertEqual(7, sum(text.count('| ' + d + ' |') for d in m.DAYS))
        friday = next(x for x in text.splitlines() if x.startswith('| Friday |'))
        self.assertIn('OFF / rejected_below_rescue_floor', friday)
        self.assertIn('B2=0.390', friday)
        self.assertIn('| B2 |', friday)
        self.assertIn('separate shadow-OCR', text)

    def test_full_block_is_adjusted_winner_not_maximum_separation(self):
        self.assertIn('NOT the maximum positive or maximum separation',
                      m.block_coverage_report(self.result()))
        self.assertEqual(.43, m._block_summary(self.rows[0]['productionByBlock'])[2]['adjusted'])

    def test_sunday_probe_best_and_shadow_ocr_not_conflated(self):
        self.rows[6]['productionByBlock'][2] = block(2, .699, 'ink_gap_probe')
        self.rows[6]['shadowByBlock'] = [block(0, .442, 'ocr_token_band')]
        text = m.block_coverage_report(self.result())
        sunday = next(x for x in text.splitlines() if x.startswith('| Sunday |'))
        self.assertIn('B3=0.699 (ink_gap_probe)', sunday)
        self.assertIn('B1=0.442', sunday)
        self.assertNotIn('B3=0.699 |', sunday)

    def test_sparse_invalid_and_out_of_range_blocks_not_invented(self):
        self.rows[0]['productionByBlock'] = [block(3, .9), block(2, float('nan'))]
        monday = next(x for x in m.block_coverage_report(self.result()).splitlines()
                      if x.startswith('| Monday |'))
        self.assertIn('no valid block summaries', monday)
        self.assertIn('none |', monday)
        self.rows[0].pop('productionByBlock')
        monday = next(x for x in m.block_coverage_report(self.result()).splitlines()
                      if x.startswith('| Monday |'))
        self.assertIn('not exported', monday)
        self.assertIn('not observable', monday)

    def test_repeated_fingerprint_warning(self):
        data = self.result()
        data[0]['scans'].append(dict(data[0]['scans'][0], path='repeat.json'))
        self.assertIn('not independent photographic evidence', m.block_coverage_report(data))

    def test_new_cli_output_collision_precedes_writes(self):
        truth = {'schemaVersion': 1, 'shifts': [
            {'weekday': d, 'physicalBlock': b} for d, b in self.truth.items()
            if b is not None], 'offWeekdays': [4]}
        scan = {'schemaVersion': 15, 'scanInProgress': False, 'scanStage': 'COMPLETE',
                'sessionId': 's', 'viewerMarkers': {'recognitionRunId': 'r',
                'savedProfileDecisions': list(self.rows.values())}}
        manifest = {'schemaVersion': 2, 'cases': [{'id': 'ref', 'groundTruth': 'truth.json',
                    'predictions': [{'path': 'scan.json', 'origin': 'automatic_export'}]}]}
        for name, value in [('truth.json', truth), ('scan.json', scan), ('manifest.json', manifest)]:
            (self.root/name).write_text(json.dumps(value), encoding='utf-8')
        destination = self.root/'coverage.md'
        self.assertEqual(1, m.main(['--manifest', str(self.root/'manifest.json'),
                                    '--output', str(destination),
                                    '--block-coverage', str(destination)]))
        self.assertFalse(destination.exists())
        self.assertEqual(0, m.main(['--manifest', str(self.root/'manifest.json'),
                                    '--block-coverage', str(destination)]))
        self.assertIn('Friday | OFF', destination.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
