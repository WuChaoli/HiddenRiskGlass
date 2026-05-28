#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
serial_args=()
clear=false
pattern=""

while (($# > 0)); do
  case "$1" in
    -s)
      serial_args=("-s" "${2:?Missing serial after -s}")
      shift 2
      ;;
    --clear)
      clear=true
      shift
      ;;
    --tag)
      pattern="${2:?Missing pattern after --tag}"
      shift 2
      ;;
    *)
      printf 'Usage: logcat.sh [-s SERIAL] [--clear] [--tag REGEX]\n' >&2
      exit 1
      ;;
  esac
done

if [[ "$clear" == true ]]; then
  "$SCRIPT_DIR/win-adb.sh" "${serial_args[@]}" logcat -c
fi
if [[ -n "$pattern" ]]; then
  "$SCRIPT_DIR/win-adb.sh" "${serial_args[@]}" logcat | grep -E "$pattern"
else
  "$SCRIPT_DIR/win-adb.sh" "${serial_args[@]}" logcat
fi
