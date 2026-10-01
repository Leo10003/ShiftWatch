import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class LabelledTruthRowGeometrySourceTest(unittest.TestCase):
    def test_labeled_export_contains_vertical_decile(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn('put("schemaVersion", 2)', planner)
        self.assertIn('put("verticalDecile", confirmedMarker?.verticalDecile ?: JSONObject.NULL)', planner)

    def test_corpus_runner_exists(self):
        self.assertTrue((ROOT / "tools/replay-labelled-corpus.py").exists())

if __name__ == "__main__":
    unittest.main()
