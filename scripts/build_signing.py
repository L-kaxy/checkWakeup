"""Signing input handling; no key generation and no password command-line arguments."""
import base64
import binascii
import os
from pathlib import Path
import re
from build_config import ROOT, read_json


def load_signing(env=None, root=ROOT):
    env = os.environ if env is None else env
    path = Path(env.get("CHECKWAKEUP_SIGNING_CONFIG", str(root / ".config/signing.json"))).resolve()
    values = {} if env.get("GITHUB_ACTIONS") == "true" else read_json(path) if path.exists() else {}
    fields = {
        "keystore_file": "ANDROID_KEYSTORE_FILE", "key_alias": "ANDROID_KEY_ALIAS",
        "store_password": "ANDROID_KEYSTORE_PASSWORD", "key_password": "ANDROID_KEY_PASSWORD",
    }
    if set(values) - set(fields):
        raise ValueError("Unknown signing configuration field")
    if values.get("keystore_file"):
        values["keystore_file"] = str((path.parent / values["keystore_file"]).resolve())
    for field, variable in fields.items():
        if variable in env:
            values[field] = env[variable]
        if not isinstance(values.get(field), str) or not values[field]:
            raise ValueError(f"{variable} is required; no replacement key will be generated")
    key = Path(values["keystore_file"])
    if not key.is_file():
        raise ValueError("Signing keystore is missing; restore the existing key")
    values["keystore_file"] = str(key.resolve())
    return values


def expected_certificate(root=ROOT):
    digest = (root / "config/signing-certificate.sha256").read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid pinned signing certificate SHA-256")
    return digest


def prepare_ci_keystore(env=None):
    env = os.environ if env is None else env
    for field in ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_PASSWORD", "ANDROID_KEY_ALIAS"):
        if not env.get(field):
            raise ValueError(f"Missing GitHub Secret: {field}")
    # Keep secret material outside the checkout and build outputs.
    directory = Path(env["RUNNER_TEMP"]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "checkwakeup-signing.p12"
    try:
        data = base64.b64decode(re.sub(r"\s", "", env["ANDROID_KEYSTORE_BASE64"]), validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("ANDROID_KEYSTORE_BASE64 is not valid Base64") from None
    if not 1 <= len(data) <= 16 * 1024 * 1024:
        raise ValueError("Invalid keystore size")
    # O_EXCL avoids following/reusing an existing path.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
    return path
