#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
NOTIFY = (ROOT / 'app/src/main/java/vip/thatiam/mml/EntryNotifications.java').read_text()
RECEIVER = (ROOT / 'app/src/main/java/vip/thatiam/mml/EntryNotificationReceiver.java').read_text()
CAPTURE = (ROOT / 'app/src/main/java/vip/thatiam/mml/NotificationCapture.java').read_text()
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
PRODUCTION = ''.join(p.read_text() for p in (ROOT / 'app/src/main/java').rglob('*.java'))

class EntryActionPolicyTest(unittest.TestCase):
    def test_no_toasts_or_custom_clipboard_copy(self):
        self.assertNotIn('android.widget.Toast', PRODUCTION)
        self.assertNotIn('Toast.makeText', PRODUCTION)
        self.assertNotIn('ClipboardManager', MAIN)
        self.assertNotIn('copyText(', MAIN)

    def test_detail_surface_is_selectable_and_contextual(self):
        popup = MAIN[MAIN.index('private void renderDetail'):MAIN.index('private String formatGroup')]
        self.assertIn('ui.selectable(surface)', popup)
        self.assertNotIn('setOnLongClickListener', popup)
        self.assertIn('bindDetailTap(', popup)
        self.assertIn('surface.setOnTouchListener', popup)
        self.assertIn('GestureDetector', popup)
        self.assertIn('surface.getSelectionStart()', popup)
        self.assertIn('surface.getSelectionEnd()', popup)
        self.assertIn('surface.post(action)', popup)
        self.assertNotIn('surface.setOnClickListener', popup)
        self.assertIn('"app_launch".equals(entry.type)', popup)
        self.assertIn('activateNotificationEntry(entry)', popup)
        self.assertIn('surface.setText(formatGroupDisplay(group))', popup)
        self.assertIn('group.entries.size() == 1 ? new Entry[] {group.first} : null', popup)
        self.assertIn('? "Unnotify" : "Notify"', popup)
        self.assertNotIn('grouped_log', popup)

    def test_notification_has_log_remove_and_persistent_main_action(self):
        self.assertIn('.setAutoCancel(false)', NOTIFY)
        self.assertIn('.setOngoing(true)', NOTIFY)
        self.assertIn('.addAction(android.R.drawable.ic_menu_view, "Log", log)', NOTIFY)
        self.assertIn('.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Remove", remove)', NOTIFY)
        self.assertIn('ACTION_OPEN_LOG', NOTIFY)
        self.assertIn('EntryNotificationReceiver.ACTION_REMOVE', NOTIFY)
        self.assertIn('getLaunchIntentForPackage(entry.source)', NOTIFY)
        self.assertIn('manager.notify(tag, NOTIFICATION_ID, notification)', NOTIFY)
        self.assertIn('ACTION_REMOVE', RECEIVER)
        self.assertIn('Journal.get(context).markNotified(', RECEIVER)
        self.assertIn('EntryNotifications.cancelTag', RECEIVER)
        self.assertIn('EntryNotificationReceiver', MANIFEST)

    def test_app_launch_notifications_deduplicate_by_package(self):
        self.assertIn('return "app:" + Entry.hash(entry.source)', NOTIFY)
        self.assertIn("kind='USER' AND type='app_launch' AND source=?", JOURNAL)
        self.assertIn('notified_ms>0 AND uid<>?', JOURNAL)

    def test_pushed_log_notification_is_not_recaptured(self):
        self.assertIn('getPackageName().equals(notification.getPackageName())', CAPTURE)
        self.assertIn('notification.getId() != CAPTURE_TEST_ID', CAPTURE)
        self.assertNotIn('EntryNotifications.NOTIFICATION_ID', CAPTURE)

    def test_settings_notification_is_removed_and_picker_is_only_settings_entry(self):
        self.assertNotIn('MmlSettingsNotification', PRODUCTION)
        self.assertIn('removeLegacySettingsNotification(this)', MAIN)
        self.assertIn('deleteNotificationChannel(LEGACY_SETTINGS_CHANNEL_ID)', NOTIFY)
        picker = MAIN[MAIN.index('private void apps()'):MAIN.index('private void openAppInfo')]
        self.assertIn('ui.button("Settings", () -> show("SETTINGS"))', picker)
        self.assertNotIn('settingsOnLongPress', MAIN)

    def test_notify_extends_original_row_instead_of_adding_event(self):
        self.assertIn('journal.markNotified(', MAIN)
        self.assertIn('notified_ms', JOURNAL)
        self.assertIn('db.update("entries", values, "uid=?"', JOURNAL)
        self.assertIn('new DetailField("Notification", "posted " + when(entry.notifiedMs))', MAIN)
        notify_path = MAIN[MAIN.index('private void postEntryNotification'):MAIN.index('private CharSequence formatGroupDisplay')]
        self.assertNotIn('journal.record(', notify_path)
        self.assertNotIn('journal.submit(', notify_path)

    def test_notify_state_is_reversible_restored_and_managed(self):
        self.assertIn('EntryNotifications.cancel(this, entry)', MAIN)
        self.assertIn('restoreEntryNotifications()', MAIN)
        self.assertIn('journal.notifiedEntriesAfter(afterRow, 100)', MAIN)
        self.assertIn('EntryNotifications.cancelAll(app)', MAIN)
        self.assertIn('clearAllNotified', JOURNAL)
        self.assertIn('notified_ms>0 AND row_id>?', JOURNAL)
        self.assertIn('add(content, "Notified logs", () -> show("NOTIFIED"))', MAIN)
        manager = MAIN[MAIN.index('private void notifiedLogs()'):MAIN.index('private CharSequence formatGroupDisplay')]
        self.assertIn('ui.button("Remove"', manager)
        self.assertIn('ui.button("Clear all"', manager)
        self.assertIn('openEntryByUid(entry.uid)', manager)

    def test_home_metadata_marks_notified_rows(self):
        row = MAIN[MAIN.index('private final class LogAdapter'):MAIN.index('private static boolean hasDetailAction')]
        self.assertIn('if (e.notifiedMs > 0) source += " / notified";', row)

    def test_delete_cancels_associated_notification(self):
        delete = MAIN[MAIN.index('private void deleteGroup'):MAIN.index('private void activateResolved')]
        self.assertIn('restoreEntryNotifications()', delete)

    def test_android_text_share_target(self):
        self.assertIn('android.intent.action.SEND', MANIFEST)
        self.assertIn('android:mimeType="text/plain"', MANIFEST)
        self.assertIn('Intent.ACTION_SEND.equals(intent.getAction())', MAIN)
        self.assertIn('Intent.EXTRA_TEXT', MAIN)
        self.assertIn('Entry.sharedText(text)', MAIN)

if __name__ == '__main__':
    unittest.main()
