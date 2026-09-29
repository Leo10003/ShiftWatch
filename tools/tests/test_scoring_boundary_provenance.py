"""Strict additive per-crop provenance and privacy-safe Android source assertions."""
from pathlib import Path
import importlib.util
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('boundary_audit', ROOT / 'tools/audit-scoring-boundary.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def crop(raw=.031, required=.07, branch='below_required_separation', adjustment=-.12):
    return {'rank': 1, 'physicalBlockIndex': 2, 'rawSeparation': raw,
            'requiredSeparation': required, 'separationBranch': branch,
            'separationAdjustment': adjustment, 'adjustedScore': .429,
            'positiveScore': .549, 'confuserScore': .518, 'confuserPenalty': 0.,
            'verticalDecile': 5, 'candidateOrigin': 'ocr_token_band'}


class ScoringBoundaryAuditTests(unittest.TestCase):
    def test_measured_penalty_and_bonus(self):
        self.assertEqual('below_required_separation', audit.validate_boundary_crop(crop(), 'x', 'Wednesday'))
        self.assertEqual('within_separation_band', audit.validate_boundary_crop(
            crop(raw=.09, branch='within_separation_band', adjustment=0.), 'x', 'Wednesday'))
        self.assertEqual('strong_separation_bonus', audit.validate_boundary_crop(
            crop(raw=.25, branch='strong_separation_bonus', adjustment=.025), 'x', 'Wednesday'))

    def test_no_confuser_and_old_export(self):
        self.assertEqual('no_confuser_profile', audit.validate_boundary_crop(
            crop(raw=0., required=None, branch='no_confuser_profile', adjustment=0.), 'x', 'Friday'))
        with self.assertRaisesRegex(ValueError, 'new Android'):
            legacy = crop(); legacy.pop('requiredSeparation')
            audit.validate_boundary_crop(legacy, 'x', 'Friday')

    def test_reject_wrong_branch_or_nonfinite_boundary(self):
        with self.assertRaisesRegex(ValueError, 'disagree'):
            audit.validate_boundary_crop(crop(adjustment=0.), 'x', 'Wednesday')
        with self.assertRaisesRegex(ValueError, 'conflicts'):
            audit.validate_boundary_crop(crop(raw=-.02, branch='strong_separation_bonus', adjustment=.025), 'x', 'Friday')
        with self.assertRaisesRegex(ValueError, 'invalid required'):
            audit.validate_boundary_crop(crop(required=float('nan')), 'x', 'Friday')

    def test_source_contains_true_boundary_not_reconstructed_maxima(self):
        vision = (ROOT / 'app/src/main/java/com/example/workshifttracker/OfflineRotaVision.kt').read_text(encoding='utf-8')
        planner = (ROOT / 'app/src/main/java/com/example/workshifttracker/PlannerActivity.kt').read_text(encoding='utf-8')
        self.assertIn('requiredSeparation = boundary.requiredSeparation', vision)
        self.assertIn('separationBranch = trace.branch', vision)
        self.assertIn('put("scoringBoundaryProvenanceVersion", 1)', planner)
        self.assertIn('put("requiredSeparation", candidate.requiredSeparation?.toDouble() ?: JSONObject.NULL)', planner)
        section = planner.split('put("completeProductionCandidates", JSONArray().apply')[1].split('put("topCandidates", JSONArray().apply')[0]
        for forbidden in ('ocrText', 'imagePixels', 'put("top"', 'put("bottom"', 'fullName'):
            self.assertNotIn(forbidden, section)

    def test_fresh_marker_required_for_every_day(self):
        class Eval:
            DAYS = ('Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday')
            @staticmethod
            def _validated_complete_candidates(row, source, day):
                return row['completeProductionCandidates']
        rows = {day: {'scoringBoundaryProvenanceVersion': 1,
                      'completeProductionCandidates': [crop()]} for day in range(7)}
        scan = {'path': 'fresh.json', 'rows': rows, 'truth': {d: None for d in range(7)}}
        text = audit.build_report([{'id': 'test', 'scans': [scan]}], Eval)
        self.assertIn('| Friday | OFF |', text)
        del rows[4]['scoringBoundaryProvenanceVersion']
        with self.assertRaisesRegex(ValueError, 'Friday'):
            audit.build_report([{'id': 'test', 'scans': [scan]}], Eval)

    def test_refuses_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'report.md'
            output.write_text('protected', encoding='utf8')
            self.assertEqual(2, audit.main(['--manifest', str(Path(directory) / 'manifest.json'),
                                            '--output', str(output)]))
            self.assertEqual('protected', output.read_text(encoding='utf8'))


if __name__ == '__main__':
    unittest.main()
