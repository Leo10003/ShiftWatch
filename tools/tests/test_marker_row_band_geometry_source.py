import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerRowBandGeometrySourceTest(unittest.TestCase):
    def test_match_keeps_detected_ink_band_with_candidate_fallback(self):
        vision = (ROOT / "app/src/main/java/com/example/workshifttracker/OfflineRotaVision.kt").read_text(encoding="utf-8")
        self.assertIn("winningSignature?.inkTop ?: best.candidate.band.top", vision)
        self.assertIn("winningSignature?.inkBottom ?: best.candidate.band.bottom", vision)
        self.assertIn("val inkTop: Int? = null", vision)
        self.assertIn("val inkBottom: Int? = null", vision)

    def test_viewer_centers_height_on_detected_row(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val detectedTop = marker.sourceTop?.let", planner)
        self.assertIn("val detectedBottom = marker.sourceBottom?.let", planner)
        self.assertIn("val rowTop = cy - highlightHeight / 2f", planner)

if __name__ == "__main__":
    unittest.main()
