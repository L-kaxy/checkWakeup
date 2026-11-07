#!/usr/bin/env python3
"""Finite Java -> actual Django backend tests, using an isolated disposable DB.

Run test-upload.sh first, then use the existing backend Python environment.
No production requests, credentials, or changes to the backend source tree.
"""
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BACKEND = Path(os.environ.get("CHECKWAKEUP_BACKEND", ROOT.parent / "checkWakeup_backend"))
FIXTURES = ROOT / ".cache/upload-tests"
APP_VERSION = json.loads((ROOT / "version.json").read_text())["name"]
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
sys.dont_write_bytecode = True
sys.path.insert(0, str(BACKEND))

with tempfile.TemporaryDirectory(prefix="android-ingest-test-", dir=ROOT / ".cache") as data:
    os.environ.update(
        DJANGO_SETTINGS_MODULE="checkwakeup.settings", CHECKWAKEUP_TESTING="1",
        DATA_DIR=data, INGEST_PRIVATE_KEY_FILE=str(ROOT / "tests/fixtures/test-private.pem"),
        ALLOWED_HOSTS="testserver", LOG_LEVEL="ERROR",
    )
    import django
    django.setup()
    from django.core.management import call_command
    from django.test import Client
    from core.crypto import decrypt
    from core.models import Device, DailySnapshot
    from core.validation import validate_payload
    from core.protocol import strict_json
    call_command("migrate", verbosity=0)
    client = Client()
    passed = 0

    def check(name, condition):
        global passed
        assert condition, name
        passed += 1
        print("PASS", name)

    def post(name):
        return client.post("/ingest/v1/reports", (FIXTURES / f"{name}.jwe").read_bytes(),
                           content_type="application/jose")

    for name in ("historical", "multi-day"):
        java_json = json.loads((FIXTURES / f"{name}.json").read_text())
        decoded = strict_json(decrypt((FIXTURES / f"{name}.jwe").read_bytes()))
        check(f"{name}: Python decrypts actual Java JWE without losing fields", decoded == java_json)
        normalized, warnings = validate_payload(decoded)
        check(f"{name}: actual backend accepts the Java payload schema", normalized == java_json)
        check(f"{name}: all revisions are within the root sequence",
              all(1 <= d["revision"] <= java_json["sequence"] for d in normalized["days"]))

    first = post("historical")
    check("historical: full endpoint accepts Java ciphertext", first.status_code == 200)
    check("historical: device created once", Device.objects.count() == 1)
    snap = DailySnapshot.objects.get()
    check("historical: qualified_at is the first ten-second completion",
          snap.payload["windows"][0]["qualified_at"] == "2026-11-06T01:02:10.000Z")
    check("historical: local 06:00 business date survives rollover",
          str(snap.business_date) == "2026-11-06" and snap.unlock_count == 1)
    again = post("historical")
    check("same persisted report retry is idempotent",
          again.status_code == 200 and again.json()["results"][0]["result"] == "unchanged"
          and Device.objects.count() == 1 and DailySnapshot.objects.count() == 1)
    batch = post("multi-day")
    check("31-day coalesced batch is accepted", batch.status_code == 200
          and len(batch.json().get("results", [])) == 31)
    check("backend stores daily snapshots, not one record per refresh",
          DailySnapshot.objects.count() == 31)
    latest = Device.objects.get()
    check("metadata follows the newest monotonic sequence",
          latest.latest_sequence == 200 and latest.device_name == "本地联调红米 📱"
          and latest.client_app_version == APP_VERSION)
    corrected = latest.days.get(business_date="2026-11-06", timezone="Asia/Shanghai")
    check("newer full snapshot may correct counts downward",
          corrected.revision > 10 and corrected.unlock_count == 0)
    older = post("historical")
    check("an old retry cannot overwrite newer corrected daily data",
          older.status_code == 200 and older.json()["results"][0]["result"] == "ignored_stale")
    corrected.refresh_from_db()
    latest.refresh_from_db()
    check("old retry does not roll back metadata/revision",
          corrected.unlock_count == 0 and latest.latest_sequence == 200)
    check("plaintext report is refused",
          client.post("/ingest/v1/reports", (FIXTURES / "historical.json").read_bytes(),
                      content_type="application/json").status_code == 415)
    print(f"PASS: {passed} isolated backend integration checks; production was not written.")
