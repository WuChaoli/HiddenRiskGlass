#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
serial_args=()
if [[ "${1:-}" == "-s" ]]; then
  serial_args=("-s" "${2:?Missing serial after -s}")
  shift 2
fi
activity="${1:?Usage: start-activity.sh [-s SERIAL] <activity-class> [extra am args...]}"
shift

"$SCRIPT_DIR/doctor.sh" --device >/dev/null
"$SCRIPT_DIR/win-adb.sh" "${serial_args[@]}" shell am start -n "com.rokid.glesse/$activity" "$@"
