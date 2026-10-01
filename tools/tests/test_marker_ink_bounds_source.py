import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerInkBoundsSourceTest(unittest.TestCase):
    def test_signature_keeps_runtime_ink_bounds(self):
        vision = (ROOT / "app/src/main/java/com/example/workshifttracker/OfflineRotaVision.kt").read_text(encoding="utf-8")
        self.assertIn("val inkLeft: Int? = null", vision)
        self.assertIn("inkLeft = minX", vision)
        self.assertIn("sourceLeft = winningSignature?.inkLeft", vision)

    def test_viewer_uses_ink_bounds(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val detectedLeft = marker.sourceLeft?.let", planner)
        self.assertIn("val detectedRight = marker.sourceRight?.let", planner)
        self.assertIn("val highlightWidth = (inkRight - clampedLeft).coerceAtLeast(1f)", planner)

    def test_0912_detector_regression_is_reverted(self):
        source = (ROOT / "app/src/main/java/com/example/workshifttracker/ScheduleImporter.kt").read_text(encoding="utf-8")
        self.assertIn("val expectedSpacing = bitmap.width / 7f", source)

if __name__ == "__main__":
    unittest.main()
