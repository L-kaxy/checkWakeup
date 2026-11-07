"""Finite build/secret-handling checks; no SDK, network, production config or credentials."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build_config import endpoint, generate, load_config, public_key, validate, version
from build_signing import expected_certificate, load_signing, prepare_ci_keystore


class BuildConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.pem = (ROOT / "tests/fixtures/test-public.pem").read_text()
        self.values = {"report_url": "https://example.test", "kid": "example-key",
                       "public_key_pem": self.pem}

    def test_valid_configuration_and_fingerprint(self):
        config = validate(self.values)
        self.assertEqual(config["report_url"], "https://example.test/ingest/v1/reports")
        self.assertEqual(len(config["fingerprint"]), 64)
        der = base64.b64decode("".join(self.pem.splitlines()[1:-1]))
        self.assertEqual(config["fingerprint"], hashlib.sha256(der).hexdigest())

    def test_missing_or_blank_each_field_fails(self):
        for key in self.values:
            for value in (None, ""):
                with self.subTest(key=key, blank=value), self.assertRaises(ValueError):
                    validate(dict(self.values, **{key: value}))

    def test_private_key_is_never_accepted(self):
        with self.assertRaises(ValueError):
            public_key((ROOT / "tests/fixtures/test-private.pem").read_text())

    def test_bad_pem_or_der_fails(self):
        for value in ("garbage", self.pem + self.pem, self.pem.replace("MIIB", "!!!!", 1),
                      "-----BEGIN PUBLIC KEY-----\nYWJj\n-----END PUBLIC KEY-----"):
            with self.subTest(kind=value[:16]), self.assertRaises(ValueError):
                public_key(value)

    def test_trailing_der_is_rejected(self):
        der = base64.b64decode("".join(self.pem.splitlines()[1:-1])) + b"\0"
        with self.assertRaises(ValueError):
            public_key("-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(der).decode()
                       + "\n-----END PUBLIC KEY-----")

    def test_short_rsa_modulus_is_rejected(self):
        def der(tag, data):
            size = len(data)
            length = size.to_bytes((size.bit_length() + 7) // 8, "big")
            return bytes([tag]) + (bytes([size]) if size < 128 else bytes([128 + len(length)]) + length) + data
        rsa = der(0x30, der(0x02, b"\0\x80" + b"\xff" * 255) + der(0x02, b"\x01\0\x01"))
        spki = der(0x30, bytes.fromhex("300d06092a864886f70d0101010500") + der(0x03, b"\0" + rsa))
        with self.assertRaises(ValueError):
            public_key("-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(spki).decode()
                       + "\n-----END PUBLIC KEY-----")

    def test_kid_limits_and_injection(self):
        validate(dict(self.values, kid="A" * 64))
        for value in ("A" * 65, "bad key", '"injection', "a\nb", 42):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate(dict(self.values, kid=value))

    def test_endpoint_rejections(self):
        for url in ("ftp://example.test", "http:///x", "https://user:pass@example.test",
                    "https://example.test?x=1", "https://example.test?", "https://example.test#",
                    "https://example.test/path", "https://example.test:0",
                    "https://example.test:65536", "https://bad_host.test", "https://example.test\n/x",
                    "https://example.test:", "https://example.test\\evil",
                    "https://bad..host", "https://-bad.example"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                endpoint(url)

    def test_http_and_ipv6_are_supported(self):
        self.assertEqual(endpoint(" http://127.0.0.1:16999/ "), "http://127.0.0.1:16999/ingest/v1/reports")
        self.assertEqual(endpoint("http://[::1]:16999"), "http://[::1]:16999/ingest/v1/reports")

    def test_environment_injection_without_local_files(self):
        env = dict(zip(("CHECKWAKEUP_REPORT_URL", "CHECKWAKEUP_KID", "CHECKWAKEUP_PUBLIC_KEY_PEM"),
                       self.values.values()), GITHUB_ACTIONS="true")
        self.assertEqual(load_config(env)["kid"], "example-key")

    def test_ci_never_falls_back_to_local_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.json"
            path.write_text(json.dumps(self.values))
            with self.assertRaises(ValueError):
                load_config({"GITHUB_ACTIONS": "true", "CHECKWAKEUP_CONFIG": str(path)})

    def test_local_file_relative_public_key_and_env_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "public.pem").write_text(self.pem)
            path = directory / "build.json"
            path.write_text(json.dumps({"report_url": "https://example.test", "kid": "local",
                                        "public_key_file": "public.pem"}))
            env = {"CHECKWAKEUP_CONFIG": str(path), "CHECKWAKEUP_KID": "override"}
            self.assertEqual(load_config(env)["kid"], "override")
            with self.assertRaises(ValueError):
                load_config(dict(env, CHECKWAKEUP_PUBLIC_KEY_PEM=""))

    def test_duplicate_json_fields_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.json"
            path.write_text('{"kid":"one","kid":"two"}')
            with self.assertRaises(ValueError):
                load_config({"CHECKWAKEUP_CONFIG": str(path)})

    def test_generated_fields_and_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = generate(tmp, validate(self.values)).read_text()
            self.assertIn('KID = "example-key"', source)
            self.assertIn('URL = "https://example.test/ingest/v1/reports"', source)
            self.assertIn("BEGIN PUBLIC KEY", source)
            self.assertNotIn("PRIVATE KEY", source)
            generated_version = Path(tmp) / "dev/checkwakeup/core/GeneratedAppVersion.java"
            self.assertIn(version()["name"], generated_version.read_text())

    def test_cli_does_not_log_accidentally_supplied_private_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, GITHUB_ACTIONS="true", CHECKWAKEUP_REPORT_URL="https://example.test",
                       CHECKWAKEUP_KID="test", CHECKWAKEUP_PUBLIC_KEY_PEM="PRIVATE SECRET CANARY")
            result = subprocess.run([sys.executable, str(ROOT / "scripts/generate-build-config.py"),
                                     "--output", tmp], env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("PRIVATE SECRET CANARY", result.stdout + result.stderr)
            self.assertFalse((Path(tmp) / "dev/checkwakeup/GeneratedServerConfig.java").exists())


class SigningConfigurationTests(unittest.TestCase):
    def test_expected_certificate_is_pinned(self):
        self.assertEqual(expected_certificate(), "5bce2f9dd58a82ed2be4622c72a0943aee5bdef914f7288e489c44103ef5bb21")

    def test_missing_signing_data_never_generates_a_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                load_signing({}, root)
            self.assertFalse((root / ".signing").exists())

    def test_signing_secrets_required_and_not_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = Path(tmp) / "key.p12"
            key.write_bytes(b"test bytes, not a certificate")
            env = {"GITHUB_ACTIONS": "true", "ANDROID_KEYSTORE_FILE": str(key),
                   "ANDROID_KEY_ALIAS": "alias", "ANDROID_KEYSTORE_PASSWORD": "STORE-CANARY",
                   "ANDROID_KEY_PASSWORD": "KEY-CANARY"}
            self.assertEqual(load_signing(env)["key_alias"], "alias")
            for name in env.keys() - {"GITHUB_ACTIONS"}:
                missing = dict(env)
                missing.pop(name)
                with self.subTest(name=name), self.assertRaises(ValueError) as caught:
                    load_signing(missing)
                self.assertNotIn("STORE-CANARY", str(caught.exception))
                self.assertNotIn("KEY-CANARY", str(caught.exception))

    def test_ci_restores_only_into_runner_temp_with_private_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = {"RUNNER_TEMP": tmp, "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"test-key").decode(),
                   "ANDROID_KEY_ALIAS": "alias", "ANDROID_KEYSTORE_PASSWORD": "pass",
                   "ANDROID_KEY_PASSWORD": "pass"}
            key = prepare_ci_keystore(env)
            self.assertEqual(key.read_bytes(), b"test-key")
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                prepare_ci_keystore(env)

    def test_bad_base64_never_writes_a_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = {"RUNNER_TEMP": tmp, "ANDROID_KEYSTORE_BASE64": "!bad!",
                   "ANDROID_KEY_ALIAS": "alias", "ANDROID_KEYSTORE_PASSWORD": "pass",
                   "ANDROID_KEY_PASSWORD": "pass"}
            with self.assertRaises(ValueError):
                prepare_ci_keystore(env)
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_ci_does_not_read_local_signing_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "signing.json"
            path.write_text("not even JSON")
            with self.assertRaises(ValueError):
                load_signing({"GITHUB_ACTIONS": "true", "CHECKWAKEUP_SIGNING_CONFIG": str(path)})


if __name__ == "__main__":
    unittest.main()
