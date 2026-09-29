"""Per-crop review telemetry does not change ranking or use truth for selection."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('boundary_review', ROOT / 'tools/audit-boundary-review.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
spec2 = importlib.util.spec_from_file_location('measured', ROOT / 'tools/audit-scoring-boundary.py')
measured = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(measured)


def crop(rank=1, block=2, raw=.031, required=.041, origin='ocr_token_band'):
    return {'rank': rank, 'physicalBlockIndex': block, 'rawSeparation': raw,
            'requiredSeparation': required, 'separationBranch': 'below_required_separation',
            'separationAdjustment': -.12, 'candidateOrigin': origin}


class BoundaryReviewTests(unittest.TestCase):
    def test_same_crop_closest_and_outside(self):
        data = [crop(), crop(2, 0, .015, .05, 'ink_gap_probe'),
                crop(3, 3), crop(4, 1, -.024, .05)]
        before = repr(data)
        found = audit.summarize(data, measured, 'scan', 'Wednesday')
        self.assertEqual(2, found['count'])
        self.assertEqual(1, found['outside'])
        self.assertEqual(1, found['closest']['rank'])
        self.assertAlmostEqual(.01, found['shortfall'])
        self.assertEqual(before, repr(data))

    def test_off_like_negative_raw_is_not_candidate(self):
        found = audit.summarize([crop(raw=-.024, required=.052)], measured, 'scan', 'Friday')
        self.assertEqual(0, found['count'])
        self.assertIsNone(found['closest'])

    def test_existing_boundary_and_bonus_not_promoted(self):
        c = crop(raw=.07, required=.05)
        c['separationBranch'] = 'within_separation_band'
        c['separationAdjustment'] = 0.
        self.assertIsNone(audit.summarize([c], measured, 'scan', 'Sunday')['closest'])

    def test_telemetry_mismatch_is_rejected(self):
        calc = audit.summarize([crop()], measured, 'scan', 'Wednesday')
        valid = {'boundaryReviewTelemetry': {
            'positiveRawBelowBoundaryCount': 1, 'outsideLabelledBlocksCount': 0,
            'closestPositiveRawBelowBoundary': dict(crop(), shortfall=.010)}}
        self.assertEqual('verified new export',
                         audit.verify_exported_telemetry(valid, calc, 'scan', 'Wednesday'))
        valid['boundaryReviewTelemetry']['closestPositiveRawBelowBoundary']['rank'] = 2
        with self.assertRaisesRegex(ValueError, 'identity'):
            audit.verify_exported_telemetry(valid, calc, 'scan', 'Wednesday')

    def test_legacy_measured_export_still_supported(self):
        calc = audit.summarize([crop()], measured, 'scan', 'Wednesday')
        self.assertIn('legacy', audit.verify_exported_telemetry({}, calc, 'scan', 'Wednesday'))

    def test_independent_off_control_gate(self):
        class Eval:
            DAYS = ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']
        results = [{'id':'a','scans':[{'path':'scan.json', 'fingerprint':'same',
                                      'truth': {4:None}, 'rows': {}}]}]
        with self.assertRaisesRegex(ValueError, 'Independent-photo'):
            audit.build_report(results, Eval, measured, require_independent_controls=True)

    def test_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / 'existing.md'
            out.write_text('protected', encoding='utf-8')
            self.assertEqual(2, audit.main(['--manifest', str(Path(temp) / 'missing.json'),
                                           '--output', str(out)]))
            self.assertEqual('protected', out.read_text(encoding='utf-8'))

    def test_source_exposes_additive_review_only_field(self):
        text = (ROOT / 'app/src/main/java/com/example/workshifttracker/PlannerActivity.kt').read_text(encoding='utf-8')
        self.assertIn('put("boundaryReviewTelemetry", JSONObject().apply', text)
        self.assertIn('decision.completeProductionCandidates)', text)
        self.assertNotIn('boundaryTriage.closestPositiveRawBelowBoundary?.let { near ->\n                                            matches', text)


if __name__ == '__main__':
    unittest.main()
