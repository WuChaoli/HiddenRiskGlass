#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================"
echo "HiddenRiskGlassServer 停止脚本"
echo "========================================"

# 检查 docker compose 命令
DOCKER_COMPOSE_CMD=""
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker compose"
else
    echo "错误：未找到 docker-compose"
    exit 1
fi

cd "$SCRIPT_DIR"

echo "停止并移除容器..."
$DOCKER_COMPOSE_CMD down

echo ""
echo "服务已停止"
