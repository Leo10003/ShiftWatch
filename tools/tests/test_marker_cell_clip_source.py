import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerCellClipSourceTest(unittest.TestCase):
    def test_highlight_is_strictly_clipped_inside_visual_cell(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val safeInset = maxOf(5.dp.toPx(), columnWidth * 0.08f)", planner)
        self.assertIn("val minLeft = columnLeft + safeInset", planner)
        self.assertIn("val maxRight = columnRight - safeInset", planner)
        self.assertIn("coerceAtLeast(minLeft)", planner)
        self.assertIn("coerceAtMost(maxRight)", planner)

    def test_highlight_uses_detected_ink_width_when_available(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val hasInkBounds =", planner)
        self.assertIn("val rawInkLeft = if (hasInkBounds)", planner)
        self.assertIn("val rawInkRight = if (hasInkBounds)", planner)
        self.assertIn("val minimumNameWidth = columnWidth * 0.46f", planner)

if __name__ == "__main__":
    unittest.main()
