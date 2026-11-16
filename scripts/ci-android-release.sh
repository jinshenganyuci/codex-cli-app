#!/usr/bin/env bash
# GitHub-hosted runner entrypoint. Local releases continue to use scripts/build.sh on Debian.
set -euo pipefail
set +x
umask 077
[[ "${GITHUB_ACTIONS:-}" == true && -n "${RUNNER_TEMP:-}" ]] || { echo 'Run this entrypoint in GitHub Actions.' >&2; exit 2; }
[[ -n "${ANDROID_KEYSTORE_BASE64:-}" && -n "${ANDROID_KEYSTORE_PASSWORD:-}" ]] || { echo 'Configure the two Android signing repository secrets.' >&2; exit 2; }
[[ -z "${CODEX_MOBILE_BOOTSTRAP_FILE:-}${CODEX_MOBILE_BOOTSTRAP_TEST_BASE:-}${CODEX_MOBILE_TEST_BASE:-}" ]] || { echo 'Private invitation and test builds cannot be published.' >&2; exit 2; }
SIGNING_DIRECTORY="$RUNNER_TEMP/codex-android-signing"
[[ ! -e "$SIGNING_DIRECTORY" ]] || { echo 'Signing temporary directory already exists.' >&2; exit 2; }
mkdir -m 700 "$SIGNING_DIRECTORY"
trap 'rm -rf -- "$SIGNING_DIRECTORY"' EXIT
export CODEX_MOBILE_SIGNING_KEYSTORE="$SIGNING_DIRECTORY/release.p12"
export CODEX_MOBILE_SIGNING_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
python3 - <<'PY'
import base64, os, pathlib
try:
    data = base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True)
    assert 0 < len(data) < 65536
    pathlib.Path(os.environ['CODEX_MOBILE_SIGNING_KEYSTORE']).write_bytes(data)
except Exception:
    raise SystemExit('Invalid Android signing secret; content omitted.')
PY
unset ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD
bash ./gradlew --no-daemon --no-configuration-cache --console=plain :app:assembleRelease :app:lintRelease
python3 scripts/verify-ci-apk.py app/build/outputs/apk/release/app-release.apk
