#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
HISTORY = (ROOT / 'app/src/main/java/vip/thatiam/mml/HistoryCollector.java').read_text()
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
APP = (ROOT / 'app/src/main/java/vip/thatiam/mml/MmlApplication.java').read_text()
DEVICE = (ROOT / 'app/src/main/java/vip/thatiam/mml/DeviceEventCapture.java').read_text()
HELP = (ROOT / 'app/src/main/java/vip/thatiam/mml/SettingsHelp.java').read_text()


class SettingsUsagePolicyTest(unittest.TestCase):
    def test_usage_capture_has_one_master_switch(self):
        settings = MAIN[MAIN.index('private void settings()'):MAIN.index('private void notificationAccessHelp()')]
        self.assertIn('"App activity history", "capture_usage"', settings)
        self.assertIn('Settings.ACTION_USAGE_ACCESS_SETTINGS', settings)
        self.assertNotIn('App activity detail', settings)
        for key in ['usage_apps', 'usage_screen', 'usage_interaction', 'usage_services', 'usage_device', 'usage_other']:
            self.assertNotIn(key, settings)
            self.assertNotIn(key, HISTORY)
        self.assertNotIn('usageTypeEnabled', HISTORY)

    def test_initial_usage_backfill_is_not_live_session(self):
        self.assertIn('boolean initialBackfill = saved == 0;', HISTORY)
        self.assertIn('entry.withSession("usage-history:" + entry.day)', HISTORY)
        self.assertIn('repairUsageBackfill2030(db)', JOURNAL)
        self.assertIn('captured_ms-time_ms>120000', JOURNAL)
        self.assertIn('repair_usage_backfill_2030', JOURNAL)

    def test_user_present_is_direct_and_independent(self):
        self.assertIn('DeviceEventCapture.register(this);', APP)
        self.assertIn('Intent.ACTION_USER_PRESENT', DEVICE)
        self.assertIn('"DEVICE", "Android", "USER_PRESENT"', DEVICE)
        self.assertNotIn('ACTION_SCREEN_ON', DEVICE)
        self.assertNotIn('ACTION_SCREEN_OFF', DEVICE)
        self.assertNotIn('capture_usage', DEVICE)

    def test_settings_scroll_survives_rerender(self):
        self.assertIn('private ScrollView settingsScroll;', MAIN)
        self.assertIn('rememberSettingsScroll();', MAIN)
        self.assertIn('settingsScrollY = settingsScroll.getScrollY();', MAIN)
        self.assertIn('scroll.scrollTo(0, settingsScrollY)', MAIN)
        toggle = MAIN[MAIN.index('private void toggle('):MAIN.index('private void diagnostics()')]
        self.assertIn('render();', toggle)

    def test_long_press_help_covers_settings_and_custom_detail(self):
        add = MAIN[MAIN.index('private void add(LinearLayout'):MAIN.index('private void toggle(')]
        self.assertIn('setOnLongClickListener', add)
        self.assertIn('showSettingHelp(label)', add)
        custom = MAIN[MAIN.index('private void chooseCustomLogDetail()'):MAIN.index('private void settings()')]
        self.assertIn('box.setOnLongClickListener', custom)
        self.assertIn('showSettingHelp(labels[index])', custom)
        self.assertIn('WindowManager.LayoutParams.MATCH_PARENT', MAIN)
        for label in [
            'Notifications', 'Notification removals', 'Calls', 'SMS', 'App resumed',
            'App paused / stopped', 'Screen / lock state', 'App interaction',
            'Foreground services', 'Device startup / shutdown', 'Other usage events',
            'MML / system diagnostics', 'Device records'
        ]:
            self.assertIn('case "' + label + '":', HELP)


if __name__ == '__main__':
    unittest.main()
