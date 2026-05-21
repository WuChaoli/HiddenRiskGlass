#!/usr/bin/env bash
set -euo pipefail

# 获取脚本所在目录的绝对路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 镜像名称和版本
IMAGE_NAME="hiddenrisk-server"
IMAGE_TAG="latest"
TAR_NAME="${IMAGE_NAME}.tar"
DEPLOY_PACKAGE="hiddenrisk-server-deploy.tar.gz"

echo "========================================"
echo "HiddenRiskGlassServer 离线构建脚本"
echo "========================================"

# 检查 Docker
echo "[1/5] 检查 Docker 环境..."
if ! command -v docker &> /dev/null; then
    echo "错误：未找到 docker 命令，请先安装 Docker"
    exit 1
fi
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "错误：未找到 docker-compose，请先安装 Docker Compose"
    exit 1
fi

# 进入项目目录
cd "$PROJECT_DIR"

echo "[2/5] 构建 Docker 镜像..."
docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" .

echo "[3/5] 导出镜像为 tar 文件..."
docker save "${IMAGE_NAME}:${IMAGE_TAG}" -o "${TAR_NAME}"

echo "[4/5] 准备离线部署包..."
# 创建临时目录
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

# 复制必要文件到临时目录
cp "${TAR_NAME}" "$TMP_DIR/"
cp docker-compose.yml "$TMP_DIR/"
cp .env.example "$TMP_DIR/.env.example"
cp scripts/start-offline.sh "$TMP_DIR/"
cp scripts/stop-offline.sh "$TMP_DIR/"

# 创建部署说明
cat > "$TMP_DIR/README.txt" << 'EOF'
HiddenRiskGlassServer 离线部署包
================================

部署步骤：
1. 将本目录传输到目标离线服务器
2. 复制 .env.example 为 .env，填写 ADMIN_PASSWORD 和 SESSION_SECRET
3. 执行 ./start-offline.sh 启动服务
4. 访问 http://<服务器IP>:10203

停止服务：
  ./stop-offline.sh

查看日志：
  docker-compose logs -f

数据目录：
  /data/HiddenRiskGlass/data/
  （包含 SQLite 数据库和 releases/ APK 文件）
EOF

# 打包
tar -czf "${DEPLOY_PACKAGE}" -C "$TMP_DIR" .

echo "[5/5] 构建完成！"
echo ""
echo "交付物："
echo "  - ${TAR_NAME}        Docker 镜像导出文件"
echo "  - ${DEPLOY_PACKAGE}  完整的离线部署包（含脚本和配置模板）"
echo ""
echo "下一步：将 ${DEPLOY_PACKAGE} 传输到离线服务器并解压部署"
