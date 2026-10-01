import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class MarkerAuthoritativeColumnSourceTest(unittest.TestCase):
    def test_tap_marker_can_preserve_authoritative_column(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val column: Int? = null", planner)
        self.assertIn("column = match.column", planner)
        self.assertIn("fun markerColumn(marker: TapMarker): Int", planner)

    def test_renderer_uses_preserved_column(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("val sourceColumn = markerColumn(marker)", planner)

if __name__ == "__main__":
    unittest.main()
