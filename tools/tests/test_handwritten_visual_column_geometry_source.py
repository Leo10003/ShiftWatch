import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class HandwrittenVisualColumnGeometrySourceTest(unittest.TestCase):
    def test_handwritten_and_mixed_renderer_use_header_aligned_cells(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("assistData.documentKind == ScheduleImporter.DocumentKind.HANDWRITTEN_GRID ||", planner)
        self.assertIn("assistData.documentKind == ScheduleImporter.DocumentKind.MIXED", planner)
        self.assertIn("ScheduleImporter.headerColumnBounds(assistData, sourceColumn)", planner)

    def test_recognition_column_geometry_remains_separate(self):
        schedule = (ROOT / "app/src/main/java/com/example/workshifttracker/ScheduleImporter.kt").read_text(encoding="utf-8")
        self.assertIn("fun headerColumnBounds(assist: AssistData, column: Int)", schedule)
        self.assertIn("fun columnBounds(assist: AssistData, column: Int)", schedule)

if __name__ == "__main__":
    unittest.main()
