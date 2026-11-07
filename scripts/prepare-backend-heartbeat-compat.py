#!/usr/bin/env python3
"""Prepare/test a presentation-only patch, also when already applied locally.

Never writes the backend source or deployment.
"""
from datetime import datetime, timedelta, timezone
import difflib
import os
from pathlib import Path
import sys
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
BACKEND = Path(os.environ.get("CHECKWAKEUP_BACKEND", ROOT.parent / "checkWakeup_backend"))
sys.dont_write_bytecode = True
sys.path.insert(0, str(BACKEND))
file = BACKEND / "core/presentation.py"
current = file.read_text()
marker = (
    "# 15-minute no-change heartbeat plus five minutes of scheduling/network grace.\n"
    "FRESHNESS_SECONDS = 20 * 60\n\n\n"
)
applied = marker in current
if applied:
    assert current.count(marker) == 1
    assert current.count("data_age > FRESHNESS_SECONDS") == 1
    assert current.count("contact_age > FRESHNESS_SECONDS") == 1
    before = current.replace(marker, "").replace(
        "data_age > FRESHNESS_SECONDS", "data_age > 180").replace(
        "contact_age > FRESHNESS_SECONDS", "contact_age > 180")
else:
    before = current
assert before.count("data_age > 180") == 1 and before.count("contact_age > 180") == 1
after = before.replace(
    "def device_data(device, today=None, now=None):",
    marker +
    "def device_data(device, today=None, now=None):",
).replace("data_age > 180", "data_age > FRESHNESS_SECONDS").replace(
    "contact_age > 180", "contact_age > FRESHNESS_SECONDS")
now = datetime(2026, 11, 7, 8, 0, tzinfo=timezone.utc)


def load(source):
    module = {}
    exec(compile(source, str(file), "exec"), module)
    # Only test status semantics; rendering the unrelated day card is out of scope.
    module["day_data"] = lambda day: {}
    return module["device_data"]


old, new = load(before), load(after)
device = SimpleNamespace(
    device_code="fixed-test-device", display_name="测试", device_name="测试",
    alias="", archived=False, monitoring_enabled=True, current_timezone="Asia/Shanghai",
    last_received_at=now,
)
day = SimpleNamespace(calculated_at=now, quality_status="available")
checks = 0


def check(name, fn, expected, *, age=0, data_age=None, quality="available",
          monitoring=True, missing=False, no_contact=False):
    global checks
    device.last_received_at = None if no_contact else now - timedelta(seconds=age)
    device.monitoring_enabled = monitoring
    day.calculated_at = now - timedelta(seconds=age if data_age is None else data_age)
    day.quality_status = quality
    result = fn(device, None if missing else day, now=now)["status"]
    assert result == expected, (name, result, expected)
    checks += 1
    print("PASS", name)


check("old three-minute threshold mislabels a valid heartbeat interval", old, "offline", age=240)
check("healthy device remains online between fifteen-minute heartbeats", new, "online", age=899)
check("scheduling/network grace remains online before twenty minutes", new, "online", age=1199)
check("exactly twenty minutes is still within grace", new, "online", age=1200)
check("longer than twenty minutes is marked disconnected", new, "offline", age=1201)
check("fresh contact does not conceal genuinely stale data", new, "stale", age=0, data_age=1201)
check("explicit data-quality failure remains stale immediately", new, "stale", quality="stale")
check("paused monitoring remains paused", new, "paused", monitoring=False)
check("missing current day remains missing", new, "missing", missing=True)
check("unknown last contact remains disconnected", new, "offline", no_contact=True)
check("bad future calculation clock remains stale", new, "stale", data_age=-301)
patch = "".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                   fromfile="a/core/presentation.py", tofile="b/core/presentation.py"))
(ROOT / "dist").mkdir(exist_ok=True)
(ROOT / "dist/backend-heartbeat-compat.patch").write_text(patch)
assert file.read_text() == current
state = "local patch already applied" if applied else "patch prepared, backend unchanged"
print(f"PASS: {checks} backend-display compatibility checks; {state}; online not modified.")
