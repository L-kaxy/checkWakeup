#!/usr/bin/env python3
"""Generate Java build constants. Missing/invalid deployment configuration fails closed."""
import argparse
from pathlib import Path
import sys
from build_config import ROOT, generate, load_config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/generated/server")
    args = parser.parse_args()
    try:
        generate(args.output, load_config())
    except (ValueError, OSError, TypeError) as error:
        print(f"Build configuration invalid ({type(error).__name__}). "
              "Check CHECKWAKEUP_REPORT_URL, CHECKWAKEUP_KID and CHECKWAKEUP_PUBLIC_KEY_PEM; "
              "see GITHUB_RELEASE.md.", file=sys.stderr)
        return 1
    print("Generated build configuration (values not logged).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
