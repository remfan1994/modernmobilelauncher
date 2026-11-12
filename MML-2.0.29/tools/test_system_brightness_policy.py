#!/usr/bin/env python3
"""Source-level guard that Log brightness controls Android system brightness."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vip/thatiam/mml/MainActivity.java").read_text()
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()


class SystemBrightnessPolicyTest(unittest.TestCase):
    def test_write_settings_special_access_is_declared(self):
        self.assertIn('android.permission.WRITE_SETTINGS', MANIFEST)
        self.assertIn('Settings.ACTION_MANAGE_WRITE_SETTINGS', MAIN)
        self.assertIn('Settings.System.canWrite(this)', MAIN)

    def test_log_gesture_writes_system_brightness(self):
        self.assertIn('Settings.System.SCREEN_BRIGHTNESS, raw', MAIN)
        self.assertIn('Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL', MAIN)
        self.assertIn('setSystemBrightnessPercent(percent)', MAIN)

    def test_window_never_owns_brightness(self):
        self.assertIn('WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE', MAIN)
        self.assertNotIn('attributes.screenBrightness = value', MAIN)
        self.assertNotIn('putInt(BRIGHTNESS_PREF', MAIN)
        self.assertNotIn('MML brightness', MAIN)

    def test_old_window_override_is_migrated_away(self):
        self.assertIn('remove("log_brightness_percent")', MAIN)

    def test_adaptive_mode_can_be_restored_explicitly(self):
        self.assertIn('"Adaptive brightness"', MAIN)
        self.assertIn('Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC', MAIN)


if __name__ == "__main__":
    unittest.main()
