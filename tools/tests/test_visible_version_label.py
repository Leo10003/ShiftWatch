import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class VisibleVersionLabelTests(unittest.TestCase):
    def test_release_metadata(self):
        text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(text, r'versionCode\s*=\s*255\b')
        self.assertRegex(text, r'versionName\s*=\s*"0\.9\.17"')

    def test_visible_label_uses_installed_package_version(self):
        planner = (ROOT / "app/src/main/java/com/example/workshifttracker/PlannerActivity.kt").read_text(encoding="utf-8")
        self.assertIn("InstalledAppVersionLabel", planner)
        self.assertIn("packageManager.getPackageInfo", planner)

if __name__ == "__main__":
    unittest.main()
