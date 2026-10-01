import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerGeometrySourceTest(unittest.TestCase):
    def test_marker_geometry_is_clipped_to_owning_column(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val minLeft = columnLeft + horizontalInset", planner)
        self.assertIn("val maxRight = columnRight - horizontalInset", planner)
        self.assertIn("coerceAtLeast(minLeft)", planner)
        self.assertIn("coerceAtMost(maxRight)", planner)
        self.assertIn("val highlightWidth = (inkRight - clampedLeft).coerceAtLeast(1f)", planner)

    def test_marker_preserves_recognition_geometry(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val sourceLeft: Float? = null", planner)
        self.assertIn("val sourceRight: Float? = null", planner)
        self.assertIn("sourceLeft = match.sourceLeft, sourceRight = match.sourceRight", planner)

if __name__ == "__main__":
    unittest.main()
