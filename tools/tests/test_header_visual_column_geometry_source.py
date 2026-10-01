import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class HeaderVisualColumnGeometrySourceTest(unittest.TestCase):
    def test_schedule_importer_exposes_header_based_bounds(self):
        source = (ROOT / "app/src/main/java/com/example/workshifttracker/ScheduleImporter.kt").read_text(encoding="utf-8")
        self.assertIn("fun headerColumnBounds(assist: AssistData, column: Int)", source)
        self.assertIn('t.startsWith("poned")', source)
        self.assertIn('t.startsWith("ned") -> 6', source)
        self.assertIn("val observed = assist.tokens.mapNotNull", source)

    def test_handwritten_renderer_uses_header_bounds(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("ScheduleImporter.headerColumnBounds(assistData, sourceColumn)", planner)
        self.assertNotIn("val uniformColumnWidth =", planner)

if __name__ == "__main__":
    unittest.main()
