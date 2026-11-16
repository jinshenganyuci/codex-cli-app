#!/usr/bin/python3
"""One-device SSH enrollment. Install root-owned; invoke only through fixed sudo.

No private keys or model credentials enter this service. The caller may register
one RSA public key; every target, path and account comes from root-owned state.
"""
from __future__ import annotations

import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import select
import stat
import struct
import sys
import tempfile
import time

STATE_ROOT = Path('/var/lib/codex-mobile-enrollment')
KEYS_ROOT = Path('/etc/ssh/codex-mobile-keys')
ENROLL_USER = re.compile(r'codex-join-[a-z0-9]{4,20}')
TARGET_USER = re.compile(r'[a-z_][a-z0-9_-]{0,31}')
INVITE_ID = re.compile(r'[A-Za-z0-9_-]{16,80}')
KEYS_HEADER = '# Managed by codex-mobile-enrollment. Do not edit.\n'
MAX_REQUEST = 16384


class EnrollmentError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def reject(code: str = 'invalid'):
    raise EnrollmentError(code)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            reject()
        result[key] = value
    return result


def read_request(fd: int, timeout: float = 15) -> dict:
    deadline = time.monotonic() + timeout
    data = bytearray()
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
            reject()
        block = os.read(fd, min(4096, MAX_REQUEST + 1 - len(data)))
        if not block:
            break
        data.extend(block)
        if len(data) > MAX_REQUEST:
            reject()
    if not data.endswith(b'\n') or data.count(b'\n') != 1 or b'\r' in data:
        reject()
    try:
        request = json.loads(data.decode('utf-8'), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        reject()
    if not isinstance(request, dict):
        reject()
    return request


def canonical_public_key(value: str) -> str:
    if not isinstance(value, str) or len(value) > 4096:
        reject()
    match = re.fullmatch(r'ssh-rsa ([A-Za-z0-9+/]+={0,2})', value)
    if not match:
        reject()
    try:
        encoded = match.group(1)
        raw = base64.b64decode(encoded, validate=True)
        if base64.b64encode(raw).decode('ascii') != encoded:
            reject()
        offset = 0

        def field():
            nonlocal offset
            if offset + 4 > len(raw):
                reject()
            size = struct.unpack('>I', raw[offset:offset + 4])[0]
            offset += 4
            if size > 1100 or offset + size > len(raw):
                reject()
            result = raw[offset:offset + size]
            offset += size
            return result

        def positive_integer():
            data = field()
            if not data or data[0] & 0x80:
                reject()
            if data[0] == 0 and (len(data) == 1 or data[1] < 0x80):
                reject()
            return int.from_bytes(data, 'big')

        if field() != b'ssh-rsa':
            reject()
        exponent, modulus = positive_integer(), positive_integer()
        if offset != len(raw) or exponent != 65537 or not 3072 <= modulus.bit_length() <= 8192 or modulus % 2 == 0:
            reject()
        return value
    except (ValueError, struct.error):
        reject()


def public_fingerprint(key: str) -> str:
    raw = base64.b64decode(key.split(' ')[1])
    return 'SHA256:' + base64.b64encode(hashlib.sha256(raw).digest()).decode().rstrip('=')


class Registry:
    """Paths/owner are injectable for isolated tests, never supplied by SSH input."""
    def __init__(self, state_root: Path = STATE_ROOT, keys_root: Path = KEYS_ROOT, owner: int = 0):
        self.root, self.keys, self.owner = Path(state_root), Path(keys_root), owner
        self.invites = self.root / 'invites'

    def _directory(self, path: Path, private: bool):
        s = path.lstat()
        if not stat.S_ISDIR(s.st_mode) or s.st_uid != self.owner or stat.S_IMODE(s.st_mode) & (0o077 if private else 0o022):
            reject('unavailable')

    def initialize(self):
        for path, mode, private in ((self.root, 0o700, True), (self.invites, 0o700, True), (self.keys, 0o755, False)):
            path.mkdir(mode=mode, exist_ok=True)
            self._directory(path, private)

    @contextlib.contextmanager
    def _lock(self):
        self._directory(self.root, True)
        self._directory(self.invites, True)
        self._directory(self.keys, False)
        fd = os.open(self.root / '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            s = os.fstat(fd)
            if not stat.S_ISREG(s.st_mode) or s.st_uid != self.owner or s.st_nlink != 1 or stat.S_IMODE(s.st_mode) & 0o077:
                reject('unavailable')
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    @staticmethod
    def _user(value: str):
        if not isinstance(value, str) or not ENROLL_USER.fullmatch(value):
            reject()

    def _read_file(self, path: Path, private: bool = True, limit: int = 65536) -> bytes:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            s = os.fstat(fd)
            if not stat.S_ISREG(s.st_mode) or s.st_uid != self.owner or s.st_nlink != 1 or s.st_size > limit or stat.S_IMODE(s.st_mode) & (0o077 if private else 0o022):
                reject('unavailable')
            with os.fdopen(fd, 'rb', closefd=False) as source:
                data = source.read(limit + 1)
            if len(data) > limit:
                reject('unavailable')
            return data
        finally:
            os.close(fd)

    def _load(self, user: str) -> dict:
        self._user(user)
        try:
            record = json.loads(self._read_file(self.invites / (user + '.json')), object_pairs_hook=unique_object)
            if not isinstance(record, dict) or record['schema_version'] != 1 or record['enrollment_user'] != user:
                reject('unavailable')
            if not TARGET_USER.fullmatch(record['target_user']) or not INVITE_ID.fullmatch(record['invite_id']):
                reject('unavailable')
            if any(type(record[k]) is not int or record[k] <= 0 for k in ('enrollment_uid', 'target_uid', 'issued_at', 'expires_at')):
                reject('unavailable')
            if record['state'] not in ('unused', 'binding', 'active', 'revoked'):
                reject('unavailable')
            key = record.get('public_key')
            if key is not None:
                canonical_public_key(key)
            if record['state'] in ('binding', 'active') and key is None:
                reject('unavailable')
            if record['state'] == 'unused' and key is not None:
                reject('unavailable')
            return record
        except FileNotFoundError:
            reject()
        except (KeyError, TypeError, ValueError):
            reject('unavailable')

    @staticmethod
    def _sync_directory(path: Path):
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def _atomic(self, path: Path, data: bytes, mode: int):
        fd, name = tempfile.mkstemp(prefix='.enrollment-', dir=path.parent)
        try:
            os.fchmod(fd, mode)
            with os.fdopen(fd, 'wb') as dest:
                fd = -1
                dest.write(data)
                dest.flush()
                os.fsync(dest.fileno())
            os.replace(name, path)
            self._sync_directory(path.parent)
        finally:
            if fd >= 0:
                os.close(fd)
            with contextlib.suppress(FileNotFoundError):
                os.unlink(name)

    def _write_record(self, record: dict):
        self._atomic(self.invites / (record['enrollment_user'] + '.json'), (json.dumps(record, sort_keys=True) + '\n').encode(), 0o600)

    def _write_keys(self, target: str):
        if not TARGET_USER.fullmatch(target):
            reject('unavailable')
        destination = self.keys / target
        if destination.exists() or destination.is_symlink():
            previous = self._read_file(destination, private=False, limit=1024 * 1024).decode()
            if not previous.startswith(KEYS_HEADER):
                reject('unavailable')
        rows = []
        for path in sorted(self.invites.glob('*.json')):
            record = self._load(path.stem)
            if record['target_user'] == target and record['state'] in ('binding', 'active'):
                rows.append('restrict ' + record['public_key'] + ' codex-mobile-invite:' + record['invite_id'] + '\n')
        self._atomic(destination, (KEYS_HEADER + ''.join(rows)).encode(), 0o644)

    def issue(self, enrollment_user: str, enrollment_uid: int, target_user: str, target_uid: int,
              invite_id: str, expires_at: int, now: int | None = None) -> dict:
        self._user(enrollment_user)
        now = int(time.time()) if now is None else now
        if not TARGET_USER.fullmatch(target_user) or not INVITE_ID.fullmatch(invite_id):
            reject()
        if any(type(v) is not int or v <= 0 for v in (enrollment_uid, target_uid, now, expires_at)) or expires_at <= now or enrollment_uid == target_uid:
            reject()
        record = dict(schema_version=1, enrollment_user=enrollment_user, enrollment_uid=enrollment_uid,
                      target_user=target_user, target_uid=target_uid, invite_id=invite_id, issued_at=now,
                      expires_at=expires_at, state='unused', public_key=None, fingerprint=None, bound_at=None)
        with self._lock():
            path = self.invites / (enrollment_user + '.json')
            if path.exists() or path.is_symlink():
                reject('unavailable')
            for existing in self.invites.glob('*.json'):
                if self._load(existing.stem)['invite_id'] == invite_id:
                    reject('unavailable')
            self._write_record(record)
        return record

    def enroll(self, enrollment_user: str, enrollment_uid: int, request: dict, now: int | None = None) -> dict:
        self._user(enrollment_user)
        if not isinstance(request, dict) or set(request) != {'schemaVersion', 'inviteId', 'publicKey'} or type(request['schemaVersion']) is not int or request['schemaVersion'] != 1:
            reject()
        key = canonical_public_key(request['publicKey'])
        now = int(time.time()) if now is None else now
        with self._lock():
            record = self._load(enrollment_user)
            if type(enrollment_uid) is not int or record['enrollment_uid'] != enrollment_uid or record['invite_id'] != request['inviteId']:
                reject()
            if record['state'] == 'revoked':
                reject('revoked')
            if record['public_key'] is not None and record['public_key'] != key:
                reject('claimed')
            # An already bound key can recover after the invitation expires.
            # Expiry only prevents binding an unclaimed invitation to a device.
            if record['state'] == 'unused':
                if now >= record['expires_at']:
                    reject('expired')
                record.update(state='binding', public_key=key, fingerprint=public_fingerprint(key), bound_at=now)
                self._write_record(record)
            self._write_keys(record['target_user'])
            if record['state'] != 'active':
                record['state'] = 'active'
                self._write_record(record)
            return {'ok': True, 'inviteId': record['invite_id']}

    def revoke(self, enrollment_user: str) -> dict:
        with self._lock():
            record = self._load(enrollment_user)
            if record['state'] != 'revoked':
                record.update(state='revoked', revoked_at=int(time.time()))
                self._write_record(record)
            self._write_keys(record['target_user'])
            return record

    def status(self, enrollment_user: str) -> dict:
        with self._lock():
            return self._load(enrollment_user)


def main() -> int:
    result = {'ok': False, 'error': 'unavailable'}
    try:
        if os.geteuid() != 0 or len(sys.argv) != 1:
            reject()
        user = os.environ.get('SUDO_USER', '')
        uid_text = os.environ.get('SUDO_UID', '')
        Registry._user(user)
        if not uid_text.isascii() or not uid_text.isdigit():
            reject()
        uid = int(uid_text)
        if uid <= 0 or pwd.getpwnam(user).pw_uid != uid:
            reject()
        registry = Registry()
        record = registry.status(user)
        if pwd.getpwnam(record['target_user']).pw_uid != record['target_uid']:
            reject('unavailable')
        result = registry.enroll(user, uid, read_request(sys.stdin.fileno()))
    except EnrollmentError as error:
        result = {'ok': False, 'error': error.code}
    except Exception:
        # Provider/filesystem errors must not expose paths, state or request data.
        pass
    print(json.dumps(result, separators=(',', ':')), flush=True)
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
