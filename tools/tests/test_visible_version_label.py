"""Source-level guard for installed APK version visibility until Android CI builds."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/com/example/workshifttracker'

class VisibleVersionLabelTests(unittest.TestCase):
    def test_release_metadata(self):
        text = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertRegex(text, r'versionCode\s*=\s*231\b')
        self.assertRegex(text, r'versionName\s*=\s*"20\.8\.32"')

    def test_label_reads_installed_package(self):
        text = (SOURCE / 'InstalledAppVersionLabel.kt').read_text(encoding='utf-8')
        self.assertIn('getPackageInfo(context.packageName, 0).versionName', text)
        self.assertIn('text = "v$version"', text)
        self.assertNotIn('"v20.8.23"', text)

    def test_label_visible_on_each_requested_screen(self):
        main = (SOURCE / 'MainActivity.kt').read_text(encoding='utf-8')
        planner = (SOURCE / 'PlannerActivity.kt').read_text(encoding='utf-8')
        self.assertIn('InstalledAppVersionLabel()', main)
        self.assertGreaterEqual(planner.count('InstalledAppVersionLabel()'), 2)
        self.assertIn('"Select shifts"', planner)
        self.assertIn('"Review detected table"', planner)

if __name__ == '__main__':
    unittest.main()
