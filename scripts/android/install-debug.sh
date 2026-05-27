#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
apk_relative="app/build/outputs/apk/standard/debug/app-standard-debug.apk"
apk="$PROJECT_ROOT/$apk_relative"

if [[ ! -f "$apk" ]]; then
  "$SCRIPT_DIR/build-debug.sh"
fi

"$SCRIPT_DIR/doctor.sh" --device
cd "$PROJECT_ROOT"
"$SCRIPT_DIR/win-adb.sh" "$@" install -r "$apk_relative"
