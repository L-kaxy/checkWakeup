#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.toolchain/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
exec "${PYTHON:-python3}" "$ROOT/scripts/build-apk.py" "$@"
