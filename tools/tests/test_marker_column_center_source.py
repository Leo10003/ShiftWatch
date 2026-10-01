import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerColumnCenterSourceTest(unittest.TestCase):
    def test_visual_marker_uses_detected_ink_center(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val detectedLeft = marker.sourceLeft?.let", planner)
        self.assertIn("val detectedRight = marker.sourceRight?.let", planner)
        self.assertIn("val visualCx = clampedLeft + highlightWidth / 2f", planner)
        self.assertIn("center = Offset(visualCx, cy)", planner)

    def test_mixed_and_handwritten_use_header_visual_geometry(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("DocumentKind.HANDWRITTEN_GRID ||", planner)
        self.assertIn("DocumentKind.MIXED", planner)
        self.assertIn("ScheduleImporter.headerColumnBounds(assistData, sourceColumn)", planner)

if __name__ == "__main__":
    unittest.main()
