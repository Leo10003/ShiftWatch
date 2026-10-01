import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class VerticalRuleRecognitionGeometrySourceTest(unittest.TestCase):
    def test_recognition_detector_is_restored_to_pre_0912_behavior(self):
        source = (ROOT / "app/src/main/java/com/example/workshifttracker/ScheduleImporter.kt").read_text(encoding="utf-8")
        start = source.index("private fun detectVerticalRules")
        end = source.index("private fun detectHorizontalRules", start)
        detector = source[start:end]
        self.assertIn("val expectedSpacing = bitmap.width / 7f", detector)
        self.assertIn("val minGap = bitmap.width * 0.085f", detector)
        self.assertNotIn("observedHeaderCenters", detector)
        self.assertNotIn("tableLeft", detector)

    def test_header_geometry_is_kept_visual_only(self):
        source = (ROOT / "app/src/main/java/com/example/workshifttracker/ScheduleImporter.kt").read_text(encoding="utf-8")
        self.assertIn("fun headerColumnBounds(assist: AssistData, column: Int)", source)
        self.assertIn("val observed = assist.tokens.mapNotNull", source)

if __name__ == "__main__":
    unittest.main()
