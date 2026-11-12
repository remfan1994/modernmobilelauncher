#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
J=(ROOT/'app/src/main/java/vip/thatiam/mml/Journal.java').read_text()

class LeanSchemaPolicyTest(unittest.TestCase):
    def test_current_schema_is_log_only(self):
        create=J[J.index('public void onCreate(SQLiteDatabase db)'):J.index('public void onUpgrade(SQLiteDatabase db')]
        self.assertIn('CREATE TABLE entries', create)
        self.assertIn('CREATE INDEX entries_session', create)
        self.assertIn('CREATE TABLE metadata', create)
        self.assertIn('CREATE TABLE states', create)
        self.assertIn('notified_ms INTEGER NOT NULL DEFAULT 0', create)
        for old in ['archive_days','note_state','entries_notes','entries_day']:
            self.assertNotIn(old, create)

    def test_upgrade_removes_old_note_archive_machinery(self):
        upgrade=J[J.index('public void onUpgrade(SQLiteDatabase db'):]
        self.assertIn('DROP TABLE IF EXISTS archive_days', upgrade)
        self.assertIn('DROP TABLE IF EXISTS note_state', upgrade)
        self.assertIn("DELETE FROM entries WHERE kind IN ('NOTE','NOTE_EDIT','NOTE_DELETE')", upgrade)
        self.assertIn('CREATE INDEX IF NOT EXISTS entries_session', upgrade)
        self.assertIn('oldVersion > 5', upgrade)
        self.assertIn("DELETE FROM entries WHERE kind='NOTIFICATION' AND source='vip.thatiam.mml'", upgrade)
        self.assertIn('ALTER TABLE entries ADD COLUMN notified_ms INTEGER NOT NULL DEFAULT 0', upgrade)

    def test_database_version_is_six(self):
        self.assertIn('"mml2.db",\n          null,\n          6,', J)

if __name__=='__main__': unittest.main()
