#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

mode="print"
if [[ "${1:-}" == "--exports" ]]; then
  mode="exports"
fi

adb_path="$(win_android_require_adb)"
java_home="$(win_android_require_java_home)"

if [[ "$mode" == "exports" ]]; then
  printf 'export WIN_ANDROID_ADB=%q\n' "$adb_path"
  printf 'export WIN_JAVA_HOME=%q\n' "$java_home"
  exit 0
fi

cat <<EOF
WIN_ANDROID_ADB=$adb_path
WIN_JAVA_HOME=$java_home
EOF
