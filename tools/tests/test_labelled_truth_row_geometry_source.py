import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class LabelledTruthRowGeometrySourceTest(unittest.TestCase):
    def test_marker_detail_contains_row_permille(self):
        evidence = (ROOT / "app/src/main/java/com/example/workshifttracker/RotaDiagnosticEvidence.kt").read_text(encoding="utf-8")
        self.assertIn("val rowYPermille: Int? = null", evidence)

    def test_planner_exports_row_permille(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("rowYPermille = ((marker.y /", planner)
        self.assertIn('put("rowYPermille", marker.rowYPermille', planner)
        self.assertIn('put("schemaVersion", 3)', planner)
        self.assertIn('put("rowYPermille", confirmedMarker?.rowYPermille', planner)

if __name__ == "__main__":
    unittest.main()
