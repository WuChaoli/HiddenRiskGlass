#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
serial_args=()
if [[ "${1:-}" == "-s" ]]; then
  serial_args=("-s" "${2:?Missing serial after -s}")
  shift 2
fi
output="${1:?Usage: screenshot.sh [-s SERIAL] <output-path>}"
mkdir -p "$(dirname "$output")"
"$SCRIPT_DIR/win-adb.sh" "${serial_args[@]}" exec-out screencap -p > "$output"
printf 'Screenshot: %s\n' "$output"
