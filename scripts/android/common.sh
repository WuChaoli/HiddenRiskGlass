#!/usr/bin/env bash
set -euo pipefail

ANDROID_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$ANDROID_SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '%s\n' "$*"
}

load_android_env() {
  [[ -f "$ENV_FILE" ]] || die "Missing $ENV_FILE. Copy .env.example to .env and configure local Android tools."
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  # 将 Windows 绝对路径 (C:/...) 转换为当前 bash 环境可用的格式
  for var in JAVA_HOME ANDROID_HOME DEBUG_KEYSTORE_PATH WIN_ANDROID_ADB; do
    if [[ -n "${!var:-}" ]]; then
      printf -v "$var" '%s' "$(normalize_path "${!var}")"
    fi
  done
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
}

# 将 Windows 绝对路径转为当前环境可用格式
#   Git Bash/MSYS: C:/path → /c/path
#   WSL:           C:/path → /mnt/c/path
normalize_path() {
  local p="$1"
  if [[ "$p" =~ ^([a-zA-Z]):[/\\]?(.*) ]]; then
    local drive="${BASH_REMATCH[1],,}"
    local rest="${BASH_REMATCH[2]}"
    rest="${rest//\\//}"
    if [[ -d /mnt ]]; then
      printf '/mnt/%s/%s\n' "$drive" "$rest"
    else
      printf '/%s/%s\n' "$drive" "$rest"
    fi
    return
  fi
  printf '%s\n' "$p"
}

require_dir() {
  [[ -d "$1" ]] || die "Required directory not found: $1"
}

require_file() {
  [[ -f "$1" ]] || die "Required file not found: $1"
}

android_build_tool() {
  local name="$1"
  local tool="${ANDROID_HOME:?ANDROID_HOME is not configured}/build-tools/${ANDROID_BUILD_TOOLS_VERSION:?ANDROID_BUILD_TOOLS_VERSION is not configured}/$name"
  # Windows 上 build-tools 可能以 .bat 形式存在（如 apksigner.bat），.bat 在 MSYS 下无执行位
  if [[ ! -x "$tool" ]]; then
    if [[ -f "$tool.bat" ]]; then
      tool="$tool.bat"
    elif [[ -f "$tool.exe" ]]; then
      tool="$tool.exe"
    fi
  fi
  [[ -x "$tool" || -f "$tool" ]] || die "Android build tool not found or not executable: $tool"
  printf '%s\n' "$tool"
}

apk_badging_line() {
  local aapt
  aapt="$(android_build_tool aapt)"
  "$aapt" dump badging "$1" | sed -n '1p'
}

apk_version_code() {
  apk_badging_line "$1" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"
}

apk_version_name() {
  apk_badging_line "$1" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p"
}

apk_package_name() {
  apk_badging_line "$1" | sed -n "s/package: name='\([^']*\)'.*/\1/p"
}

apk_certificate_sha256() {
  local apksigner
  apksigner="$(android_build_tool apksigner)"
  "$apksigner" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    head -n 1
}

resolve_apk_argument() {
  local path="$1"
  if [[ "$path" != /* ]]; then
    path="$PROJECT_ROOT/$path"
  fi
  require_file "$path"
  printf '%s\n' "$path"
}
