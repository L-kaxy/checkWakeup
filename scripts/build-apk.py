#!/usr/bin/env python3
"""Dependency-free Android build with injected configuration and pinned signing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from build_config import generate, load_config, version
from build_signing import expected_certificate, load_signing

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get("JAVA_HOME", str(ROOT / ".toolchain/jdk/Contents/Home"))) / "bin"
SDK = Path(os.environ.get("ANDROID_HOME", str(ROOT / ".toolchain/android-sdk")))
TOOLS = SDK / "build-tools/35.0.0"
ANDROID = SDK / "platforms/android-35/android.jar"
BUILD = ROOT / "app/build/direct"
DIST = ROOT / "dist"
VERSION = version()["name"]
PACKAGE = "dev.checkwakeup"
NS = "{http://schemas.android.com/apk/res/android}"


def run(*args, env=None, capture=False):
    command = [str(a) for a in args]
    print("+", shlex.join(command), flush=True)
    result = subprocess.run(command, check=True, cwd=ROOT, env=env,
                            stdout=subprocess.PIPE if capture else None, text=capture)
    if capture:
        print(result.stdout, end="")
        return result.stdout


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--debug", action="store_true", help="debuggable APK; still requires the existing signing key")
args = parser.parse_args()
try:
    config = load_config()
    signing = load_signing()
    pinned_certificate = expected_certificate()
except (ValueError, OSError, TypeError) as error:
    raise SystemExit(f"Build/signing configuration invalid ({type(error).__name__}); see GITHUB_RELEASE.md.")
if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != "v" + VERSION:
    raise SystemExit("Release tag must equal v + version.json name.")
sign_env = dict(os.environ, CHECKWAKEUP_SIGN_STORE_PASS=signing["store_password"],
                CHECKWAKEUP_SIGN_KEY_PASS=signing["key_password"])


for directory in (BUILD, BUILD / "generated", BUILD / "classes", BUILD / "dex", DIST):
    directory.mkdir(parents=True, exist_ok=True)
if not ANDROID.exists() or not (JAVA / "javac").exists():
    raise SystemExit("Missing local JDK/SDK. Run bash scripts/bootstrap-toolchain.sh first.")

# Each invocation gets clean generated/class/dex directories: removed source files
# cannot leave old bytecode in the output.
for directory in (BUILD / "generated", BUILD / "classes", BUILD / "dex"):
    shutil.rmtree(directory)
    directory.mkdir()

# Verify the selected alias/certificate BEFORE compilation. Missing/wrong signing
# inputs must never silently produce a non-upgradeable APK.
certificate_file = BUILD / "signing-cert.der"
run(JAVA / "keytool", "-exportcert", "-keystore", signing["keystore_file"],
    "-alias", signing["key_alias"], "-storepass:env", "CHECKWAKEUP_SIGN_STORE_PASS",
    "-file", certificate_file, env=sign_env)
if hashlib.sha256(certificate_file.read_bytes()).hexdigest() != pinned_certificate:
    raise SystemExit("Signing certificate differs from the existing installed-app certificate. Build stopped.")
generate(BUILD / "generated", config)

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
manifest.getroot().set("package", PACKAGE)
manifest.getroot().set(NS + "versionCode", str(version()["code"]))
manifest.getroot().set(NS + "versionName", VERSION)
manifest.getroot().find("application").set(NS + "debuggable", "true" if args.debug else "false")
uses_sdk = ET.Element("uses-sdk")
uses_sdk.set(NS + "minSdkVersion", "28")
uses_sdk.set(NS + "targetSdkVersion", "35")
manifest.getroot().insert(0, uses_sdk)
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
ET.register_namespace("tools", "http://schemas.android.com/tools")
manifest_file = BUILD / "AndroidManifest.xml"
manifest.write(manifest_file, encoding="utf-8", xml_declaration=True)
resources = BUILD / "resources.zip"
unsigned = BUILD / "unsigned.apk"
aligned = BUILD / "aligned.apk"
mode = "debug" if args.debug else "release"
apk = DIST / f"checkwakeup-{VERSION}-{mode}.apk"
signed = BUILD / "signed.apk"

run(TOOLS / "aapt2", "compile", "--dir", ROOT / "app/src/main/res", "-o", resources)
run(TOOLS / "aapt2", "link", "-I", ANDROID, "--manifest", manifest_file,
    "--java", BUILD / "generated", "-o", unsigned, resources)
sources = sorted((ROOT / "app/src/main/java").rglob("*.java"))
sources += sorted((BUILD / "generated").rglob("*.java"))
run(JAVA / "javac", "-encoding", "UTF-8", "-source", "17", "-target", "17",
    "-Xlint:unchecked", "-classpath", ANDROID, "-d", BUILD / "classes", *sources)
classes = sorted((BUILD / "classes").rglob("*.class"))
run(JAVA / "java", "-cp", TOOLS / "lib/d8.jar", "com.android.tools.r8.D8",
    "--debug" if args.debug else "--release",
    "--min-api", "28", "--lib", ANDROID, "--output", BUILD / "dex", *classes)
with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as output:
    for dex in sorted((BUILD / "dex").glob("*.dex")):
        output.write(dex, dex.name)
run(TOOLS / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
run(JAVA / "java", "-jar", TOOLS / "lib/apksigner.jar", "sign",
    "--ks", signing["keystore_file"], "--ks-pass", "env:CHECKWAKEUP_SIGN_STORE_PASS",
    "--key-pass", "env:CHECKWAKEUP_SIGN_KEY_PASS",
    "--ks-key-alias", signing["key_alias"], "--out", signed, aligned, env=sign_env)
verification = run(JAVA / "java", "-jar", TOOLS / "lib/apksigner.jar",
    "verify", "--verbose", "--print-certs", signed, capture=True)
if f"Signer #1 certificate SHA-256 digest: {pinned_certificate}" not in verification:
    raise SystemExit("Signed APK certificate mismatch; refusing to publish.")
run(TOOLS / "zipalign", "-c", "-P", "16", "4", signed)
shutil.copyfile(signed, apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(DIST / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n")
report = {
    "package": PACKAGE, "version": VERSION, "versionCode": version()["code"],
    "minSdk": 28, "targetSdk": 35, "debuggable": args.debug,
    "signingCertificateSha256": pinned_certificate,
    "reportPublicKeySha256": config["fingerprint"],
    "apk": apk.name, "bytes": apk.stat().st_size, "sha256": digest,
    "androidRuntimeTested": False, "build": "AAPT2 / javac / D8 / zipalign / apksigner",
}
(DIST / "build-info.json").write_text(json.dumps(report, indent=2) + "\n")
print("\nBuilt:", apk, "\nBytes:", apk.stat().st_size, "\nSHA-256:", digest)
