#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"
load_android_env

apk="$(resolve_apk_argument "${1:?Usage: verify-apk.sh <apk-path>}")"
apksigner="$(android_build_tool apksigner)"
"$apksigner" verify --verbose "$apk" >/dev/null

printf 'APK: %s\n' "$apk"
printf 'Size: %s bytes\n' "$(stat -c '%s' "$apk")"
printf 'Package: %s\n' "$(apk_package_name "$apk")"
printf 'Version: versionCode=%s versionName=%s\n' "$(apk_version_code "$apk")" "$(apk_version_name "$apk")"
printf 'Certificate SHA-256: %s\n' "$(apk_certificate_sha256 "$apk")"
printf 'Signature: verified\n'
