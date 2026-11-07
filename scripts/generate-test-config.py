#!/usr/bin/env python3
"""Public, non-deployable fixtures for secret-free CI tests. Never loads local/env config."""
import argparse
from pathlib import Path
from build_config import ROOT, generate, validate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
generate(args.output, validate({
    "report_url": "https://example.test/ingest/v1/reports",
    "kid": "ingest-2026-01",
    "public_key_pem": (ROOT / "tests/fixtures/test-public.pem").read_text(),
}))
