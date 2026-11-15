#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
HISTORY = (ROOT / 'app/src/main/java/vip/thatiam/mml/HistoryCollector.java').read_text()
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
MIRROR = (ROOT / 'app/src/main/java/vip/thatiam/mml/PlainTextMirror.java').read_text()
CRASH = (ROOT / 'app/src/main/java/vip/thatiam/mml/CrashRecorder.java').read_text()

class StabilityPolicyTest(unittest.TestCase):
    def test_crash_recorder(self):
        self.assertIn('android:name=".MmlApplication"', MANIFEST)
        self.assertIn('getHistoricalProcessExitReasons', CRASH)

    def test_only_listener_persists(self):
        self.assertEqual(1, MANIFEST.count('<service '))
        self.assertIn('MmlNotificationListener', MANIFEST)
        self.assertNotIn('CaptureService', MANIFEST)
        self.assertNotIn('BootReceiver', MANIFEST)

    def test_optional_history_is_foreground_only(self):
        self.assertIn('public static void setActive(boolean value)', HISTORY)
        self.assertIn('HistoryCollector.setActive(true);', MAIN)
        self.assertIn('HistoryCollector.setActive(false);', MAIN)
        self.assertNotIn('postDelayed(this, 60000)', HISTORY)

    def test_plaintext_mirror_is_foreground_bounded(self):
        self.assertIn('MAX_BATCHES_PER_REQUEST = 8', MIRROR)
        self.assertIn('public static void setActive(boolean value)', MIRROR)
        self.assertIn('PlainTextMirror.setActive(true);', MAIN)
        self.assertIn('PlainTextMirror.setActive(false);', MAIN)
        self.assertNotIn('ScheduledThreadPoolExecutor', MIRROR)
        self.assertNotIn('worker.schedule(', MIRROR)

    def test_journal_has_bounded_retry_and_observer_isolation(self):
        self.assertIn('attempt < 3', JOURNAL)
        self.assertIn('catch (RuntimeException error)', JOURNAL)
        self.assertIn('observer_error', JOURNAL)

    def test_dialogs_guard_window_lifecycle(self):
        self.assertIn('private boolean canShowUi()', MAIN)
        self.assertIn('private AlertDialog showDialogSafely', MAIN)

if __name__ == '__main__':
    unittest.main()
