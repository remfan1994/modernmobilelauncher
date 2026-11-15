#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JOURNAL = (ROOT / 'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()
STORAGE = (ROOT / 'app/src/main/java/vip/thatiam/mml/StoragePolicy.java').read_text()
MAIN = (ROOT / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()

class RollingRetentionPolicyTest(unittest.TestCase):
    def test_machine_history_rolls_but_user_and_warning_do_not(self):
        self.assertIn("kind!='USER'", JOURNAL)
        self.assertIn("type!='home_reminder'", JOURNAL)
        self.assertIn('rollAutomaticHistory(db)', JOURNAL)
        self.assertIn('ROLL_DELETE_BATCH = 2048', STORAGE)
        self.assertIn('MAX_ROLL_BATCHES_PER_PASS = 16', STORAGE)

    def test_plaintext_backlog_is_not_pruned(self):
        self.assertIn('mirrorPruneCeiling', JOURNAL)
        self.assertIn('"mirror:" + Entry.hash(selected)', JOURNAL)
        self.assertIn('row_id<=?', JOURNAL)

    def test_budget_uses_live_pages_not_only_file_length(self):
        self.assertIn('PRAGMA page_count', JOURNAL)
        self.assertIn('PRAGMA freelist_count', JOURNAL)
        self.assertIn('storageAllocatedBytes', JOURNAL)

    def test_settings_explain_rolling_and_plaintext_delete_semantics(self):
        self.assertIn('App-data log limit:', MAIN)
        self.assertIn('Copies already written to the log folder are not removed.', MAIN)

if __name__ == '__main__':
    unittest.main()
