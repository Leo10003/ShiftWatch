import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class AnchoredStrongNearTieSourceTest(unittest.TestCase):
    def test_mixed_viewer_uses_header_geometry(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("DocumentKind.HANDWRITTEN_GRID ||", planner)
        self.assertIn("DocumentKind.MIXED", planner)
        self.assertIn("ScheduleImporter.headerColumnBounds(assistData, sourceColumn)", planner)

    def test_review_policy_has_anchored_strong_near_tie(self):
        review = (ROOT / "app/src/main/java/com/example/workshifttracker/RotaNearBoundaryReview.kt").read_text(encoding="utf-8")
        self.assertIn("val anchoredStrongNearTie =", review)
        self.assertIn("candidate.positiveScore >= 0.70f", review)
        self.assertIn("candidate.rawSeparation >= 0f", review)
        self.assertIn("required - candidate.rawSeparation <= 0.030f", review)
        self.assertIn("candidate.runnerBlock == anchor", review)
        self.assertIn("runnerOutsideAnchorEnvelope", review)
        self.assertIn("anchoredStrongNearTie ||", review)

if __name__ == "__main__":
    unittest.main()
