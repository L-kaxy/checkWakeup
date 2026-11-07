#!/bin/bash
# Bounded JVM cryptography/serialization/policy tests using PUBLIC TEST fixtures.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
OUT="$ROOT/.cache/upload-tests"
mkdir -p "$OUT/classes"
python3 "$ROOT/scripts/generate-test-config.py" --output "$OUT/generated"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all -Werror -d "$OUT/classes" \
  "$ROOT"/app/src/main/java/dev/checkwakeup/core/*.java \
  "$ROOT"/app/src/main/java/dev/checkwakeup/TrustedServer.java \
  "$OUT"/generated/dev/checkwakeup/GeneratedServerConfig.java \
  "$OUT"/generated/dev/checkwakeup/core/GeneratedAppVersion.java \
  "$ROOT"/tests/UploadTests.java "$ROOT"/tests/UploadTriggerTests.java
"$JAVA_HOME/bin/java" -ea -cp "$OUT/classes" dev.checkwakeup.UploadTests \
  "$ROOT/tests/fixtures/test-public.pem" "$ROOT/tests/fixtures/test-private.pem" "$OUT"
"$JAVA_HOME/bin/java" -ea -cp "$OUT/classes" dev.checkwakeup.core.UploadTriggerTests
