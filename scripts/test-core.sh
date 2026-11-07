#!/bin/bash
# JVM logic tests only. No Android SDK, device, emulator or test dependencies.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
OUT="$ROOT/.cache/core-tests"
mkdir -p "$OUT"
python3 "$ROOT/scripts/generate-test-config.py" --output "$OUT/generated"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all -Werror -d "$OUT" \
  "$ROOT"/app/src/main/java/dev/checkwakeup/core/*.java \
  "$OUT"/generated/dev/checkwakeup/core/GeneratedAppVersion.java \
  "$ROOT"/tests/CoreTests.java
"$JAVA_HOME/bin/java" -ea -cp "$OUT" dev.checkwakeup.core.CoreTests
