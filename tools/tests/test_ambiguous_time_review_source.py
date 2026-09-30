from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/com/example/workshifttracker"

class AmbiguousTimeReviewSourceTests(unittest.TestCase):
    def test_review_only_multiple_alternatives(self):
        importer = (SOURCE / "ScheduleImporter.kt").read_text(encoding="utf-8")
        self.assertIn("val ambiguous = structuralTimeModel", importer)
        self.assertIn("if (alternatives.size < 2) return emptyList()", importer)
        self.assertIn('"ambiguous block alternative"', importer)

    def test_review_heading(self):
        planner = (SOURCE / "PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn('"Row time candidates"', planner)

if __name__ == "__main__":
    unittest.main()
