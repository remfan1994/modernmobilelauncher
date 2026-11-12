#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
ENTRY = (ROOT / 'app/src/main/java/vip/thatiam/mml/Entry.java').read_text()

class SimpleHomePolicyTest(unittest.TestCase):
    def test_major_navigation_is_full_screen(self):
        self.assertIn('screen.matches("HOME|SETTINGS|APPS|DETAIL|APP_INFO|NOTIFIED")', MAIN)
        render = MAIN[MAIN.index('private void render()'):MAIN.index('private void home()')]
        for call in ['settings();', 'apps();', 'detail();', 'appInfo();', 'notifiedLogs();', 'home();']:
            self.assertIn(call, render)
        self.assertNotIn('showAppPicker()', MAIN)
        self.assertNotIn('openEntryDialog', MAIN)
        self.assertNotIn('appPickerDialog', MAIN)
        self.assertNotIn('entryDialog', MAIN)

    def test_home_is_log_plus_one_action_and_date_warning(self):
        home = MAIN[MAIN.index('private void home()'):MAIN.index('private void apps()')]
        self.assertIn('ui.button("Make a log entry"', home)
        begin = MAIN[MAIN.index('private void beginNewEntry()'):MAIN.index('private void addBloodguiltWarning()')]
        self.assertIn('show("APPS")', begin)
        self.assertIn('header.setOnClickListener(v -> addBloodguiltWarning())', home)
        self.assertNotIn('setOnLongClickListener', home)
        warning = MAIN[MAIN.index('private void addBloodguiltWarning()'):MAIN.index('private void show(String destination)')]
        self.assertIn('"home_reminder"', warning)
        self.assertIn('"Bloodguilt curse"', warning)
        self.assertIn('journal.submit(', warning)

    def test_app_picker_is_full_screen_and_settings_is_bottom_footer(self):
        picker = MAIN[MAIN.index('private void apps()'):MAIN.index('private void openAppInfo')]
        self.assertIn('ui.input("Find an app")', picker)
        self.assertIn('new ListView(this)', picker)
        self.assertIn('new GridView(this)', picker)
        self.assertIn('root.addView(choicesView, new LinearLayout.LayoutParams(-1, 0, 1))', picker)
        self.assertIn('ui.button("Settings", () -> show("SETTINGS"))', picker)
        self.assertIn('root.addView(settings, new LinearLayout.LayoutParams(-1, -2))', picker)
        self.assertLess(picker.index('root.addView(choicesView'), picker.index('ui.button("Settings"'))
        self.assertNotIn('AlertDialog', picker)
        self.assertNotIn('dialog()', picker)

    def test_app_picker_tap_launches_long_press_opens_app_info(self):
        picker = MAIN[MAIN.index('private void apps()'):MAIN.index('private void openAppInfo')]
        self.assertIn('setOnItemClickListener', picker)
        self.assertIn('submitAppLaunch(adapter.visible.get(position))', picker)
        self.assertIn('setOnItemLongClickListener', picker)
        self.assertIn('openAppInfo(adapter.visible.get(position))', picker)
        self.assertIn('return true;', picker)
        self.assertNotIn('CheckBox', picker)

    def test_app_info_has_package_version_and_android_settings(self):
        info = MAIN[MAIN.index('private void appInfo()'):MAIN.index('private void submitAppLaunch')]
        self.assertIn('"Package\\n" + appInfoPackage', info)
        self.assertIn('appVersion(packages.getPackageInfo(appInfoPackage, 0))', info)
        self.assertIn('Settings.ACTION_APPLICATION_DETAILS_SETTINGS', info)
        self.assertIn('"Android app settings (Force stop)"', info)
        self.assertNotIn('AlertDialog', info)

    def test_brick_view_is_settings_preference_not_picker_control(self):
        picker = MAIN[MAIN.index('private void apps()'):MAIN.index('private void openAppInfo')]
        settings = MAIN[MAIN.index('private void settings()'):MAIN.index('private void notificationAccessHelp()')]
        adapter = MAIN[MAIN.index('private final class AppPickerAdapter'):MAIN.index('private static String appVersion')]
        self.assertIn('prefs.getBoolean("app_picker_brick", false)', picker)
        self.assertIn('GridView.AUTO_FIT', picker)
        self.assertIn('grid.setColumnWidth(ui.dp(96))', picker)
        self.assertIn('"App picker view: " + (brickPicker ? "Brick" : "List")', settings)
        self.assertNotIn('"App picker view:', picker)
        self.assertIn('brickLabel(item.label)', adapter)
        self.assertIn('value.offsetByCodePoints(0, 7)', adapter)
        self.assertEqual(1, MAIN.count('"App picker view: '))

    def test_list_view_is_one_line_and_version_is_lazy(self):
        adapter = MAIN[MAIN.index('private final class AppPickerAdapter'):MAIN.index('private static String appVersion')]
        loader = MAIN[MAIN.index('private List<AppItem> loadApps()'):MAIN.index('private final class AppPickerAdapter')]
        self.assertEqual(1, adapter.count('row.addView(name'))
        self.assertNotIn('item.packageName +', adapter)
        self.assertNotIn('item.version', adapter)
        self.assertNotIn('getPackageInfo(', loader)

    def test_settings_direct_access_is_app_picker_only(self):
        picker = MAIN[MAIN.index('private void apps()'):MAIN.index('private void openAppInfo')]
        self.assertIn('ui.button("Settings", () -> show("SETTINGS"))', picker)
        self.assertEqual(1, MAIN.count('ui.button("Settings"'))
        self.assertNotIn('settingsOnLongPress', MAIN)
        self.assertNotIn('MmlSettingsNotification', MAIN)
        self.assertFalse((ROOT / 'app/src/main/java/vip/thatiam/mml/MmlSettingsNotification.java').exists())

    def test_system_home_returns_from_any_full_screen_surface(self):
        new_intent = MAIN[MAIN.index('protected void onNewIntent'):MAIN.index('protected void onUserLeaveHint')]
        self.assertIn('Intent.CATEGORY_HOME', new_intent)
        self.assertIn('returnToHomeFromSystemHome()', new_intent)
        helper = MAIN[MAIN.index('private void returnToHomeFromSystemHome()'):MAIN.index('private void openWeb')]
        self.assertIn('!"HOME".equals(screen)', helper)
        self.assertIn('show("HOME")', helper)

    def test_back_navigation_matches_full_screen_hierarchy(self):
        back = MAIN[MAIN.index('public void onBackPressed()'):MAIN.index('public void changed()')]
        self.assertIn('case "APP_INFO": show("APPS")', back)
        self.assertIn('case "SETTINGS": show("APPS")', back)
        self.assertIn('case "NOTIFIED": show("SETTINGS")', back)
        self.assertIn('case "DETAIL":', back)
        self.assertIn('case "APPS": show("HOME")', back)

    def test_app_launch_is_saved_then_home_then_opened(self):
        block = MAIN[MAIN.index('private void submitAppLaunch'):MAIN.index('private void launchApp')]
        self.assertIn('Entry.appLaunch(item.packageName, item.label)', block)
        self.assertIn('journal.submit(', block)
        self.assertLess(block.index('show("HOME")'), block.index('launchApp(item.packageName)'))
        self.assertNotIn('applaunch::', MAIN)

    def test_share_text_is_a_plain_user_log_entry(self):
        self.assertIn('return event("USER", "Android share", "shared_text", "Shared text", body)', ENTRY)
        self.assertIn('Entry.sharedText(text)', MAIN)

    def test_scrolling_fix_is_preserved(self):
        home = MAIN[MAIN.index('private void home()'):MAIN.index('private void apps()')]
        self.assertIn('setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED)', home)
        self.assertIn('setOverScrollMode(View.OVER_SCROLL_NEVER)', home)
        self.assertIn('first + visible >= total', home)
        self.assertIn('state == SCROLL_STATE_IDLE && refreshPending', home)
        self.assertNotIn('TRANSCRIPT_MODE_ALWAYS_SCROLL', MAIN)

    def test_notes_archive_and_composer_are_absent(self):
        production = ROOT / 'app/src/main/java/vip/thatiam/mml'
        for name in ['DraftStore.java','NoteNotifications.java','NoteNotificationReceiver.java','LegacyImport.java','LegacyCodec.java','LogEntryCommand.java']:
            self.assertFalse((production / name).exists(), name)
        self.assertNotIn('composeEditor', MAIN)
        self.assertNotIn('Submit entry', MAIN)
        self.assertNotIn('App launcher', MAIN)
        self.assertNotIn('archiveDays(', MAIN)
        self.assertNotIn('updateUserEntry(', (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text())

    def test_one_background_service_only(self):
        self.assertEqual(1, MANIFEST.count('<service '))
        self.assertIn('MmlNotificationListener', MANIFEST)
        self.assertIn('EntryNotificationReceiver', MANIFEST)
        self.assertIn('android:exported="false"', MANIFEST)
        self.assertNotIn('FOREGROUND_SERVICE', MANIFEST)
        self.assertNotIn('RECEIVE_BOOT_COMPLETED', MANIFEST)

if __name__ == '__main__':
    unittest.main()
