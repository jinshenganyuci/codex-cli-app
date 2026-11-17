#!/usr/bin/env python3
"""Verify and atomically publish an Android release over authenticated SSH.

Run on the Debian build VM. Forward a dedicated SSH agent from the Mac; do not
copy a private key to this VM. The caller must provide the verified host-key
file and an existing signed baseline APK. No signing secrets are needed here.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PACKAGE = 'com.nanci.codexremote'
APK_LIMIT = 100 * 1024 * 1024
MANIFEST_LIMIT = 64 * 1024


def android_tool(name: str) -> str:
    configured = os.environ.get('CODEX_ANDROID_BUILD_TOOLS')
    if configured:
        return str(Path(configured) / name)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        return str(Path(sdk) / 'build-tools' / '35.0.0' / name)
    return name  # Resolve from PATH, or supply an explicit CLI option.

# This is a fixed program; the release metadata is sent as JSON on stdin and the
# payload follows as raw bytes.  No private key or model credential is sent.
REMOTE = r'''import contextlib, fcntl, hashlib, json, os, pathlib, re, stat, sys, tempfile
P = pathlib.Path.home() / '.local/share/codex-mobile/updates'

def fail(message):
    raise ValueError(message)

def owned_directory(p, private=False):
    if p.is_symlink() or not p.is_dir(): fail('unsafe update directory')
    st=p.stat()
    if st.st_uid != os.getuid() or (private and stat.S_IMODE(st.st_mode) & 0o077):
        fail('update directory ownership or write permissions are unsafe')

def digest_file(path):
    if path.is_symlink() or not path.is_file(): fail('release file is missing or unsafe')
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024), b''): h.update(block)
    return h.hexdigest(), path.stat().st_size

def atomic_bytes(path, data):
    fd,tmp=tempfile.mkstemp(prefix='.publish-', dir=path.parent)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'wb') as f:
            f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(tmp,path)
    finally:
        with contextlib.suppress(FileNotFoundError): os.unlink(tmp)

def fsync_dir(path):
    fd=os.open(path,os.O_RDONLY | getattr(os,'O_DIRECTORY',0))
    try: os.fsync(fd)
    finally: os.close(fd)

try:
    header=sys.stdin.buffer.readline(65537)
    if not header.endswith(b'\n') or len(header)>65536: fail('invalid metadata header')
    m=json.loads(header)
    digest=m.get('sha256',''); signer=m.get('signer','')
    if m.get('packageName')!='com.nanci.codexremote': fail('unexpected package')
    if not re.fullmatch('[0-9a-f]{64}',digest) or not re.fullmatch('[0-9a-f]{64}',signer): fail('invalid checksum')
    if signer!=m.get('baselineSigner'): fail('signer differs from verified baseline')
    if type(m.get('versionCode')) is not int or not 0<m['versionCode']<=2147483647 or m['versionCode']<=m.get('baselineVersionCode',0): fail('version must exceed baseline')
    if type(m.get('sizeBytes')) is not int or not 0<m['sizeBytes']<=100*1024*1024: fail('invalid file size')
    if type(m.get('minSdk')) is not int or not 0<m['minSdk']<=10000: fail('invalid minimum SDK')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}',m.get('versionName','')) or '..' in m['versionName']: fail('invalid version name')
    if not isinstance(m.get('releaseNotes'),list) or len(m['releaseNotes'])>20 or not all(isinstance(x,str) and len(x)<=1000 for x in m['releaseNotes']): fail('invalid release notes')
    # Existing parents are checked; newly created directories stay private.
    root=pathlib.Path.home()
    owned_directory(root,private=True)
    for child in ('.local','share','codex-mobile','updates'):
        root=root/child
        root.mkdir(mode=0o700,exist_ok=True)
        owned_directory(root,private=(child=='updates'))
    releases=P/'releases'; releases.mkdir(mode=0o700,exist_ok=True); owned_directory(releases,private=True)
    lock=P/'.publish.lock'
    flags=os.O_RDWR|os.O_CREAT|getattr(os,'O_NOFOLLOW',0)
    lockfd=os.open(lock,flags,0o600)
    with os.fdopen(lockfd,'r+') as lockfile:
        if os.fstat(lockfile.fileno()).st_uid!=os.getuid(): fail('unsafe lock owner')
        fcntl.flock(lockfile,fcntl.LOCK_EX)
        latest=P/'latest.json'; old=None
        if latest.exists() or latest.is_symlink():
            if latest.is_symlink() or not latest.is_file(): fail('unsafe latest manifest')
            oldbytes=latest.read_bytes()
            if len(oldbytes)>65536: fail('manifest too large')
            old=json.loads(oldbytes)
            if old.get('schemaVersion')!=1 or old.get('packageName')!=m['packageName'] or type(old.get('versionCode')) is not int: fail('invalid existing manifest')
            oldfile=old.get('apkFile','')
            if not re.fullmatch(r'releases/[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.apk',oldfile) or '..' in oldfile: fail('invalid existing APK path')
            oldpath=P/oldfile
            oldside=pathlib.Path(str(oldpath)+'.signer-sha256')
            if oldside.is_symlink() or not oldside.is_file() or oldside.read_text().strip()!=signer: fail('existing release signer mismatch')
            oldhash,oldsize=digest_file(oldpath)
            if oldhash!=old.get('sha256') or oldsize!=old.get('sizeBytes'): fail('existing APK failed integrity check')
            if m['versionCode']<old['versionCode']: fail('refusing version rollback')
            if m['versionCode']==old['versionCode'] and (digest!=oldhash or m['versionName']!=old.get('versionName') or m['minSdk']!=old.get('minSdk')):
                fail('version code already exists with different release')
        fd,tmp=tempfile.mkstemp(prefix='.incoming-',dir=releases)
        try:
            os.fchmod(fd,0o600); h=hashlib.sha256(); count=0
            with os.fdopen(fd,'wb') as f:
                for block in iter(lambda:sys.stdin.buffer.read(1024*1024),b''):
                    count+=len(block)
                    if count>m['sizeBytes']: fail('payload exceeds advertised size')
                    h.update(block); f.write(block)
                f.flush(); os.fsync(f.fileno())
            if count!=m['sizeBytes'] or h.hexdigest()!=digest: fail('uploaded APK integrity mismatch')
            if old and m['versionCode']==old['versionCode']:
                print(json.dumps({'status':'unchanged','versionCode':m['versionCode'],'sha256':digest})); sys.exit(0)
            filename='Codex-'+m['versionName']+'-code'+str(m['versionCode'])+'-'+digest[:16]+'.apk'
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.apk',filename) or '..' in filename: fail('invalid client APK path')
            manifest={'schemaVersion':1,'packageName':m['packageName'],'versionCode':m['versionCode'],
              'versionName':m['versionName'],'publishedAt':m['publishedAt'],'releaseNotes':m['releaseNotes'],
              'apkFile':'releases/'+filename,'sizeBytes':count,'sha256':digest,'minSdk':m['minSdk']}
            manifest_bytes=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
            if len(manifest_bytes)>65536: fail('manifest exceeds client limit')
            target=releases/filename; side=pathlib.Path(str(target)+'.signer-sha256')
            try:
                os.link(tmp,target,follow_symlinks=False)
            except FileExistsError:
                if digest_file(target)!=(digest,count): fail('release filename collision')
            if side.exists() or side.is_symlink():
                if side.is_symlink() or not side.is_file() or side.read_text().strip()!=signer: fail('release signer collision')
            else:
                atomic_bytes(side,(signer+'\n').encode())
            fsync_dir(releases)
            if old is not None: atomic_bytes(P/'previous.json',oldbytes)
            # Commit latest last, after APK, signer metadata, and previous manifest.
            atomic_bytes(latest,manifest_bytes)
            fsync_dir(P)
            print(json.dumps({'status':'published','versionCode':m['versionCode'],'apkFile':manifest['apkFile'],'sha256':digest}))
        finally:
            with contextlib.suppress(FileNotFoundError): os.unlink(tmp)
except Exception as e:
    print('release publication failed: '+str(e),file=sys.stderr)
    sys.exit(1)
'''


def run(*args: str) -> str:
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if p.returncode:
        raise RuntimeError(f'{Path(args[0]).name} failed: {p.stderr.strip()[:1000]}')
    return p.stdout


def inspect(apk: Path, aapt: str, apksigner: str) -> dict:
    if apk.is_symlink() or not apk.is_file():
        raise ValueError(f'APK does not exist or is a symlink: {apk}')
    if not 0 < apk.stat().st_size <= APK_LIMIT:
        raise ValueError('APK size is outside the 100 MiB client limit')
    # A private first-install APK contains a bearer enrollment invitation.
    # Never distribute it through the shared update channel, even if its
    # package, version, and signing certificate otherwise match a release.
    try:
        with zipfile.ZipFile(apk) as archive:
            if 'assets/codex-bootstrap.json' in archive.namelist():
                raise ValueError('private enrollment APK cannot be published to the shared update channel')
    except zipfile.BadZipFile as error:
        raise ValueError('APK is not a valid ZIP archive') from error
    badging = run(aapt, 'dump', 'badging', str(apk))
    # Release APKs must not grant debug access or be marked test-only.  These
    # Android attributes live on <application>; missing means the platform's
    # default false.  Fail closed if a present value cannot be decoded.
    if re.search(r'^application-debuggable\s*$', badging, re.MULTILINE):
        raise ValueError('debuggable APK cannot be published')
    manifest = run(aapt, 'dump', 'xmltree', str(apk), 'AndroidManifest.xml')
    for attr in ('debuggable', 'testOnly'):
        values = re.findall(r'^\s*A: android:' + attr + r'(?:\([^)]*\))?=(.+)$',
                            manifest, re.MULTILINE)
        if len(values) > 1:
            raise ValueError(f'duplicate android:{attr} manifest attributes')
        if values:
            value = re.fullmatch(r'\(type 0x12\)0x([0-9a-fA-F]+)\s*', values[0])
            if not value:
                raise ValueError(f'cannot determine android:{attr} value')
            if int(value.group(1), 16):
                raise ValueError(f'android:{attr}=true APK cannot be published')
    first = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    def field(name: str) -> str:
        match = re.search(r'\b' + re.escape(name) + r"='([^']*)'", first)
        if not match:
            raise ValueError(f'missing {name} in APK metadata')
        return match.group(1)
    package, code, version = field('name'), int(field('versionCode')), field('versionName')
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.MULTILINE)
    if package != PACKAGE or not minimum or not 0 < code <= 2147483647 or not 0 < int(minimum.group(1)) <= 10000:
        raise ValueError('APK package, version code, or minimum SDK is invalid')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', version) or '..' in version:
        raise ValueError('APK versionName contains unsupported characters')
    cert = run(apksigner, 'verify', '--verbose', '--print-certs', str(apk))
    count = re.search(r'^Number of signers:\s*(\d+)\s*$', cert, re.MULTILINE)
    signer = re.search(r'^Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$', cert, re.MULTILINE)
    if not count or int(count.group(1)) != 1 or not signer:
        raise ValueError('APK must verify and have exactly one signing certificate')
    signature = signer.group(1).replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', signature):
        raise ValueError('invalid signing certificate digest')
    h = hashlib.sha256()
    with apk.open('rb') as f:
        for chunk in iter(lambda: f.read(1024*1024), b''):
            h.update(chunk)
    return {'packageName': package, 'versionCode': code, 'versionName': version,
            'minSdk': int(minimum.group(1)), 'signer': signature,
            'sizeBytes': apk.stat().st_size, 'sha256': h.hexdigest()}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--apk', required=True, type=Path)
    ap.add_argument('--baseline-apk', required=True, type=Path, help='prior verified release APK')
    ap.add_argument('--known-hosts', required=True, type=Path, help='trusted SSH host keys')
    ap.add_argument('--target', required=True, help='SSH destination user@host; no server is built in')
    ap.add_argument('--note', action='append', default=[], help='release note; can be repeated')
    ap.add_argument('--aapt', default=android_tool('aapt'))
    ap.add_argument('--apksigner', default=android_tool('apksigner'))
    args = ap.parse_args()
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]*@[A-Za-z0-9][A-Za-z0-9.-]*', args.target):
        raise ValueError('invalid SSH target')
    if args.known_hosts.is_symlink() or not args.known_hosts.is_file():
        raise ValueError('trusted known-hosts file not found')
    if len(args.note)>20:
        raise ValueError('at most 20 release notes are allowed')
    for note in args.note:
        if len(note)>1000 or any(c in note for c in '\r\n\x00'):
            raise ValueError('release notes must be short single lines')
    # Freeze both inputs before verification, so the bytes verified by
    # apksigner are the same bytes hashed and transmitted.
    for path in (args.apk, args.baseline_apk):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'APK does not exist or is a symlink: {path}')
    with tempfile.TemporaryDirectory(prefix='codex-release-') as temp_dir:
        payload=Path(temp_dir)/'payload.apk'
        baseline_path=Path(temp_dir)/'baseline.apk'
        shutil.copyfile(args.apk,payload)
        shutil.copyfile(args.baseline_apk,baseline_path)
        return publish(args,payload,baseline_path)


def publish(args: argparse.Namespace, payload: Path, baseline_path: Path) -> int:
    new, baseline = inspect(payload,args.aapt,args.apksigner), inspect(baseline_path,args.aapt,args.apksigner)
    if new['signer'] != baseline['signer']:
        raise ValueError('new APK signer differs from baseline APK')
    if new['versionCode'] <= baseline['versionCode']:
        raise ValueError('new APK version must exceed baseline version')
    new.update(baselineSigner=baseline['signer'],baselineVersionCode=baseline['versionCode'],
               releaseNotes=args.note,publishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    filename='Codex-'+new['versionName']+'-code'+str(new['versionCode'])+'-'+new['sha256'][:16]+'.apk'
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.apk',filename) or '..' in filename:
        raise ValueError('APK release filename is incompatible with the client')
    manifest={'schemaVersion':1,'packageName':new['packageName'],'versionCode':new['versionCode'],
              'versionName':new['versionName'],'publishedAt':new['publishedAt'],'releaseNotes':new['releaseNotes'],
              'apkFile':'releases/'+filename,'sizeBytes':new['sizeBytes'],'sha256':new['sha256'],'minSdk':new['minSdk']}
    if len((json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()) > MANIFEST_LIMIT:
        raise ValueError('manifest exceeds 64 KiB client limit')
    header=(json.dumps(new,ensure_ascii=False)+'\n').encode()
    if len(header)>MANIFEST_LIMIT:
        raise ValueError('release metadata exceeds 64 KiB')
    cmd=['ssh','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
         '-o','UserKnownHostsFile='+str(args.known_hosts.resolve()),'-o','ConnectTimeout=10',
         '-o','ServerAliveInterval=15','-o','ServerAliveCountMax=4',args.target,
         'python3 -c '+shlex.quote(REMOTE)]
    proc=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        try:
            assert proc.stdin is not None
            proc.stdin.write(header)
            with payload.open('rb') as f: shutil.copyfileobj(f,proc.stdin,1024*1024)
            proc.stdin.close()
        except BrokenPipeError:
            pass
        proc.stdin=None
        out,err=proc.communicate(timeout=120)
    except BaseException:
        proc.terminate()
        proc.wait(timeout=10)
        raise
    if proc.returncode:
        raise RuntimeError('SSH publication failed: '+err.decode(errors='replace').strip()[:1500])
    result=json.loads(out)
    if result.get('sha256')!=new['sha256'] or result.get('versionCode')!=new['versionCode']:
        raise RuntimeError('remote confirmation did not match APK')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 0


if __name__=='__main__':
    try: sys.exit(main())
    except (ValueError,RuntimeError,OSError,subprocess.TimeoutExpired) as e:
        print(f'error: {e}',file=sys.stderr); sys.exit(1)
