#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
UI = (ROOT / 'app/src/main/java/vip/thatiam/mml/Ui.java').read_text()

class LogUiPolicyTest(unittest.TestCase):
    def test_log_row_visual_order(self):
        block = MAIN[MAIN.index('private final class LogAdapter'):MAIN.index('private static boolean hasDetailAction')]
        self.assertLess(block.index('row.addView(heading);'), block.index('row.addView(time);'))
        self.assertLess(block.index('row.addView(time);'), block.index('row.addView(body);'))
        self.assertLess(block.index('row.addView(body);'), block.index('row.addView(detail);'))
        self.assertIn('e.source + " / " + e.type', block)
        self.assertIn('sans-serif-medium', block)
        self.assertIn('sans-serif-condensed', block)

    def test_detail_is_full_screen_not_dialog(self):
        block = MAIN[MAIN.index('private void detail()'):MAIN.index('private void bindDetailTap')]
        self.assertIn('renderDetail(detailGroup)', block)
        self.assertIn('root.addView(heading', block)
        self.assertIn('root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1))', block)
        self.assertIn('root.addView(actions, new LinearLayout.LayoutParams(-1, -2))', block)
        self.assertNotIn('AlertDialog', block)
        self.assertNotIn('dialog()', block)

    def test_detail_view_has_visual_hierarchy(self):
        block = MAIN[MAIN.index('private CharSequence formatGroupDisplay'):MAIN.index('private void deleteGroup')]
        self.assertIn('Math.max(14f, ui.size - 7f)', block)
        self.assertIn('sans-serif-condensed', block)
        self.assertIn('sans-serif-medium', block)
        self.assertIn('Math.max(14f, ui.size - 8f)', block)
        self.assertIn('notificationMetadata(field.label)', block)
        self.assertIn('simplifyNotificationText(fields, entry.title)', block)
        self.assertIn('"Expanded text"', block)
        self.assertIn('new DetailField("App", appSourceName(entry.source))', block)

    def test_action_rows_use_dark_checkered_border_without_pin_semantics(self):
        block = MAIN[MAIN.index('private final class LogAdapter'):MAIN.index('private static boolean hasDetailAction')]
        start = MAIN.index('private static boolean hasDetailAction')
        action = MAIN[start:MAIN.index('private void openEntry(LogGroups.Group', start)]
        self.assertIn('boolean actionRow = hasDetailAction(group);', block)
        self.assertIn('ui.bindListRow(row, false, actionRow)', block)
        self.assertIn('ui.styleMeta(time, actionRow)', block)
        self.assertIn('group.entries.size() != 1', action)
        self.assertIn('"home_reminder".equals(entry.type)', action)
        self.assertIn('"app_launch".equals(entry.type)', action)
        self.assertIn('NotificationActions.eligible(entry)', action)
        self.assertIn('!entry.notificationKeyHash.isEmpty()', action)
        self.assertIn('actionRowBackground', UI)
        self.assertIn('checker(background, divider, 2)', UI)
        self.assertIn('checker(background, divider, 3)', UI)
        self.assertIn('CheckeredBorderDrawable', UI)
        self.assertNotIn('reverseRowBackground', UI)
        self.assertNotIn('pin', (block + action).lower())

    def test_bloodguilt_detail_action_confirms_before_site(self):
        detail = MAIN[MAIN.index('private void renderDetail'):MAIN.index('private void bindDetailTap')]
        self.assertIn('"home_reminder".equals(entry.type)', detail)
        self.assertIn('this::confirmBloodguiltWeb', detail)
        self.assertNotIn('openWeb(', detail)
        self.assertIn('"Open bloodguiltcurse.net"', detail)
        confirm = MAIN[MAIN.index('private void confirmBloodguiltWeb'):MAIN.index('private void openWeb')]
        self.assertIn('.setMessage("Goes to internet. Continue?")', confirm)
        self.assertIn('"Continue",', confirm)
        self.assertIn('.setNegativeButton("Cancel", null)', confirm)
        self.assertIn('openWeb("https://www.bloodguiltcurse.net/")', confirm)
        helper = MAIN[MAIN.index('private void openWeb'):MAIN.index('private void open(Intent')]
        self.assertIn('Intent.ACTION_VIEW', helper)

    def test_detail_blank_viewport_shares_contextual_action(self):
        detail = MAIN[MAIN.index('private void renderDetail'):MAIN.index('private void bindDetailTap')]
        self.assertIn('scroll.setFillViewport(true)', detail)
        self.assertIn('new ScrollView.LayoutParams(', detail)
        self.assertIn('ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT', detail)
        self.assertIn('bindDetailTap(', detail)

    def test_grouped_rows_have_no_synthetic_notify(self):
        detail = MAIN[MAIN.index('private void renderDetail'):MAIN.index('private void bindDetailTap')]
        self.assertIn('group.entries.size() == 1 ? new Entry[] {group.first} : null', detail)
        self.assertNotIn('Entry.event("USER", "MML", "grouped_log"', detail)
        self.assertIn('? "Unnotify" : "Notify"', detail)

    def test_detail_has_compact_provenance_metadata(self):
        block = MAIN[MAIN.index('private DetailParts detailParts'):MAIN.index('private List<DetailField> parseNotificationFields')]
        self.assertIn('new DetailField("Provenance", provenance(entry))', block)
        self.assertIn('"Android notification access"', block)
        self.assertIn('"Android Usage history"', block)
        self.assertIn('"Android Share to MML"', block)
        self.assertIn('"MML app picker"', block)
        hierarchy = MAIN[MAIN.index('private void appendEntryDisplay'):MAIN.index('private void styleDetailRange')]
        self.assertIn('Math.max(14f, ui.size - 8f)', hierarchy)

    def test_group_delete_is_explicit_and_confirmed(self):
        detail = MAIN[MAIN.index('private void renderDetail'):MAIN.index('private void bindDetailTap')]
        self.assertIn('"Delete " + group.entries.size() + " entries"', detail)
        self.assertIn('confirmDeleteGroup(group)', detail)
        confirm = MAIN[MAIN.index('private void confirmDeleteGroup'):MAIN.index('private void deleteGroup')]
        self.assertIn('setPositiveButton("Delete"', confirm)
        self.assertIn('Copies already written to the log folder are not removed.', confirm)

    def test_notified_logs_is_full_screen(self):
        block = MAIN[MAIN.index('private void notifiedLogs()'):MAIN.index('private void removeNotifiedLog')]
        self.assertIn('root.addView(heading', block)
        self.assertIn('root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1))', block)
        self.assertIn('ui.button("Remove"', block)
        self.assertIn('ui.button("Clear all"', block)
        self.assertIn('openEntryByUid(entry.uid)', block)
        self.assertNotIn('AlertDialog', block)

    def test_storage_ui_is_simple_and_dialog_buttons_are_separated(self):
        settings = MAIN[MAIN.index('content.addView(ui.heading("Logs"))'):MAIN.index('content.addView(ui.heading("Display"))')]
        self.assertIn('App-data log limit:', settings)
        self.assertIn('Log storage: app data', settings)
        self.assertNotIn('Automatic log storage', settings)
        self.assertNotIn('Plaintext log folder', settings)
        storage = MAIN[MAIN.index('private void showLogStorage'):MAIN.index('private void chooseStorageLimit')]
        self.assertIn('MML stores logs in app data by default.', storage)
        self.assertIn('Choose log folder', storage)
        self.assertNotIn('Plaintext log folder', storage)
        self.assertNotIn('plaintext mirror', storage.lower())
        buttons = MAIN[MAIN.index('private void styleDialogButton'):MAIN.index('private AlertDialog showText')]
        self.assertIn('MarginLayoutParams', buttons)
        self.assertIn('setMarginStart', buttons)
        self.assertIn('setMarginEnd', buttons)

    def test_live_tail_uses_stable_index_math(self):
        self.assertIn('first + visible >= total', MAIN)
        self.assertIn('setOverScrollMode(View.OVER_SCROLL_NEVER)', MAIN)
        self.assertNotIn('TRANSCRIPT_MODE_ALWAYS_SCROLL', MAIN)
        self.assertNotIn('last.getBottom() <= view.getHeight()', MAIN)

    def test_two_dp_dividers_and_reverse_date(self):
        self.assertIn('list.setDividerHeight(dp(2))', UI)
        home = MAIN[MAIN.index('private void home()'):MAIN.index('private void apps()')]
        self.assertIn('ui.reverse(header)', home)
        self.assertIn('header.setGravity(Gravity.CENTER)', home)

    def test_native_widgets(self):
        self.assertIn('new Button(activity)', UI)
        self.assertIn('new EditText(activity)', UI)
        self.assertIn('new ListView', MAIN)
        self.assertIn('new GridView', MAIN)
        self.assertIn('Canvas', UI)

if __name__ == '__main__':
    unittest.main()
