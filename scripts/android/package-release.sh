#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

replace_current=false
if [[ "${1:-}" == "--replace-current" ]]; then
  replace_current=true
  shift
fi
if (($# > 0)); then
  die "Usage: package-release.sh [--replace-current]"
fi

load_android_env
"$SCRIPT_DIR/doctor.sh"

version_code="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]\+\([0-9][0-9]*\).*/\1/p' "$PROJECT_ROOT/app/build.gradle" | head -n 1)"
version_name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' "$PROJECT_ROOT/app/build.gradle" | head -n 1)"
[[ -n "$version_code" && -n "$version_name" ]] || die "Unable to read versionCode/versionName from app/build.gradle."

max_version_code=-1
same_version_reference=""
while IFS= read -r -d '' existing; do
  existing_code="$(apk_version_code "$existing" 2>/dev/null || true)"
  [[ "$existing_code" =~ ^[0-9]+$ ]] || continue
  if ((existing_code > max_version_code)); then
    max_version_code="$existing_code"
  fi
  if [[ "$existing_code" == "$version_code" && -z "$same_version_reference" ]]; then
    same_version_reference="$existing"
  fi
done < <(find "$PROJECT_ROOT/release" -type f -name '*.apk' -print0 2>/dev/null || true)

if ((max_version_code >= 0)); then
  if ((version_code < max_version_code)); then
    die "versionCode=$version_code is lower than existing release versionCode=$max_version_code."
  fi
  if ((version_code == max_version_code)) && [[ "$replace_current" != true ]]; then
    die "versionCode=$version_code already exists. Increment it, or pass --replace-current for an intentional rebuild."
  fi
fi

"$SCRIPT_DIR/build-release.sh"

source_apk="$PROJECT_ROOT/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"
apk_version_code_value="$(apk_version_code "$source_apk")"
apk_version_name_value="$(apk_version_name "$source_apk")"
[[ "$apk_version_code_value" == "$version_code" && "$apk_version_name_value" == "$version_name" ]] ||
  die "Built APK metadata does not match app/build.gradle."

zipalign="$(android_build_tool zipalign)"
apksigner="$(android_build_tool apksigner)"

formal_signing=true
for key in RELEASE_KEYSTORE_PATH RELEASE_KEY_ALIAS RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_PASSWORD RELEASE_CERT_SHA256; do
  if [[ -z "${!key:-}" ]]; then
    formal_signing=false
  fi
done

if [[ "$formal_signing" == true ]]; then
  require_file "$RELEASE_KEYSTORE_PATH"
  output_dir="$PROJECT_ROOT/release"
  output_apk="$output_dir/全省版-v${version_name}.apk"
  keystore="$RELEASE_KEYSTORE_PATH"
  alias="$RELEASE_KEY_ALIAS"
  store_pass="$RELEASE_KEYSTORE_PASSWORD"
  key_pass="$RELEASE_KEY_PASSWORD"
  expected_cert="${RELEASE_CERT_SHA256,,}"
  signing_label="release"
else
  require_file "${DEBUG_KEYSTORE_PATH:?DEBUG_KEYSTORE_PATH is not configured}"
  output_dir="$PROJECT_ROOT/release/local"
  output_apk="$output_dir/全省版-v${version_name}-debug-signed.apk"
  keystore="$DEBUG_KEYSTORE_PATH"
  alias="androiddebugkey"
  store_pass="android"
  key_pass="android"
  expected_cert=""
  signing_label="debug-local-demo"
  info "WARNING: Release signing is not fully configured. Producing a local demo APK signed with the debug keystore."
  info "WARNING: This artifact is not a formal upgradeable release package."
fi

mkdir -p "$output_dir"
tmp_apk="$output_apk.aligned.tmp.apk"
rm -f "$tmp_apk"
"$zipalign" -f -p 4 "$source_apk" "$tmp_apk"
"$apksigner" sign \
  --ks "$keystore" \
  --ks-key-alias "$alias" \
  --ks-pass "pass:$store_pass" \
  --key-pass "pass:$key_pass" \
  --out "$output_apk" \
  "$tmp_apk"
rm -f "$tmp_apk"

actual_cert="$(apk_certificate_sha256 "$output_apk")"
if [[ -n "$expected_cert" && "${actual_cert,,}" != "$expected_cert" ]]; then
  rm -f "$output_apk" "$output_apk.idsig"
  die "Signed certificate SHA-256 does not match RELEASE_CERT_SHA256."
fi

if [[ "$replace_current" == true && -n "$same_version_reference" && "$same_version_reference" != "$output_apk" ]]; then
  reference_cert="$(apk_certificate_sha256 "$same_version_reference")"
  if [[ "${actual_cert,,}" != "${reference_cert,,}" ]]; then
    rm -f "$output_apk" "$output_apk.idsig"
    die "Refusing same-version replacement: certificate differs from $same_version_reference."
  fi
fi

"$SCRIPT_DIR/verify-apk.sh" "$output_apk"
printf 'Signing type: %s\n' "$signing_label"
printf 'Staged APK: %s\n' "$output_apk"
