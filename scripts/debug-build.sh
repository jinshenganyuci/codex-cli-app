#!/usr/bin/env bash
set -euo pipefail
# This entrypoint is copied into the job directory by buildlab-job.
PROJECT_ROOT="$(cd "${CODEX_PROJECT_ROOT:-$PWD}" && pwd)"
if [[ ! -f "$PROJECT_ROOT/settings.gradle.kts" || ! -x "$PROJECT_ROOT/scripts/build.sh" ]]; then
  echo "Run with --cwd set to the project source root, or set CODEX_PROJECT_ROOT." >&2
  exit 2
fi
exec "$PROJECT_ROOT/scripts/build.sh" :app:assembleDebug "$@"
