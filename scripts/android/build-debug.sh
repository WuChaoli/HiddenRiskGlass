#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/doctor.sh"
"$SCRIPT_DIR/wsl-gradle.sh" :app:assembleStandardDebug "$@"

apk="$SCRIPT_DIR/../../app/build/outputs/apk/standard/debug/app-standard-debug.apk"
[[ -f "$apk" ]] || {
  printf 'ERROR: Expected APK was not generated: %s\n' "$apk" >&2
  exit 1
}
printf 'Debug APK: %s\n' "$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"
