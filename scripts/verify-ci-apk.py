#!/usr/bin/env python3
"""Verify the signed CI artifact before publishing only its APK and public metadata."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

SIGNER = '12c9858dc73f6bfab725bb10ec341e3a203344f9bd8f1cea0b3874106ba25d3e'
apk = Path(sys.argv[1])
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert package and package[1] == 'com.nanci.codexremote', 'Unexpected package'
code, version = int(package[2]), package[3]
assert code >= 12 and re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?', version), 'Invalid release version'
assert 'application-debuggable' not in badging, 'Debug APK is not publishable'
manifest = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
assert not re.search(r'android:testOnly[^\n]*=(?:\(type 0x12\))?0xffffffff', manifest), 'Test-only APK is not publishable'
assert ' E: instrumentation ' not in manifest and not re.search(r'^\s*E: instrumentation\b', manifest, re.M), 'Test runner in production APK'
cert = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
signers = re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', cert)
assert signers == [SIGNER], 'APK does not use the established release signing certificate'
with zipfile.ZipFile(apk) as archive:
    assert not any(n.endswith('/codex-bootstrap.json') for n in archive.namelist()), 'Private invitation is not publishable'
if os.environ.get('GITHUB_REF_TYPE') == 'tag':
    assert os.environ['GITHUB_REF_NAME'] == 'v' + version, 'Tag does not match APK version'
else:
    assert os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Unexpected CI trigger'
size = apk.stat().st_size
assert 0 < size < 100 * 1024 * 1024, 'Unexpected APK size'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
output = Path('dist')
output.mkdir(exist_ok=True)
assert not any(output.iterdir()), 'Refusing to mix release artifacts'
name = f'Codex-{version}.apk'
shutil.copyfile(apk, output / name)
(output / 'SHA256SUMS').write_text(f'{digest}  {name}\n')
metadata = dict(packageName=package[1], versionCode=code, versionName=version,
                signerSha256=SIGNER, sha256=digest, sizeBytes=size, sourceCommit=os.environ['GITHUB_SHA'])
(output / 'release-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(f'Verified {name}: code {code}, established signer, no private invitation, SHA-256 {digest}')
