#!/usr/bin/env bash
set -euo pipefail

# Android builds belong to the Debian build VM. This file contains paths only;
# signing secrets are read from the task's protected artifact directory at build time.
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
source /srv/buildlab/control/cache-env.sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/srv/buildlab/cache/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/srv/buildlab/cache/gradle
export PATH="$JAVA_HOME/bin:$PATH"

if [[ "$#" -eq 0 ]]; then
  set -- :app:assembleRelease
fi

for requested_task in "$@"; do
  if [[ "$requested_task" == *assemble* || "$requested_task" == *bundle* || "$requested_task" == *package* ]]; then
    set +x
    export CODEX_MOBILE_SIGNING_KEYSTORE=/srv/buildlab/artifacts/codex-mobile/20261115-android-v01/signing/app-signing.p12
    signing_password_file=/srv/buildlab/artifacts/codex-mobile/20261115-android-v01/signing/password
    [[ -r "$CODEX_MOBILE_SIGNING_KEYSTORE" && -r "$signing_password_file" ]]
    CODEX_MOBILE_SIGNING_PASSWORD="$(< "$signing_password_file")"
    export CODEX_MOBILE_SIGNING_PASSWORD
    break
  fi
done

cd "$PROJECT_ROOT"
if [[ -x "$PROJECT_ROOT/gradlew" ]]; then
  GRADLE_BIN="$PROJECT_ROOT/gradlew"
else
  GRADLE_BIN=/srv/buildlab/cache/gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle
fi
exec "$GRADLE_BIN" --no-daemon --console=plain \
  --project-cache-dir "$BUILD_TASK_ROOT/gradle-state" "$@"
