#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
DETAIL = (ROOT / 'app/src/main/java/vip/thatiam/mml/LogDetail.java').read_text()

class LogDetailPolicyTest(unittest.TestCase):
    def test_only_custom_and_full(self):
        self.assertIn('CUSTOM = 0', DETAIL)
        self.assertIn('FULL = 1', DETAIL)
        self.assertIn('DEFAULT_CUSTOM = SHOW_NOTIFICATIONS | SHOW_CALLS | SHOW_SMS', DETAIL)

    def test_filter_is_query_level(self):
        self.assertIn('LogDetail.sqlPredicate(detailLevel, customMask)', JOURNAL)
        self.assertIn('journal.homeWindow(', MAIN)
        self.assertIn('logDetailLevel, customLogMask, cancellation', MAIN)

    def test_user_entries_and_warning_are_always_visible_in_custom(self):
        self.assertIn("kind='SYSTEM' AND type='home_reminder'", DETAIL)
        self.assertIn("kind='USER'", DETAIL)
        self.assertNotIn('toggle(content, "Session reminder"', MAIN)

    def test_settings_controls_detail_not_date_header(self):
        self.assertIn('"Log detail: " + LogDetail.label(logDetailLevel)', MAIN)
        self.assertIn('new String[] {"Custom", "Full"}', MAIN)
        self.assertNotIn('cycleLogDetail', MAIN)
        home = MAIN[MAIN.index('private void home()'):MAIN.index('private void apps()')]
        self.assertNotIn('setOnLongClickListener', home)
        self.assertIn('header.setOnClickListener(v -> addBloodguiltWarning())', home)

    def test_bloodguilt_web_action_requires_confirmation(self):
        block = MAIN[MAIN.index('private void confirmBloodguiltWeb()'):MAIN.index('private void openWeb(String url)')]
        self.assertIn('.setMessage("Goes to internet. Continue?")', block)
        self.assertIn('.setPositiveButton(', block)
        self.assertIn('"Continue",', block)
        self.assertIn('.setNegativeButton("Cancel", null)', block)
        self.assertIn('openWeb("https://www.bloodguiltcurse.net/")', block)
        detail = MAIN[MAIN.index('if ("home_reminder".equals(entry.type))'):MAIN.index('} else if ("USER".equals(entry.kind)')]
        self.assertIn('this::confirmBloodguiltWeb', detail)
        self.assertNotIn('openWeb(', detail)

    def test_custom_screen_has_spaced_cancel_save(self):
        block = MAIN[MAIN.index('private void chooseCustomLogDetail()'):MAIN.index('private void settings()')]
        self.assertIn('ui.button("Cancel"', block)
        self.assertIn('"Save",', block)
        self.assertIn('new LinearLayout.LayoutParams(0, 1, 1)', block)

if __name__ == '__main__':
    unittest.main()
