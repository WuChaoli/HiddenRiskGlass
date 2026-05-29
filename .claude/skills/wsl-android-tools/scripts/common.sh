#!/usr/bin/env bash
set -euo pipefail

DEFAULT_WIN_JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
DEFAULT_WSL_ADB='/mnt/c/Users/wuchaoli/AppData/Local/Android/Sdk/platform-tools/adb.exe'

win_android_find_adb() {
  if [[ -n "${WIN_ANDROID_ADB:-}" ]]; then
    printf '%s\n' "$WIN_ANDROID_ADB"
    return 0
  fi
  if [[ -x "$DEFAULT_WSL_ADB" ]]; then
    printf '%s\n' "$DEFAULT_WSL_ADB"
    return 0
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb.exe" ]]; then
    printf '%s\n' "${ANDROID_SDK_ROOT}/platform-tools/adb.exe"
    return 0
  fi
  return 1
}

win_android_find_java_home() {
  if [[ -n "${WIN_JAVA_HOME:-}" ]]; then
    printf '%s\n' "$WIN_JAVA_HOME"
    return 0
  fi
  printf '%s\n' "$DEFAULT_WIN_JAVA_HOME"
}

win_android_require_adb() {
  local adb_path
  adb_path="$(win_android_find_adb)" || {
    echo "Unable to locate adb.exe. Set WIN_ANDROID_ADB or ANDROID_SDK_ROOT." >&2
    exit 1
  }
  printf '%s\n' "$adb_path"
}

win_android_require_java_home() {
  local java_home
  java_home="$(win_android_find_java_home)"
  if [[ -z "$java_home" ]]; then
    echo "Unable to resolve Windows JAVA_HOME." >&2
    exit 1
  fi
  printf '%s\n' "$java_home"
}

win_android_project_dir() {
  local project_dir="${1:-$PWD}"
  if [[ ! -d "$project_dir" ]]; then
    echo "Project directory does not exist: $project_dir" >&2
    exit 1
  fi
  printf '%s\n' "$(cd "$project_dir" && pwd)"
}

win_android_project_win_path() {
  local project_dir
  project_dir="$(win_android_project_dir "${1:-$PWD}")"
  wslpath -w "$project_dir"
}

win_android_cmd_quote() {
  local value="${1//\"/\"\"}"
  printf '"%s"' "$value"
}
