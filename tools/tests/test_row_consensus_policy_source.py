import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class RowConsensusPolicySourceTest(unittest.TestCase):
    def test_row_consensus_is_review_only(self):
        policy = (ROOT / "app/src/main/java/com/example/workshifttracker/RotaRowConsensusPolicy.kt").read_text(encoding="utf-8")
        vision = (ROOT / "app/src/main/java/com/example/workshifttracker/OfflineRotaVision.kt").read_text(encoding="utf-8")
        self.assertIn("return Plan(emptySet(), review)", policy)
        self.assertNotIn('copy(status = "quarantined_row_outlier")', vision)
        self.assertIn("rowConsensusReviewHints", vision)

    def test_renderer_has_minimum_name_width_and_no_center_circle(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val minimumNameWidth = columnWidth * 0.46f", planner)
        self.assertIn("val safeInset = maxOf(5.dp.toPx(), columnWidth * 0.08f)", planner)
        self.assertNotIn("drawCircle(color = outline, radius = 6.dp.toPx()", planner)

if __name__ == "__main__":
    unittest.main()
