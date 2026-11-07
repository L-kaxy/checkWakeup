#!/usr/bin/env python3
"""Finite host-SQL checks of the actual v1 -> v2 DDL (not Android runtime tests)."""
import json
from pathlib import Path
import re
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "app/src/main/java/dev/checkwakeup/UploadStore.java").read_text()
create_section = source.split("@Override public void onCreate", 1)[1].split("@Override public void onUpgrade", 1)[0]
upgrade_section = source.split("@Override public void onUpgrade", 1)[1].split("String text(", 1)[0]


def statements(section):
    return ["".join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', expression))
            for expression in re.findall(r"db\.execSQL\((.*?)\);", section, re.S)]


create = statements(create_section)
migrate = statements(upgrade_section)
assert len(create) == 3 and len(migrate) == 1
assert migrate[0] == "ALTER TABLE days ADD COLUMN signal TEXT NOT NULL DEFAULT ''"
checks = 0


def check(name, condition):
    global checks
    assert condition, name
    checks += 1
    print("PASS", name)


with tempfile.TemporaryDirectory(prefix="upload-migration-", dir=ROOT / ".cache") as temp:
    file = Path(temp) / "uploads.db"
    db = sqlite3.connect(file)
    for sql in create:
        db.execute(sql.replace(",signal TEXT NOT NULL DEFAULT ''", ""))
    db.executemany("INSERT INTO metadata VALUES(?,?)",
                   [("device_code", "old-device-uuid"), ("sequence", "42"), ("last_success", "1234")])
    db.execute("INSERT INTO days VALUES(?,?,?,?,?,?,?,?,?,?,?)", (
        "2026-11-06|Asia/Shanghai", 100, 200, "Asia/Shanghai", "example.app", "测试",
        "[[540,660]]", '{"revision":41}', 41, 0, 1))
    db.execute("INSERT INTO outbox VALUES(1,?)", ('{"report_id":"stable-inflight"}',))
    db.commit()
    db.execute(migrate[0])
    db.commit()
    db.close()
    db = sqlite3.connect(file)
    check("upgrade retains the installation device code",
          db.execute("SELECT value FROM metadata WHERE name='device_code'").fetchone()[0] == "old-device-uuid")
    check("upgrade retains the monotonic sequence",
          db.execute("SELECT value FROM metadata WHERE name='sequence'").fetchone()[0] == "42")
    check("upgrade retains last successful contact",
          db.execute("SELECT value FROM metadata WHERE name='last_success'").fetchone()[0] == "1234")
    check("upgrade retains pending daily payload and revision",
          db.execute("SELECT payload,revision,dirty FROM days").fetchone() == ('{"revision":41}', 41, 1))
    check("upgrade retains the exact stable in-flight report",
          db.execute("SELECT body FROM outbox").fetchone()[0] == '{"report_id":"stable-inflight"}')
    check("existing rows acquire a non-null empty signal, triggering first re-evaluation",
          db.execute("SELECT signal FROM days").fetchone()[0] == "")
    fresh = sqlite3.connect(":memory:")
    for sql in create:
        fresh.execute(sql)
    check("fresh-install and upgraded daily schemas match",
          list(db.execute("PRAGMA table_info(days)")) == list(fresh.execute("PRAGMA table_info(days)")))
    db.execute("INSERT INTO metadata VALUES('last_signal','unchanged-business-state')")
    db.execute("INSERT INTO metadata VALUES('last_queued_at','900000')")
    db.commit()
    db.close()
    db = sqlite3.connect(file)
    check("heartbeat gate survives closing and reopening the database",
          dict(db.execute("SELECT name,value FROM metadata"))["last_queued_at"] == "900000"
          and dict(db.execute("SELECT name,value FROM metadata"))["last_signal"] == "unchanged-business-state")
    # This is the same revision-guarded ACK predicate as UploadStore.acknowledge().
    db.execute("UPDATE days SET revision=44,dirty=1")
    db.execute("UPDATE days SET dirty=0 WHERE day_key=? AND revision=?",
               ("2026-11-06|Asia/Shanghai", 41))
    check("old in-flight ACK cannot clear a newer pending change",
          db.execute("SELECT dirty FROM days").fetchone()[0] == 1)
    db.close()
    fresh.close()
print(f"PASS: {checks} host-SQL migration/persistence checks; no Android runtime used.")
