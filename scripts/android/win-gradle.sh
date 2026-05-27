#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

if (($# == 0)); then
  die "Usage: win-gradle.sh <gradle arguments...>"
fi

load_android_env
[[ -n "${WIN_JAVA_HOME:-}" ]] || die "WIN_JAVA_HOME is empty in .env."
command -v wslpath >/dev/null 2>&1 || die "wslpath is required for Windows Gradle fallback."
command -v powershell.exe >/dev/null 2>&1 || die "powershell.exe is required for Windows Gradle fallback."
require_file "$PROJECT_ROOT/gradlew.bat"

project_win="$(wslpath -w "$PROJECT_ROOT")"
if [[ "$project_win" == \\\\wsl* ]]; then
  die "Windows Gradle fallback does not support a WSL UNC checkout. Use wsl-gradle.sh, or place a checkout under /mnt/c."
fi

ps_quote() {
  local value="${1//\'/\'\'}"
  printf "'%s'" "$value"
}

ps_args=()
for arg in "$@"; do
  ps_args+=("$(ps_quote "$arg")")
done
ps_command="& { Set-Location $(ps_quote "$project_win"); \$env:JAVA_HOME=$(ps_quote "$WIN_JAVA_HOME"); & ./gradlew.bat @(${ps_args[*]}) }"
exec powershell.exe -NoProfile -Command "$ps_command"
