#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

check_device=false
if [[ "${1:-}" == "--device" ]]; then
  check_device=true
elif (($# > 0)); then
  die "Usage: doctor.sh [--device]"
fi

load_android_env

require_dir "${JAVA_HOME:?JAVA_HOME is not configured}"
require_file "$JAVA_HOME/bin/java"
require_dir "${ANDROID_HOME:?ANDROID_HOME is not configured}"
require_dir "$ANDROID_HOME/platforms/android-${ANDROID_COMPILE_SDK:?ANDROID_COMPILE_SDK is not configured}"
require_dir "$ANDROID_HOME/ndk/${ANDROID_NDK_VERSION:?ANDROID_NDK_VERSION is not configured}"
require_dir "$ANDROID_HOME/cmake/${ANDROID_CMAKE_VERSION:?ANDROID_CMAKE_VERSION is not configured}"
require_dir "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION:?ANDROID_BUILD_TOOLS_VERSION is not configured}"
android_build_tool aapt >/dev/null
android_build_tool apksigner >/dev/null
android_build_tool zipalign >/dev/null
require_file "$PROJECT_ROOT/gradlew"
require_file "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties"

grep -q 'gradle-8\.6-' "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" ||
  die "Gradle wrapper differs from the verified Gradle 8.6 baseline."
grep -q 'ndkVersion "29.0.14206865"' "$PROJECT_ROOT/app/build.gradle" ||
  die "app/build.gradle differs from the verified NDK baseline."
grep -q 'standard {' "$PROJECT_ROOT/app/build.gradle" ||
  die "The required standard flavor was not found in app/build.gradle."
grep -q 'assembleStandardDebug' "$PROJECT_ROOT/app/build.gradle" ||
  die "The standard debug task alias was not found in app/build.gradle."

require_file "$PROJECT_ROOT/local.properties"
sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | head -n 1)"
sdk_dir="$(normalize_path "$sdk_dir")"
[[ -n "$sdk_dir" ]] || die "local.properties does not contain sdk.dir."
[[ "$sdk_dir" == "$ANDROID_HOME" ]] ||
  die "local.properties sdk.dir=$sdk_dir does not match ANDROID_HOME=$ANDROID_HOME. Update it before building in WSL."

java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1p')"
info "Environment OK"
info "  JAVA_HOME=$JAVA_HOME ($java_version)"
info "  ANDROID_HOME=$ANDROID_HOME"
info "  compileSdk=$ANDROID_COMPILE_SDK ndk=$ANDROID_NDK_VERSION cmake=$ANDROID_CMAKE_VERSION buildTools=$ANDROID_BUILD_TOOLS_VERSION"
info "  defaultVariant=standardDebug releaseVariant=standardRelease"

if [[ "$check_device" == true ]]; then
  [[ -n "${WIN_ANDROID_ADB:-}" ]] ||
    die "WIN_ANDROID_ADB is empty in .env. Configure the Windows adb.exe path for Rokid Glass device access."
  require_file "$WIN_ANDROID_ADB"
  info "  WIN_ANDROID_ADB=$WIN_ANDROID_ADB"
  "$SCRIPT_DIR/win-adb.sh" devices -l
fi
