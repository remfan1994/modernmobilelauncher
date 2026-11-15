#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
BATCH = (ROOT / 'app/src/main/java/vip/thatiam/mml/AutomaticBatchWindow.java').read_text()
HISTORY = (ROOT / 'app/src/main/java/vip/thatiam/mml/HistoryCollector.java').read_text()
MIRROR = (ROOT / 'app/src/main/java/vip/thatiam/mml/PlainTextMirror.java').read_text()
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()

class ResourceDietPolicyTest(unittest.TestCase):
    def test_no_background_framework_bloat(self):
        for forbidden in ['android.permission.WAKE_LOCK', 'android.permission.FOREGROUND_SERVICE',
                          'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.INTERNET']:
            self.assertNotIn(forbidden, MANIFEST)
        for forbidden in ['JobScheduler', 'AlarmManager', 'WorkManager']:
            self.assertNotIn(forbidden, MAIN + JOURNAL + HISTORY + MIRROR)
        self.assertEqual(1, MANIFEST.count('<service '))

    def test_optional_workers_time_out(self):
        self.assertIn('reader.allowCoreThreadTimeOut(true)', MAIN)
        self.assertIn('worker.allowCoreThreadTimeOut(true)', HISTORY)
        self.assertIn('worker.allowCoreThreadTimeOut(true)', MIRROR)
        self.assertIn('THREAD_PRIORITY_BACKGROUND', HISTORY)
        self.assertIn('THREAD_PRIORITY_BACKGROUND', MIRROR)
        self.assertIn('!enabled.getBoolean("capture_usage", false)', HISTORY)
        self.assertIn('!enabled.getBoolean("capture_sms", false)', HISTORY)
        self.assertIn('!enabled.getBoolean("capture_calls", false)', HISTORY)

    def test_plaintext_mirror_is_debounced(self):
        self.assertIn('main.postDelayed(mirrorSync, 5000)', MAIN)
        self.assertIn('main.removeCallbacks(mirrorSync)', MAIN)
        self.assertIn('PlainTextMirror.setActive(false)', MAIN)

    def test_writer_is_background_priority_and_burst_coalesced(self):
        self.assertIn('THREAD_PRIORITY_BACKGROUND', JOURNAL)
        self.assertNotIn('Thread.sleep(40)', JOURNAL)
        self.assertIn('QUIET_MILLIS = 250L', BATCH)
        self.assertIn('MAX_MILLIS = 1000L', BATCH)
        self.assertIn('MAX_JOBS = 32', BATCH)
        self.assertIn('queue.poll(wait, TimeUnit.MILLISECONDS)', JOURNAL)
        self.assertIn('if (next.protectedWrite || next.storageRefresh) break;', JOURNAL)
        self.assertIn('now - lastStorageCheck < 5000', JOURNAL)

if __name__ == '__main__':
    unittest.main()
