#!/usr/bin/env bash
set -euo pipefail

# Android builds belong to the Debian build VM. Machine-specific paths and
# signing credentials are supplied by the environment, never by this source file.
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Run this script on the Debian build VM after syncing the project." >&2
  exit 2
fi

# buildlab-job snapshots this script into jobs/<name>/script.sh, so locate the
# project from the job's --cwd rather than from the copied script's directory.
PROJECT_ROOT="$(cd "${CODEX_PROJECT_ROOT:-$PWD}" && pwd)"
if [[ ! -f "$PROJECT_ROOT/settings.gradle.kts" || ! -f "$PROJECT_ROOT/app/build.gradle.kts" ]]; then
  echo "Run with --cwd set to the project source root, or set CODEX_PROJECT_ROOT." >&2
  exit 2
fi
BUILD_TASK_ROOT="${CODEX_BUILD_TASK_ROOT:-$(cd "$PROJECT_ROOT/.." && pwd)}"
set +x
if [[ -n "${CODEX_MOBILE_BUILD_ENV:-}" ]]; then
  source "$CODEX_MOBILE_BUILD_ENV"
fi
if [[ -n "${CODEX_MOBILE_CACHE_ENV:-}" ]]; then
  source "$CODEX_MOBILE_CACHE_ENV"
fi
: "${JAVA_HOME:?Set JAVA_HOME to your JDK 21 installation}"
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleRelease
fi

for requested_task in "$@"; do
  if [[ "$requested_task" == *assemble* || "$requested_task" == *bundle* || "$requested_task" == *package* ]]; then
    set +x
    : "${CODEX_MOBILE_SIGNING_KEYSTORE:?Set the signing keystore path}"
    [[ -r "$CODEX_MOBILE_SIGNING_KEYSTORE" ]]
    if [[ -z "${CODEX_MOBILE_SIGNING_PASSWORD:-}" && -n "${CODEX_MOBILE_SIGNING_PASSWORD_FILE:-}" ]]; then
      [[ -r "$CODEX_MOBILE_SIGNING_PASSWORD_FILE" ]]
      CODEX_MOBILE_SIGNING_PASSWORD="$(< "$CODEX_MOBILE_SIGNING_PASSWORD_FILE")"
    fi
    : "${CODEX_MOBILE_SIGNING_PASSWORD:?Set the signing password or its protected file path}"
    export CODEX_MOBILE_SIGNING_KEYSTORE
    export CODEX_MOBILE_SIGNING_PASSWORD
    break
  fi
done

cd "$PROJECT_ROOT"
exec "$PROJECT_ROOT/gradlew" --no-daemon --console=plain \
  --project-cache-dir "$BUILD_TASK_ROOT/gradle-state" "$@"
