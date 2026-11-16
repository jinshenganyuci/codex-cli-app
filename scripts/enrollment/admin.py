#!/usr/bin/python3
"""Root-only invitation administration; never prints invitation passwords.

Create the locked, unprivileged enrollment OS account and validate its sshd
Match/ForceCommand restrictions before using issue. This program never changes
sshd, users, groups, the existing Codex authorized_keys, or provider settings.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import subprocess
import sys
import time

import enroll


def ensure_enrollment_restrictions(user: str, uid: int):
    account = pwd.getpwnam(user)
    if uid <= 0 or account.pw_uid != uid or account.pw_shell not in ('/bin/sh', '/bin/bash'):
        raise RuntimeError('Enrollment account must have a working shell and non-root UID')
    groups = os.getgrouplist(user, account.pw_gid)
    if set(groups) != {account.pw_gid}:
        raise RuntimeError('Enrollment account must have no supplementary groups')
    home = Path(account.pw_dir)
    st = home.lstat()
    if home.is_symlink() or not home.is_dir() or st.st_uid != 0 or st.st_mode & 0o022:
        raise RuntimeError('Enrollment home must be a root-owned, non-writable directory')
    result = subprocess.run(['/usr/sbin/sshd', '-T', '-C', 'user=' + user + ',host=localhost,addr=127.0.0.1'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        raise RuntimeError('Cannot validate effective enrollment SSH settings')
    config = dict(line.split(' ', 1) for line in result.stdout.splitlines() if ' ' in line)
    expected = {'authenticationmethods': 'password', 'passwordauthentication': 'yes',
                'pubkeyauthentication': 'no', 'kbdinteractiveauthentication': 'no',
                'disableforwarding': 'yes', 'permittty': 'no', 'permituserrc': 'no',
                'forcecommand': 'sudo -n /usr/local/libexec/codex-mobile-enroll'}
    if any(config.get(key) != value for key, value in expected.items()):
        raise RuntimeError('Effective enrollment SSH restrictions do not match the fixed protocol')


def issue(args, registry):
    registry._user(args.user)
    if not enroll.TARGET_USER.fullmatch(args.target):
        raise RuntimeError('Invalid target account')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}', args.host) or not 1 <= args.port <= 65535:
        raise RuntimeError('Invalid public SSH endpoint')
    if not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', args.fingerprint):
        raise RuntimeError('Invalid pinned SSH fingerprint')
    if args.algorithm not in ('ecdsa-sha2-nistp256', 'ssh-ed25519', 'rsa-sha2-512', 'rsa-sha2-256'):
        raise RuntimeError('Unsupported SSH host key algorithm')
    if not 1 <= args.days <= 365:
        raise RuntimeError('Invitation expiry must be between 1 and 365 days')
    if not args.codex_path or '\x00' in args.codex_path or not args.workspace.startswith('/') or '\x00' in args.workspace:
        raise RuntimeError('Invalid target application paths')
    account, target = pwd.getpwnam(args.user), pwd.getpwnam(args.target)
    if target.pw_uid == 0 or account.pw_uid == target.pw_uid:
        raise RuntimeError('Target account must be distinct and non-root')
    ensure_enrollment_restrictions(args.user, account.pw_uid)
    output = Path(args.output).absolute()
    registry._directory(output.parent, private=True)
    if output.exists() or output.is_symlink():
        raise RuntimeError('Refusing to replace an existing invitation output')
    password = secrets.token_urlsafe(36)
    invite_id = secrets.token_urlsafe(24)
    now = int(time.time())
    registry.issue(args.user, account.pw_uid, args.target, target.pw_uid, invite_id,
                   now + args.days * 86400, now=now)
    try:
        result = subprocess.run(['/usr/sbin/chpasswd'], input=(args.user + ':' + password + '\n').encode(),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('Failed to set enrollment password')
        common = dict(host=args.host, port=args.port, fingerprint=args.fingerprint,
                      hostKeyAlgorithm=args.algorithm)
        bootstrap = dict(schemaVersion=1, inviteId=invite_id,
                         enrollment=dict(common, user=args.user, password=password),
                         connection=dict(common, user=args.target, name=args.name, codexPath=args.codex_path),
                         workspace=args.workspace)
        registry._atomic(output, (json.dumps(bootstrap, ensure_ascii=False) + '\n').encode(), 0o600)
    except Exception:
        try:
            registry.revoke(args.user)
        finally:
            subprocess.run(['/usr/sbin/usermod', '-L', args.user], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        raise
    return dict(ok=True, user=args.user, inviteId=invite_id, state='unused', expires_at=now + args.days * 86400,
                output=str(output))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('init')
    make = sub.add_parser('issue')
    make.add_argument('--user', required=True)
    make.add_argument('--target', default='codex')
    make.add_argument('--host', required=True)
    make.add_argument('--port', type=int, required=True)
    make.add_argument('--fingerprint', required=True)
    make.add_argument('--algorithm', default='ecdsa-sha2-nistp256')
    make.add_argument('--codex-path', default='~/.local/bin/codex')
    make.add_argument('--workspace', default='/home/codex')
    make.add_argument('--name', default='Codex')
    make.add_argument('--days', type=int, default=90)
    make.add_argument('--output', required=True, help='New private JSON file in an existing root-owned 0700 directory')
    for command in ('status', 'revoke'):
        sub.add_parser(command).add_argument('--user', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Run as root')
    registry = enroll.Registry()
    try:
        if args.command == 'init':
            registry.initialize()
            result = dict(ok=True)
        elif args.command == 'issue':
            result = issue(args, registry)
        elif args.command == 'status':
            result = registry.status(args.user)
        else:
            try:
                result = registry.revoke(args.user)
            finally:
                subprocess.run(['/usr/sbin/usermod', '-L', args.user], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except enroll.EnrollmentError as error:
        print(json.dumps(dict(ok=False, error=error.code)), file=sys.stderr)
    except RuntimeError as error:
        # Only constant validation messages raised above reach this branch.
        print('Enrollment administration failed: ' + str(error), file=sys.stderr)
    except Exception as error:
        # OS/provider exceptions can contain paths or supplied input.
        print('Enrollment administration failed: ' + type(error).__name__, file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
