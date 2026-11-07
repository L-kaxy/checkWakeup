#!/usr/bin/env python3
import sys
from build_signing import prepare_ci_keystore

try:
    prepare_ci_keystore()
except (ValueError, OSError, KeyError):
    print("Signing configuration invalid: check the four ANDROID_* Secrets. "
          "See GITHUB_RELEASE.md. Secret values were not logged.", file=sys.stderr)
    sys.exit(1)
print("Restored signing keystore in RUNNER_TEMP (0600).")
