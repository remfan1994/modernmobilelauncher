#!/usr/bin/env python3
"""Source-level guard for MML's fixed two-gray palette contract."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/vip/thatiam/mml/Ui.java"
MAIN = ROOT / "app/src/main/java/vip/thatiam/mml/MainActivity.java"
STYLES = ROOT / "app/src/main/res/values/styles.xml"
PRODUCTION = ROOT / "app/src/main"


class PalettePolicyTest(unittest.TestCase):
    def test_no_black_or_white_ui_literal(self):
        patterns = [
            re.compile(r"#(?:000|000000|fff|ffffff)\b", re.I),
            re.compile(r"0x(?:ff)?(?:000000|ffffff)\b", re.I),
            re.compile(r"\bColor\.(?:BLACK|WHITE)\b"),
            re.compile(r"\b(?:BLACK|WHITE)\s*="),
        ]
        offenders = []
        for path in PRODUCTION.rglob("*"):
            if path.suffix not in {".java", ".xml"} or not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            for pattern in patterns:
                if pattern.search(text):
                    offenders.append(f"{path.relative_to(ROOT)}: {pattern.pattern}")
        self.assertEqual([], offenders)

    def test_only_two_named_palette_values(self):
        text = UI.read_text(encoding="utf-8")
        self.assertIn("DARK_GRAY = 0xff2b2b2b", text)
        self.assertIn("LIGHT_GRAY = 0xffaaaaaa", text)
        self.assertNotIn("BLACK", text)
        self.assertNotIn("WHITE", text)

    def test_fixed_normal_and_reverse_video_pairings(self):
        text = UI.read_text(encoding="utf-8")
        expected = [
            "final int background = DARK_GRAY;",
            "final int foreground = LIGHT_GRAY;",
            "final int inset = DARK_GRAY;",
            "final int insetForeground = LIGHT_GRAY;",
            "final int selectedBackground = LIGHT_GRAY;",
            "final int selectedForeground = DARK_GRAY;",
        ]
        for line in expected:
            self.assertIn(line, text)
        self.assertIn("fill(LIGHT_GRAY, DARK_GRAY, true)", text)

    def test_theme_is_not_user_switchable(self):
        main = MAIN.read_text(encoding="utf-8")
        self.assertNotIn('toggle(content, "Gray background"', main)
        self.assertNotIn('getBoolean("light"', main)
        self.assertIn("R.style.MmlDialog", main)

    def test_theme_resources_only_use_the_two_grays(self):
        text = STYLES.read_text(encoding="utf-8")
        colors = {c.lower() for c in re.findall(r"#[0-9a-fA-F]{6}", text)}
        self.assertEqual({"#2b2b2b", "#aaaaaa"}, colors)


if __name__ == "__main__":
    unittest.main()
