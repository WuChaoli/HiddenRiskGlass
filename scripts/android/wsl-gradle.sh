#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

if (($# == 0)); then
  die "Usage: wsl-gradle.sh <gradle arguments...>"
fi

load_android_env
require_file "$JAVA_HOME/bin/java"
require_dir "$ANDROID_HOME"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

if [[ "${HTTP_PROXY:-}${http_proxy:-}${HTTPS_PROXY:-}${https_proxy:-}" == *"127.0.0.1"* ]]; then
  info "Removing localhost proxy variables for Gradle to avoid Java TLS handshake failures."
  unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy
fi

gradle_args=()
if [[ -n "${GRADLE_PROXY_HOST:-}" || -n "${GRADLE_PROXY_PORT:-}" ]]; then
  [[ -n "${GRADLE_PROXY_HOST:-}" && -n "${GRADLE_PROXY_PORT:-}" ]] ||
    die "Both GRADLE_PROXY_HOST and GRADLE_PROXY_PORT must be set, or neither."
  gradle_args+=(
    "-Dhttp.proxyHost=$GRADLE_PROXY_HOST"
    "-Dhttp.proxyPort=$GRADLE_PROXY_PORT"
    "-Dhttps.proxyHost=$GRADLE_PROXY_HOST"
    "-Dhttps.proxyPort=$GRADLE_PROXY_PORT"
  )
fi

cd "$PROJECT_ROOT"
exec ./gradlew "${gradle_args[@]}" "$@"
