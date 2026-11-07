#!/usr/bin/env python3
"""Bounded structural checks, not a replacement for Android Lint or device tests."""
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
NS = "{http://schemas.android.com/apk/res/android}"
for file in MAIN.rglob("*.xml"):
    ET.parse(file)
    print("PASS XML", file.relative_to(ROOT))
manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
permissions = {p.get(NS + "name") for p in manifest.findall("uses-permission")}
assert "android.permission.INTERNET" in permissions
assert "android.permission.QUERY_ALL_PACKAGES" not in permissions
assert "android.permission.BIND_ACCESSIBILITY_SERVICE" not in permissions
assert "android.permission.SYSTEM_ALERT_WINDOW" not in permissions
application = manifest.find("application")
assert application.get(NS + "allowBackup") == "false"
assert len(application.findall("service")) == 1
for component in ("service", "receiver"):
    for item in application.findall(component):
        assert item.get(NS + "exported") == "false"
for component in ("activity", "service", "receiver"):
    for item in application.findall(component):
        name = item.get(NS + "name")
        file = MAIN / ("java/dev/checkwakeup/" + name.lstrip(".") + ".java")
        assert file.exists(), file
all_xml = "\n".join(f.read_text() for f in (MAIN / "res").rglob("*.xml"))
declared_ids = set(re.findall(r"@\+id/(\w+)", all_xml))
for file in (MAIN / "java").rglob("*.java"):
    for name in re.findall(r"(?<!android\.)R\.id\.(\w+)", file.read_text()):
        assert name in declared_ids, (file, name)
assert 'PAGE_TRANSITION_MS = 500' in (MAIN / "java/dev/checkwakeup/core/StatsEngine.java").read_text()
assert "REQUIRED_MS = 10_000" in (MAIN / "java/dev/checkwakeup/core/StatsEngine.java").read_text()
settings = (MAIN / "java/dev/checkwakeup/AppSettings.java").read_text()
assert 'prefs.getBoolean("live_updates", false)' in settings
assert 'prefs.getBoolean("upload_enabled", false)' in settings
uploader = (MAIN / "java/dev/checkwakeup/UploadManager.java").read_text()
transport = (MAIN / "java/dev/checkwakeup/core/ReportTransport.java").read_text()
assert "setInstanceFollowRedirects(false)" in transport
assert "setConnectTimeout(8_000)" in transport
assert "setReadTimeout(10_000)" in transport
assert 'setRequestProperty("Content-Type", "application/jose")' in transport
assert "ReportCrypto.encrypt(" in uploader
assert "HostnameVerifier" not in uploader and "TrustManager" not in uploader
assert "bindConfiguration()" in uploader
assert "token != generation" in uploader
assert 'store.putMeta("bound_url", settings.uploadUrl())' in uploader
assert 'store.putMeta("error", error)' in uploader
store = (MAIN / "java/dev/checkwakeup/UploadStore.java").read_text()
assert "String text(String key, String fallback)" in store
assert "void putMeta(String key, Object value)" in store
assert "void meta(" not in store  # Never overload a metadata getter as a setter.
assert '"day_key=? AND revision=?"' in store
assert "ALTER TABLE days ADD COLUMN signal TEXT NOT NULL DEFAULT ''" in store
assert "UploadTrigger.due(lastSignal, signal, now, lastQueuedAt)" in uploader
assert 'store.putMeta("last_signal", signal)' in uploader
assert 'store.putMeta("last_queued_at", now)' in uploader
assert "if (!due && now < nextHistoricalCheck) return" in uploader
trigger = (MAIN / "java/dev/checkwakeup/core/UploadTrigger.java").read_text()
assert "HEARTBEAT_MS = 15 * 60_000L" in trigger
monitor = (MAIN / "java/dev/checkwakeup/MonitorService.java").read_text()
assert "repository.interactiveAndUnlocked() ? 2_000 : 60_000" in monitor
assert application.get(NS + "networkSecurityConfig") == "@xml/network_security_config"
notification = (MAIN / "java/dev/checkwakeup/StatusNotification.java").read_text()
assert "if (settings.liveUpdates())" in notification
assert "LiveUpdates.request(builder)" in notification
assert ": shortText" in notification
assert "setCustomContentView" not in notification
live = (MAIN / "java/dev/checkwakeup/LiveUpdates.java").read_text()
assert 'REQUEST = "android.requestPromotedOngoing"' in live
assert 'PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"' in live
assert "setAccessible" not in live  # No hidden/private API access.
assert "active.flags & flag" in live  # Read system result; never set the promoted flag.
assert "android.permission.POST_PROMOTED_NOTIFICATIONS" in permissions
print("PASS experiment defaults OFF, original summary fallback, public-API request and promotion probe")
print("PASS optional upload OFF, encrypted bodies, bounded network and destination cancellation")
print("PASS change-triggered snapshots / 15-minute heartbeat / persisted gate / unchanged local polling")
print("PASS component names, view IDs, non-exported background components, no intrusive permissions")
trusted = (MAIN / "java/dev/checkwakeup/TrustedServer.java").read_text()
assert "BEGIN PUBLIC KEY" not in trusted
for field in ("URL", "KID", "PUBLIC_KEY"):
    assert f"GeneratedServerConfig.{field}" in trusted
report = (MAIN / "java/dev/checkwakeup/core/ReportJson.java").read_text()
assert "APP_VERSION = GeneratedAppVersion.NAME" in report
builder = (ROOT / "scripts/build-apk.py").read_text()
assert "load_config()" in builder and "load_signing()" in builder
assert "expected_certificate()" in builder
assert '"-genkeypair"' not in builder
assert '"pass:android"' not in builder
assert "env:CHECKWAKEUP_SIGN_STORE_PASS" in builder
workflow = (ROOT / ".github/workflows/android-release.yml").read_text()
assert "pull_request_target" not in workflow
assert "github.event_name != 'pull_request'" in workflow
assert "dist/build-info.json" in workflow
assert "persist-credentials: false" in workflow
assert re.search(r'uses: actions/checkout@[0-9a-f]{40}\b', workflow)
assert re.search(r'uses: actions/upload-artifact@[0-9a-f]{40}\b', workflow)
print("PASS generated deployment/version constants, no replacement signing key, pinned CI actions")
print("NOTE: Android Lint and device/emulator execution are not performed by this script.")
