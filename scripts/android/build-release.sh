#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/doctor.sh"
"$SCRIPT_DIR/wsl-gradle.sh" :app:assembleStandardRelease "$@"

apk="$SCRIPT_DIR/../../app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"
[[ -f "$apk" ]] || {
  printf 'ERROR: Expected unsigned APK was not generated: %s\n' "$apk" >&2
  exit 1
}
printf 'Unsigned release APK: %s\n' "$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"
