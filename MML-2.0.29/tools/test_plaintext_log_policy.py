#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for path in (root / 'app/src/main').rglob('*'):
    if not path.is_file() or path.suffix not in {'.java', '.xml'}:
        continue
    text = path.read_text(encoding='utf-8')
    for token in ['org.json', 'JSONObject', '.jsonl', 'application/x-ndjson']:
        assert token not in text, f'{token} remains in production file {path}'

mirror = (root / 'app/src/main/java/vip/thatiam/mml/PlainTextMirror.java').read_text()
codec = (root / 'app/src/main/java/vip/thatiam/mml/PlainTextLogCodec.java').read_text()
main = (root / 'app/src/main/java/vip/thatiam/mml/MainActivity.java').read_text()
assert '.log.txt' in mirror and '"text/plain"' in mirror
assert 'MML-Logs' in mirror
assert 'importTree' not in mirror and 'Import MML' not in main
assert 'Log storage: app data' in main
assert 'Choose log folder' in main
assert 'Plaintext log folder' not in main
assert 'FORMAT_VERSION = 1' in codec and 'MML LOG RECORD ' in codec and 'Body-Characters:' in codec
assert 'out.append(e.body)' in codec
print('plaintext log policy: PASS')

format_doc = (root / 'TEXT-LOG-FORMAT.txt').read_text()
assert 'Status: stable format contract' in format_doc
assert 'MML LOG RECORD 1' in format_doc
for field in ['UID:', 'Time:', 'Time-Millis:', 'Captured-Millis:', 'Date:', 'Timezone:',
              'Session:', 'Kind:', 'Source:', 'Type:', 'Title:', 'Notification-Key-Hash:',
              'Body-Characters:']:
    assert field in format_doc, field
assert 'incompatible future change requires a new record header' in format_doc
interaction = (root / 'INTERACTION-CONTRACT.txt').read_text()
assert 'Tap a log row: open that row\'s full-screen detail view.' in interaction
assert 'Checkered-border log row:' in interaction
assert 'Long-press text: use Android\'s native text selection/copy behavior.' in interaction
