#!/bin/bash
# Project-local tools only; no Homebrew, global Java, Android Studio or emulator.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.toolchain"
mkdir -p "$TOOLS/downloads" "$TOOLS/jdk" "$TOOLS/android-sdk"

download() {
  [ -f "$2" ] || curl --fail --location --retry 3 --connect-timeout 30 "$1" -o "$2"
}

if [ ! -x "$TOOLS/jdk/Contents/Home/bin/java" ]; then
  download 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse' "$TOOLS/downloads/jdk.json"
  /usr/bin/python3 - "$TOOLS/downloads/jdk.json" "$TOOLS/downloads" <<'PY'
import hashlib, json, pathlib, subprocess, sys
package = json.loads(pathlib.Path(sys.argv[1]).read_text())[0]["binary"]["package"]
destination = pathlib.Path(sys.argv[2]) / "jdk17.tar.gz"
if not destination.exists():
    subprocess.run(["curl", "--fail", "--location", "--retry", "3", package["link"],
                    "-o", str(destination)], check=True)
assert hashlib.sha256(destination.read_bytes()).hexdigest() == package["checksum"], "JDK checksum mismatch"
PY
  tar -xzf "$TOOLS/downloads/jdk17.tar.gz" -C "$TOOLS/jdk" --strip-components=1
fi
export JAVA_HOME="$TOOLS/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Resolve only these two SDK packages from Google's signed-distribution repository
# metadata. No command-line manager, sources, emulator, system image or NDK.
download 'https://dl.google.com/android/repository/repository2-1.xml' "$TOOLS/downloads/android-repository.xml"
/usr/bin/python3 - "$TOOLS" <<'PY'
import hashlib, pathlib, subprocess, sys, xml.etree.ElementTree as ET, zipfile
root = pathlib.Path(sys.argv[1])
repo = ET.parse(root/"downloads/android-repository.xml").getroot()
for package, subdir, archive_root in [
    ("platforms;android-35", "platforms/android-35", None),
    ("build-tools;35.0.0", "build-tools/35.0.0", None),
]:
    target = root/"android-sdk"/subdir
    if (target/"source.properties").exists():
        continue
    entry = next(p for p in repo if p.attrib.get("path") == package)
    archives = entry.find("archives").findall("archive")
    archive = next(a for a in archives if a.findtext("host-os") in (None, "macosx"))
    complete = archive.find("complete")
    filename = complete.findtext("url")
    local = root/"downloads"/filename
    if not local.exists():
        subprocess.run(["curl", "--fail", "--location", "--retry", "3",
                        "https://dl.google.com/android/repository/"+filename, "-o", str(local)], check=True)
    checksum = complete.find("checksum")
    algorithm = checksum.attrib.get("type", "sha1")
    assert hashlib.new(algorithm, local.read_bytes()).hexdigest() == checksum.text.strip(), "SDK checksum mismatch"
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(local) as z:
        for item in z.infolist():
            parts = pathlib.PurePosixPath(item.filename).parts[1:]
            if not parts or ".." in parts:
                continue
            out = target.joinpath(*parts)
            if item.is_dir():
                out.mkdir(parents=True, exist_ok=True)
            else:
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(item))
                mode = item.external_attr >> 16
                if mode:
                    out.chmod(mode & 0o777)
print("Installed only platforms;android-35 and build-tools;35.0.0")
PY
printf 'sdk.dir=%s\n' "$TOOLS/android-sdk" > "$ROOT/local.properties"
"$JAVA_HOME/bin/java" -version
printf '\nReady. Tools are isolated in %s\n' "$TOOLS"
