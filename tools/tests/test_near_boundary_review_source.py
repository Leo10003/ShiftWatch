"""Ensure review-only matching stays isolated from automatic acceptance and training."""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/com/example/workshifttracker'

class NearBoundaryReviewIntegrationTests(unittest.TestCase):
    def test_production_matches_stay_separate(self):
        vision = (SRC / 'OfflineRotaVision.kt').read_text(encoding='utf-8')
        self.assertIn('RotaNearBoundaryReview.select(', vision)
        self.assertIn('(0..6).map { decisions.getValue(it) }, reviewHints)', vision)
        self.assertIn('matches.sortedBy { it.column }', vision)
        self.assertIn('val decision = decisions[match.column] ?: return@mapNotNull null', vision)
        self.assertIn('decision.status !in setOf("accepted_normal", "accepted_near_floor")', vision)
        self.assertIn('confuserPenalty = best.confuserPenalty', vision)
        self.assertIn('val runnerCandidate = ranked.getOrNull(1)', vision)
        self.assertIn('val corroborator = runnerCandidate?.takeIf', vision)
        self.assertIn('corroboratingOcr = corroborator?.candidate?.ocrText != null', vision)
        self.assertIn('verticalDecile = RotaDiagnosticEvidence.verticalDecile(', vision)
        self.assertIn('val acceptedAnchorEvidence = matches.mapNotNull', vision)
        self.assertIn('acceptedAnchorDeciles', vision)
        self.assertNotIn('matches += reviewHints', vision)

        selector = (SRC / 'RotaNearBoundaryReview.kt').read_text(encoding='utf-8')
        self.assertIn('candidate.confuserPenalty <= 0.020f', selector)
        self.assertIn('candidate.rawSeparation > -0.030f', selector)
        self.assertIn('required - candidate.rawSeparation <= 0.065f', selector)
        self.assertIn('candidate.corroboratingOcr', selector)
        self.assertIn('candidate.corroboratingRawSeparation?.let { it > -0.010f } == true', selector)
        self.assertIn('adjacentStrongNearBoundary', selector)
        self.assertIn('candidate.runnerBlock == anchor', selector)
        self.assertIn('ambiguousDominantRunner', selector)
        self.assertIn('candidate.verticalDecile?.let { it in minAnchorDecile..maxAnchorDecile } == true', selector)

    def test_hints_have_distinct_unconfirmed_ui_origin(self):
        planner = (SRC / 'PlannerActivity.kt').read_text(encoding='utf-8')
        self.assertIn('profileReport?.reviewHints.orEmpty()', planner)
        self.assertIn('suggestionScore = null, origin = "near_boundary_review"', planner)
        self.assertIn('"check row?"', planner)
        self.assertIn('ComposeColor(0xFFFFB74D)', planner)

if __name__ == '__main__': unittest.main()
