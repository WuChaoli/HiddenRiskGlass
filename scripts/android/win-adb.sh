#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

load_android_env
[[ -n "${WIN_ANDROID_ADB:-}" ]] ||
  die "WIN_ANDROID_ADB is empty in .env. Configure the Windows adb.exe path."
require_file "$WIN_ANDROID_ADB"
exec "$WIN_ANDROID_ADB" "$@"
