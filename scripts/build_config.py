"""Shared, dependency-free build configuration. Never loads a private reporting key."""
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import textwrap
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
FIELDS = {
    "report_url": "CHECKWAKEUP_REPORT_URL",
    "kid": "CHECKWAKEUP_KID",
    "public_key_pem": "CHECKWAKEUP_PUBLIC_KEY_PEM",
}


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Configuration contains duplicate fields")
            result[key] = value
        return result
    value = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError("Configuration must be a JSON object")
    return value


def endpoint(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("CHECKWAKEUP_REPORT_URL is required")
    value = value.strip()
    try:
        uri = urlsplit(value)
        if (not value.isascii() or re.search(r"[\s\\\x00-\x1f\x7f]", value)
                or uri.scheme.lower() not in ("http", "https")
                or not uri.hostname or uri.username is not None or uri.password is not None
                or "?" in value or "#" in value or uri.port == 0
                or uri.netloc.endswith(":") or uri.path not in ("", "/", "/ingest/v1/reports")):
            raise ValueError
        if ":" not in uri.hostname:
            host = uri.hostname.removesuffix(".")
            if not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                       for label in host.split(".")):
                raise ValueError
    except ValueError:
        raise ValueError("Invalid report URL: use HTTP(S) root or /ingest/v1/reports") from None
    return value.rstrip("/") + "/ingest/v1/reports" if uri.path in ("", "/") else value


def public_key(pem):
    """Validate RSA SubjectPublicKeyInfo DER without an extra Python dependency."""
    if not isinstance(pem, str) or len(pem) > 32_768:
        raise ValueError("A PEM RSA public key is required (not a private key)")
    match = re.fullmatch(r"\s*-----BEGIN PUBLIC KEY-----\s*([A-Za-z0-9+/=\s]+)"
                         r"\s*-----END PUBLIC KEY-----\s*", pem)
    if not match:
        raise ValueError("Expected one SPKI PUBLIC KEY PEM block, never a private key")
    try:
        der = base64.b64decode(re.sub(r"\s", "", match[1]), validate=True)

        def take(data, tag):
            if len(data) < 2 or data[0] != tag:
                raise ValueError
            length, offset = data[1], 2
            if length & 128:
                count = length & 127
                if not 1 <= count <= 4 or len(data) < 2 + count or data[2] == 0:
                    raise ValueError
                length = int.from_bytes(data[2:2 + count], "big")
                if length < 128:
                    raise ValueError
                offset += count
            if offset + length > len(data):
                raise ValueError
            return data[offset:offset + length], data[offset + length:]

        spki, rest = take(der, 0x30)
        if rest:
            raise ValueError
        algorithm, rest = take(spki, 0x30)
        if algorithm != bytes.fromhex("06092a864886f70d0101010500"):
            raise ValueError
        bits, rest = take(rest, 0x03)
        if rest or not bits or bits[0] != 0:
            raise ValueError
        rsa, rest = take(bits[1:], 0x30)
        if rest:
            raise ValueError

        def integer(data):
            raw, tail = take(data, 0x02)
            if not raw or raw[0] & 128 or (len(raw) > 1 and raw[0] == 0 and raw[1] < 128):
                raise ValueError
            return int.from_bytes(raw, "big"), tail

        modulus, rest = integer(rsa)
        exponent, rest = integer(rest)
        if (rest or modulus.bit_length() < 3072 or modulus % 2 != 1
                or not 3 <= exponent < modulus or exponent % 2 != 1):
            raise ValueError
    except (ValueError, binascii.Error, IndexError):
        raise ValueError("Public key must be valid SPKI RSA, at least 3072 bits") from None
    body = "\n".join(textwrap.wrap(base64.b64encode(der).decode("ascii"), 64))
    return f"-----BEGIN PUBLIC KEY-----\n{body}\n-----END PUBLIC KEY-----", hashlib.sha256(der).hexdigest()


def validate(values):
    url = endpoint(values.get("report_url"))
    kid = values.get("kid")
    if not isinstance(kid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", kid):
        raise ValueError("CHECKWAKEUP_KID must contain 1-64 letters, digits, '_' or '-'")
    pem, fingerprint = public_key(values.get("public_key_pem"))
    return {"report_url": url, "kid": kid, "public_key_pem": pem, "fingerprint": fingerprint}


def load_config(env=None, root=ROOT):
    env = os.environ if env is None else env
    path = Path(env.get("CHECKWAKEUP_CONFIG", str(root / ".config/build.json"))).resolve()
    # CI must be configured by the workflow, never by a local fallback.
    values = {} if env.get("GITHUB_ACTIONS") == "true" else read_json(path) if path.exists() else {}
    if set(values) - {*FIELDS, "public_key_file"}:
        raise ValueError("Unknown build configuration field")
    if "public_key_pem" not in values and "public_key_file" in values and "CHECKWAKEUP_PUBLIC_KEY_PEM" not in env:
        values["public_key_pem"] = (path.parent / values["public_key_file"]).read_text(encoding="utf-8")
    for field, variable in FIELDS.items():
        if variable in env:
            values[field] = env[variable]  # Explicit blank is invalid, not a fallback.
    return validate(values)


def version(root=ROOT):
    value = read_json(root / "version.json")
    if (set(value) != {"name", "code"} or not isinstance(value["name"], str)
            or not re.fullmatch(r"\d+\.\d+\.\d+", value["name"])
            or type(value["code"]) is not int or not 1 <= value["code"] <= 2_100_000_000):
        raise ValueError("version.json requires a semantic name and positive Android versionCode")
    return value


def generate(output, config):
    output = Path(output)
    package = output / "dev/checkwakeup"
    package.mkdir(parents=True, exist_ok=True)
    fields = {"URL": config["report_url"], "KID": config["kid"], "PUBLIC_KEY": config["public_key_pem"]}
    source = "package dev.checkwakeup;\n\n// GENERATED. Do not commit.\nfinal class GeneratedServerConfig {\n"
    for key, value in fields.items():
        source += f"    static final String {key} = {json.dumps(value, ensure_ascii=True)};\n"
    source += "    private GeneratedServerConfig() {}\n}\n"
    target = package / "GeneratedServerConfig.java"
    target.write_text(source, encoding="utf-8")
    core = package / "core"
    core.mkdir(exist_ok=True)
    (core / "GeneratedAppVersion.java").write_text(
        "package dev.checkwakeup.core;\n\n// GENERATED. Do not commit.\n"
        "final class GeneratedAppVersion {\n"
        f"    static final String NAME = {json.dumps(version()['name'])};\n"
        "    private GeneratedAppVersion() {}\n}\n", encoding="utf-8")
    return target
