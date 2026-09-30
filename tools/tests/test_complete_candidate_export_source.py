"""Static Android-path checks when Android SDK build isn't available on this host."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/com/example/workshifttracker'


class CompleteCandidateExportSourceTests(unittest.TestCase):
    def test_schema_and_version_bump_and_both_export_paths(self):
        planner = (SOURCE / 'PlannerActivity.kt').read_text(encoding='utf-8')
        gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('put("schemaVersion", 16)', planner)
        self.assertEqual(1, planner.count('put("completeProductionCandidates", JSONArray().apply'))
        self.assertIn('versionCode = 239', gradle)
        self.assertIn('versionName = "0.9.0"', gradle)
        self.assertIn('InstalledAppVersionLabel()', planner)

    def test_full_candidates_derive_from_production_scored_ranking_not_shadow(self):
        vision = (SOURCE / 'OfflineRotaVision.kt').read_text(encoding='utf-8')
        self.assertIn('completeCandidateEvidence(ranked.map(::evidenceRow))', vision)
        self.assertNotIn('completeCandidateEvidence(shadows', vision)
        self.assertEqual(2, vision.count('completeProductionCandidates = completeProductionCandidates'))
        evidence = (SOURCE / 'RotaDiagnosticEvidence.kt').read_text(encoding='utf-8')
        self.assertIn('ordered.mapIndexed { index, row -> row.copy(rank = index + 1) }', evidence)
        self.assertIn('require(rank >= 1', evidence)

    def test_export_contains_no_private_ocr_text_or_precise_positions(self):
        planner = (SOURCE / 'PlannerActivity.kt').read_text(encoding='utf-8')
        sections = planner.split('put("completeProductionCandidates", JSONArray().apply')[1:]
        self.assertEqual(1, len(sections))
        for section in sections:
            body = section.split('put("topCandidates", JSONArray().apply')[0]
            for forbidden in ('ocrText', 'imagePixels', 'put("top"', 'put("bottom"',
                              'put("left"', 'put("right"', 'fullName'):
                self.assertNotIn(forbidden, body)


if __name__ == '__main__':
    unittest.main()
