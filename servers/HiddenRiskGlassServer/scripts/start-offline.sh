#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="/data/HiddenRiskGlass/data"
IMAGE_NAME="hiddenrisk-server"
IMAGE_TAG="latest"
TAR_NAME="${IMAGE_NAME}.tar"

echo "========================================"
echo "HiddenRiskGlassServer 离线启动脚本"
echo "========================================"

# 检查 Docker
echo "[1/6] 检查 Docker 环境..."
if ! command -v docker &> /dev/null; then
    echo "错误：未找到 docker 命令，请先安装 Docker"
    exit 1
fi

DOCKER_COMPOSE_CMD=""
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker compose"
else
    echo "错误：未找到 docker-compose，请先安装 Docker Compose"
    exit 1
fi

# 检查 .env 文件
echo "[2/6] 检查 .env 配置..."
if [ ! -f "${SCRIPT_DIR}/.env" ]; then
    echo "错误：未找到 .env 文件"
    echo "请复制 .env.example 为 .env 并填写配置："
    echo "  cp .env.example .env"
    echo "  vi .env"
    exit 1
fi

# 检查必填环境变量
set -a
source "${SCRIPT_DIR}/.env"
set +a

if [ -z "${ADMIN_PASSWORD:-}" ]; then
    echo "错误：.env 文件中 ADMIN_PASSWORD 不能为空"
    exit 1
fi

if [ -z "${SESSION_SECRET:-}" ]; then
    echo "警告：.env 文件中 SESSION_SECRET 未设置，建议设置固定值"
fi

# 创建数据目录
echo "[3/6] 创建数据目录 ${DATA_DIR}..."
mkdir -p "${DATA_DIR}/releases"

# 设置目录权限（匹配容器内 appuser UID/GID=1000）
echo "[4/6] 设置数据目录权限..."
chown -R 1000:1000 "${DATA_DIR}" 2>/dev/null || {
    echo "警告：无法设置 ${DATA_DIR} 属主为 1000:1000，请确保当前用户有权限"
    echo "或者手动执行：sudo chown -R 1000:1000 ${DATA_DIR}"
}
chmod -R u+rwx "${DATA_DIR}"

# 加载 Docker 镜像
echo "[5/6] 加载 Docker 镜像..."
if ! docker image inspect "${IMAGE_NAME}:${IMAGE_TAG}" &> /dev/null; then
    if [ ! -f "${SCRIPT_DIR}/${TAR_NAME}" ]; then
        echo "错误：未找到镜像文件 ${TAR_NAME}"
        exit 1
    fi
    docker load -i "${SCRIPT_DIR}/${TAR_NAME}"
    echo "镜像加载完成"
else
    echo "镜像已存在，跳过加载"
fi

# 启动容器
echo "[6/6] 启动服务..."
cd "$SCRIPT_DIR"
$DOCKER_COMPOSE_CMD up -d

echo ""
echo "========================================"
echo "服务启动成功！"
echo "========================================"
echo ""
echo "访问地址："
echo "  管理后台：http://<本机IP>:8080/admin"
echo "  API 接口：http://<本机IP>:8080/api/v1/updates/check"
echo ""
echo "常用命令："
echo "  查看日志：$DOCKER_COMPOSE_CMD logs -f"
echo "  停止服务：./stop-offline.sh"
echo "  重启服务：$DOCKER_COMPOSE_CMD restart"
echo ""
echo "数据目录：${DATA_DIR}"
echo ""
