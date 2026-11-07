#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
OUT="$ROOT/.cache/upload-tests"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:all -Werror -cp "$OUT/classes" \
  -d "$OUT/classes" "$ROOT/tests/TransportTests.java"
"$JAVA_HOME/bin/java" -ea -cp "$OUT/classes" dev.checkwakeup.TransportTests "$OUT/historical.jwe"
