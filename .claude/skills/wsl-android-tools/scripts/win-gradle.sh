#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

project_dir="$PWD"
java_home_override=""
gradle_args=()

while (($# > 0)); do
  case "$1" in
    --project)
      project_dir="${2:?--project requires a directory}"
      shift 2
      ;;
    --java-home)
      java_home_override="${2:?--java-home requires a Windows path}"
      shift 2
      ;;
    --help|-h)
      cat <<'EOF'
Usage: win-gradle.sh [--project DIR] [--java-home WINDOWS_PATH] [--] <gradle args...>

Examples:
  win-gradle.sh :app:compileDebugKotlin
  win-gradle.sh --project /abs/path/to/project :app:installDebug
EOF
      exit 0
      ;;
    --)
      shift
      while (($# > 0)); do
        gradle_args+=("$1")
        shift
      done
      ;;
    *)
      gradle_args+=("$1")
      shift
      ;;
  esac
done

if ((${#gradle_args[@]} == 0)); then
  echo "win-gradle.sh requires at least one Gradle argument." >&2
  exit 1
fi

project_dir="$(win_android_project_dir "$project_dir")"
project_win="$(win_android_project_win_path "$project_dir")"
java_home="${java_home_override:-$(win_android_require_java_home)}"

if [[ ! -f "$project_dir/gradlew.bat" ]]; then
  echo "gradlew.bat not found in project directory: $project_dir" >&2
  exit 1
fi

ps_quote() {
  local value="${1//\'/\'\'}"
  printf "'%s'" "$value"
}

ps_gradle_args=()
for arg in "${gradle_args[@]}"; do
  ps_gradle_args+=("$(ps_quote "$arg")")
done

ps_command="& { Set-Location $(ps_quote "$project_win"); \$env:JAVA_HOME=$(ps_quote "$java_home"); & ./gradlew.bat @(${ps_gradle_args[*]}) }"
powershell.exe -NoProfile -Command "$ps_command"
