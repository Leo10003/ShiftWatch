import importlib.util, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools/replay-labelled-corpus.py"

def tool():
    spec = importlib.util.spec_from_file_location("replay_labelled_corpus", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class ReplayRowGeometryTest(unittest.TestCase):
    def test_same_block_wrong_row_fails(self):
        m = tool()
        ok, note = m.row_status(
            {"block":2,"rowYPermille":735,"verticalDecile":7},
            {"block":2,"rowYPermille":700,"verticalDecile":7},
            18,
        )
        self.assertFalse(ok)
        self.assertIn("row delta=35", note)

    def test_close_row_passes(self):
        m = tool()
        ok, _ = m.row_status(
            {"block":2,"rowYPermille":712,"verticalDecile":7},
            {"block":2,"rowYPermille":700,"verticalDecile":7},
            18,
        )
        self.assertTrue(ok)

if __name__ == "__main__":
    unittest.main()
