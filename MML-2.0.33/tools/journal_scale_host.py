#!/usr/bin/env python3
"""Finite host stress for the current live-journal query shape. Not an Android benchmark."""
import argparse, json, os, sqlite3, tempfile, time

p=argparse.ArgumentParser(); p.add_argument('--rows',type=int,default=250000); p.add_argument('--out')
a=p.parse_args()
fd,path=tempfile.mkstemp(prefix='mml-journal-',suffix='.db'); os.close(fd)
con=sqlite3.connect(path)
try:
    con.executescript('''
    PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
    CREATE TABLE entries(row_id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
      time_ms INTEGER NOT NULL,captured_ms INTEGER NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,
      session TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,
      body TEXT NOT NULL,notification_key_hash TEXT NOT NULL DEFAULT '');
    CREATE INDEX entries_session ON entries(session,row_id);
    CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE states(key TEXT PRIMARY KEY,value TEXT NOT NULL,seen_ms INTEGER NOT NULL);
    ''')
    session='scale-session'; base=1770000000000
    batch=[]; start=time.perf_counter()
    for i in range(a.rows):
        if i % 100 == 0:
            kind,source,typ,title='NOTIFICATION','fixture.player','posted','Playing'
        elif i % 251 == 0:
            kind,source,typ,title='CALL','Android','incoming','Call'
        elif i % 509 == 0:
            kind,source,typ,title='SMS','Messages','message','Message'
        else:
            kind,source,typ,title='USAGE','fixture.app','usage_7','App interaction'
        body='Technomatic playing' if kind=='NOTIFICATION' else 'detail'
        batch.append((f'u{i}',base+i,base+i,'2026-11-11','UTC',session,kind,source,typ,title,body,''))
        if len(batch)==5000:
            con.executemany('INSERT INTO entries(uid,time_ms,captured_ms,day,zone,session,kind,source,type,title,body,notification_key_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',batch); batch.clear()
    if batch: con.executemany('INSERT INTO entries(uid,time_ms,captured_ms,day,zone,session,kind,source,type,title,body,notification_key_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',batch)
    con.commit(); seed=time.perf_counter()-start
    custom="((kind='SYSTEM' AND type='home_reminder') OR kind='USER' OR (kind='NOTIFICATION' AND type IN ('posted','active')) OR kind='CALL' OR kind='SMS')"
    def measure(sql,args=(),reps=30):
        vals=[]; count=0
        for _ in range(reps):
            t=time.perf_counter(); rows=con.execute(sql,args).fetchall(); vals.append((time.perf_counter()-t)*1000); count=len(rows)
        vals.sort(); return {'rows':count,'median_ms':round(vals[len(vals)//2],3),'max_ms':round(max(vals),3)}
    custom_result=measure('SELECT row_id,kind,type,body FROM entries WHERE session=? AND row_id<? AND '+custom+' ORDER BY row_id DESC LIMIT 101',(session,2**63-1))
    full_result=measure('SELECT row_id,kind,type,body FROM entries WHERE session=? AND row_id<? ORDER BY row_id DESC LIMIT 101',(session,2**63-1))
    plan=' | '.join(str(r) for r in con.execute('EXPLAIN QUERY PLAN SELECT row_id FROM entries WHERE session=? AND row_id<? AND '+custom+' ORDER BY row_id DESC LIMIT 101',(session,2**63-1)))
    con.execute("INSERT INTO entries(uid,time_ms,captured_ms,day,zone,session,kind,source,type,title,body,notification_key_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",('shared-delete',base+a.rows+1,base+a.rows+1,'2026-11-11','UTC',session,'USER','Android share','shared_text','Shared text','remember this',''))
    con.commit()
    shared=con.execute("SELECT source,type,title,body FROM entries WHERE uid='shared-delete'").fetchone()
    con.execute("DELETE FROM entries WHERE uid='shared-delete'"); con.commit()
    deleted=con.execute("SELECT count(*) FROM entries WHERE uid='shared-delete'").fetchone()[0]
    integrity=con.execute('PRAGMA integrity_check').fetchone()[0]
    result={'rows':a.rows,'seed_seconds':round(seed,3),'custom_tail':custom_result,'full_tail':full_result,'query_plan':plan,'shared_user_row':shared,'deleted_user_rows_remaining':deleted,'integrity':integrity}
    text=json.dumps(result,indent=2)
    if a.out: open(a.out,'w').write(text+'\n')
    print(text)
finally:
    con.close(); os.unlink(path)
