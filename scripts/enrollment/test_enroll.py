#!/usr/bin/env python3
"""Run on Debian: python3 -m unittest discover -s scripts/enrollment -v."""
from __future__ import annotations

import base64
import json
import multiprocessing
import os
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest.mock import patch

import enroll


NOW = 1_800_000_000
USER = "codex-join-test"
UID = 2001
INVITE = "test-invite-123456"


def rsa_public(seed=1, bits=3072):
    def field(value):
        return struct.pack(">I", len(value)) + value

    def mpint(value):
        raw = value.to_bytes((value.bit_length() + 7) // 8, "big")
        return field((b"\0" if raw[0] & 0x80 else b"") + raw)

    # Valid wire representation; no private key is needed to test registration.
    modulus = (1 << (bits - 1)) | (1 << (bits // 2)) | (seed << 1) | 1
    blob = field(b"ssh-rsa") + mpint(65537) + mpint(modulus)
    return "ssh-rsa " + base64.b64encode(blob).decode("ascii")


def request(key):
    return {"schemaVersion": 1, "inviteId": INVITE, "publicKey": key}


def concurrent_enroll(state, keys, key, gate, output):
    registry = enroll.Registry(Path(state), Path(keys), owner=os.geteuid())
    gate.wait(10)
    try:
        registry.enroll(USER, UID, request(key), now=NOW)
        output.put("ok")
    except enroll.EnrollmentError as error:
        output.put(error.code)


class EnrollmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.registry = enroll.Registry(root / "state", root / "keys", owner=os.geteuid())
        self.registry.initialize()
        self.registry.issue(USER, UID, "codex", 1000, INVITE, NOW + 3600, now=NOW)
        self.key = rsa_public()
        self.authorized = root / "keys" / "codex"

    def enroll(self, key=None, now=NOW):
        return self.registry.enroll(USER, UID, request(key or self.key), now=now)

    def assert_error(self, code, operation):
        with self.assertRaises(enroll.EnrollmentError) as raised:
            operation()
        self.assertEqual(raised.exception.code, code)

    def test_same_key_is_idempotent_and_second_key_is_rejected(self):
        expected = {"ok": True, "inviteId": INVITE}
        self.assertEqual(self.enroll(), expected)
        first = self.authorized.read_bytes()
        self.assertEqual(self.enroll(), expected)
        self.assertEqual(self.authorized.read_bytes(), first)
        self.assertEqual(first.decode().count(self.key), 1)
        self.assertIn("restrict ", first.decode())
        self.assert_error("claimed", lambda: self.enroll(rsa_public(2)))
        self.assertEqual(self.authorized.read_bytes(), first)

    def test_two_processes_cannot_claim_different_keys(self):
        context = multiprocessing.get_context("spawn")
        gate, output = context.Event(), context.Queue()
        root = Path(self.temp.name)
        processes = [context.Process(target=concurrent_enroll, args=(
            str(root / "state"), str(root / "keys"), rsa_public(n), gate, output,
        )) for n in (1, 2)]
        for process in processes:
            process.start()
        gate.set()
        try:
            outcomes = [output.get(timeout=15) for _ in processes]
            for process in processes:
                process.join(15)
                self.assertEqual(process.exitcode, 0)
            self.assertCountEqual(outcomes, ["ok", "claimed"])
            recorded = self.registry.status(USER)["public_key"]
            self.assertEqual(self.authorized.read_text().count(recorded), 1)
            self.assertEqual(sum(key in self.authorized.read_text()
                                 for key in (rsa_public(1), rsa_public(2))), 1)
        finally:
            for process in processes:
                if process.is_alive():
                    process.terminate()
                    process.join(5)
            output.close()

    def test_retry_after_binding_record_but_before_authorized_file(self):
        with patch.object(self.registry, "_write_keys", side_effect=OSError("simulated")):
            with self.assertRaises(Exception):
                self.enroll()
        self.assertEqual(self.registry.status(USER)["state"], "binding")
        self.assert_error("claimed", lambda: self.enroll(rsa_public(2)))
        self.assertEqual(self.enroll()["ok"], True)
        self.assertEqual(self.registry.status(USER)["state"], "active")
        self.assertIn(self.key, self.authorized.read_text())

    def test_retry_after_authorized_file_but_before_active_record(self):
        original = self.registry._write_record

        def crash_on_active(record):
            if record["state"] == "active":
                raise OSError("simulated")
            original(record)

        with patch.object(self.registry, "_write_record", side_effect=crash_on_active):
            with self.assertRaises(Exception):
                self.enroll()
        self.assertIn(self.key, self.authorized.read_text())
        self.assertEqual(self.registry.status(USER)["state"], "binding")
        self.assertTrue(self.enroll()["ok"])
        self.assertEqual(self.authorized.read_text().count(self.key), 1)

    def test_revocation_cannot_be_undone_by_retry(self):
        self.enroll()
        self.registry.revoke(USER)
        self.assertEqual(self.registry.status(USER)["state"], "revoked")
        self.assertNotIn(self.key, self.authorized.read_text())
        self.assert_error("revoked", self.enroll)
        self.assertNotIn(self.key, self.authorized.read_text())

    def test_expired_unused_invite_and_wrong_identity_cannot_bind(self):
        self.assert_error("expired", lambda: self.enroll(now=NOW + 3601))
        with self.assertRaises(enroll.EnrollmentError):
            self.registry.enroll(USER, UID + 1, request(self.key), now=NOW)
        wrong_invite = dict(request(self.key), inviteId="another-invite")
        with self.assertRaises(enroll.EnrollmentError):
            self.registry.enroll(USER, UID, wrong_invite, now=NOW)
        self.assertEqual(self.registry.status(USER)["state"], "unused")

    def test_invalid_public_keys_do_not_claim_invitation(self):
        invalid = [rsa_public(bits=2048), "restrict " + self.key,
                   self.key + "\n" + rsa_public(2), self.key + " comment",
                   "ssh-rsa !!!!", "ssh-ed25519 " + self.key.split()[1]]
        for key in invalid:
            with self.subTest(key=key[:30]):
                with self.assertRaises(enroll.EnrollmentError):
                    self.enroll(key)
                self.assertEqual(self.registry.status(USER)["state"], "unused")


class RequestTests(unittest.TestCase):
    def read(self, data):
        with tempfile.TemporaryFile() as file:
            file.write(data)
            file.seek(0)
            return enroll.read_request(file.fileno(), timeout=0.1)

    def test_complete_single_line(self):
        value = request(rsa_public())
        self.assertEqual(self.read(json.dumps(value).encode() + b"\n"), value)

    def test_malformed_duplicate_truncated_and_overlong_requests(self):
        invalid = [b"{}", b"{}\n{}\n", b"{}\n\n", b"{\n}\n", b"[]\n",
                   b'{"schemaVersion":1,"schemaVersion":2}\n',
                   b"x" * 16385, b"\xff\n"]
        for data in invalid:
            with self.subTest(prefix=data[:30]):
                with self.assertRaises(enroll.EnrollmentError) as raised:
                    self.read(data)
                self.assertEqual(raised.exception.code, "invalid")

    def test_timeout_without_eof(self):
        reader, writer = os.pipe()
        try:
            os.write(writer, b"{}\n")
            start = time.monotonic()
            with self.assertRaises(enroll.EnrollmentError):
                enroll.read_request(reader, timeout=0.05)
            self.assertLess(time.monotonic() - start, 1.0)
        finally:
            os.close(reader)
            os.close(writer)


if __name__ == "__main__":
    unittest.main()
