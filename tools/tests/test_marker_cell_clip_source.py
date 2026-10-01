import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerCellClipSourceTest(unittest.TestCase):
    def test_highlight_is_strictly_clipped_inside_visual_cell(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val horizontalInset = maxOf(4.dp.toPx(), columnWidth * 0.045f)", planner)
        self.assertIn("val minLeft = columnLeft + horizontalInset", planner)
        self.assertIn("val maxRight = columnRight - horizontalInset", planner)
        self.assertIn("coerceAtLeast(minLeft)", planner)
        self.assertIn("coerceAtMost(maxRight)", planner)

    def test_highlight_uses_detected_ink_width_when_available(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val hasInkBounds =", planner)
        self.assertIn("val inkLeft = if (hasInkBounds)", planner)
        self.assertIn("val inkRight = if (hasInkBounds)", planner)
        self.assertIn("val highlightWidth = (inkRight - clampedLeft).coerceAtLeast(1f)", planner)

if __name__ == "__main__":
    unittest.main()
