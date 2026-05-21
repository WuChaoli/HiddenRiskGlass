# HiddenRiskGlassServer Docker 离线部署实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 HiddenRiskGlassServer 创建 Docker 离线部署能力，包含镜像定义、构建脚本和离线服务器启动脚本，支持在 Kylin V10 等无外网 Linux 服务器上一键部署。

**Architecture:** 基于 `python:3.12-slim-bookworm` 构建单容器镜像，非 root 用户运行；宿主机 `/data/HiddenRiskGlass/data/` 挂载为容器数据卷；环境变量通过 `.env` 文件注入；联网机 `docker build` + `docker save` 导出 tar，离线机 `docker load` + `docker-compose up` 运行。

**Tech Stack:** Docker, Docker Compose, Python 3.12, FastAPI, Uvicorn, SQLite

---

## 文件结构

| 文件 | 操作 | 说明 |
|------|------|------|
| `servers/HiddenRiskGlassServer/Dockerfile` | 创建 | 镜像构建定义，非 root 运行 |
| `servers/HiddenRiskGlassServer/.dockerignore` | 创建 | 排除缓存、测试、SQLite、APK 等大文件 |
| `servers/HiddenRiskGlassServer/docker-compose.yml` | 创建 | 端口映射、卷挂载、env_file 配置 |
| `servers/HiddenRiskGlassServer/.env.example` | 创建 | 环境变量模板，供部署人员参考 |
| `servers/HiddenRiskGlassServer/scripts/build-offline.sh` | 创建 | 联网机执行：构建镜像 + 导出 tar + 打包交付物 |
| `servers/HiddenRiskGlassServer/scripts/start-offline.sh` | 创建 | 离线服务器执行：检查 Docker、加载镜像、启动容器 |
| `servers/HiddenRiskGlassServer/scripts/stop-offline.sh` | 创建 | 离线服务器执行：停止并移除容器 |

---

### Task 1: 创建 Dockerfile

**Files:**
- Create: `servers/HiddenRiskGlassServer/Dockerfile`

- [ ] **Step 1: 编写 Dockerfile**

```dockerfile
# 基于 Debian Bookworm slim，glibc 兼容性好
FROM python:3.12-slim-bookworm

# 安装基本工具（可选，调试用）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户/组（UID/GID=1000）
RUN groupadd --gid 1000 appgroup && \
    useradd --uid 1000 --gid appgroup --shell /bin/bash --create-home appuser

# 设置工作目录
WORKDIR /app

# 先复制依赖文件并安装（利用 Docker 层缓存）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制项目代码
COPY app/ ./app/
COPY server.py .
COPY config.json .

# 创建数据目录并设置属主
RUN mkdir -p /app/data/releases && chown -R appuser:appgroup /app/data

# 切换到非 root 用户
USER appuser

# 暴露服务端口
EXPOSE 8080

# 默认环境变量（可被 docker-compose 覆盖）
ENV APK_UPDATE_DATA_DIR=/app/data

# 启动命令
CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "8080"]
```

- [ ] **Step 2: Commit**

```bash
git add servers/HiddenRiskGlassServer/Dockerfile
git commit -m "feat: add Dockerfile for containerized deployment"
```

---

### Task 2: 创建 .dockerignore

**Files:**
- Create: `servers/HiddenRiskGlassServer/.dockerignore`

- [ ] **Step 1: 编写 .dockerignore**

```text
# Python 缓存
__pycache__/
*.py[cod]
*$py.class
*.so

# 虚拟环境
.venv/
venv/
env/

# 测试相关
.pytest_cache/
tests/

# 数据库和上传文件（数据卷挂载，不应打包进镜像）
apk_update_server.sqlite3
releases/
*.apk

# IDE
.idea/
.vscode/
*.swp
*.swo

# Git
.git/
.gitignore

# 其他
*.md
*.ps1
scripts/
docs/
```

- [ ] **Step 2: Commit**

```bash
git add servers/HiddenRiskGlassServer/.dockerignore
git commit -m "chore: add .dockerignore to exclude runtime data from image"
```

---

### Task 3: 创建 docker-compose.yml

**Files:**
- Create: `servers/HiddenRiskGlassServer/docker-compose.yml`

- [ ] **Step 1: 编写 docker-compose.yml**

```yaml
version: "3.8"

services:
  hiddenrisk-server:
    image: hiddenrisk-server:latest
    container_name: hiddenrisk-server
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - /data/HiddenRiskGlass/data:/app/data
    env_file:
      - .env
    environment:
      # 强制覆盖数据目录，确保容器内路径正确
      APK_UPDATE_DATA_DIR: /app/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/login"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s
```

- [ ] **Step 2: Commit**

```bash
git add servers/HiddenRiskGlassServer/docker-compose.yml
git commit -m "feat: add docker-compose.yml for offline server deployment"
```

---

### Task 4: 创建 .env.example 环境变量模板

**Files:**
- Create: `servers/HiddenRiskGlassServer/.env.example`

- [ ] **Step 1: 编写 .env.example**

```bash
# ============================================
# HiddenRiskGlassServer 环境变量配置
# ============================================
# 复制此文件为 .env 并填写实际值

# 管理后台登录密码（必填）
ADMIN_PASSWORD=your-strong-password-here

# Cookie 签名密钥（必填，建议固定长随机字符串）
SESSION_SECRET=replace-with-a-long-random-secret-at-least-32-chars

# 是否仅 HTTPS 传输 Cookie（可选，内网 HTTP 部署时留空）
# SESSION_COOKIE_SECURE=false

# 数据目录（docker-compose 中已固定为 /app/data，通常无需修改）
# APK_UPDATE_DATA_DIR=/app/data
```

- [ ] **Step 2: Commit**

```bash
git add servers/HiddenRiskGlassServer/.env.example
git commit -m "docs: add .env.example for deployment configuration"
```

---

### Task 5: 创建 build-offline.sh（联网构建脚本）

**Files:**
- Create: `servers/HiddenRiskGlassServer/scripts/build-offline.sh`

- [ ] **Step 1: 编写 build-offline.sh**

```bash
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
4. 访问 http://<服务器IP>:8080

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
```

- [ ] **Step 2: 设置可执行权限**

```bash
chmod +x servers/HiddenRiskGlassServer/scripts/build-offline.sh
```

- [ ] **Step 3: Commit**

```bash
git add servers/HiddenRiskGlassServer/scripts/build-offline.sh
git commit -m "feat: add build-offline.sh for packaging deployable image"
```

---

### Task 6: 创建 start-offline.sh（离线服务器启动脚本）

**Files:**
- Create: `servers/HiddenRiskGlassServer/scripts/start-offline.sh`

- [ ] **Step 1: 编写 start-offline.sh**

```bash
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
```

- [ ] **Step 2: 设置可执行权限**

```bash
chmod +x servers/HiddenRiskGlassServer/scripts/start-offline.sh
```

- [ ] **Step 3: Commit**

```bash
git add servers/HiddenRiskGlassServer/scripts/start-offline.sh
git commit -m "feat: add start-offline.sh for one-click deployment on offline servers"
```

---

### Task 7: 创建 stop-offline.sh（离线服务器停止脚本）

**Files:**
- Create: `servers/HiddenRiskGlassServer/scripts/stop-offline.sh`

- [ ] **Step 1: 编写 stop-offline.sh**

```bash
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
```

- [ ] **Step 2: 设置可执行权限**

```bash
chmod +x servers/HiddenRiskGlassServer/scripts/stop-offline.sh
```

- [ ] **Step 3: Commit**

```bash
git add servers/HiddenRiskGlassServer/scripts/stop-offline.sh
git commit -m "feat: add stop-offline.sh for stopping containerized service"
```

---

### Task 8: 验证构建（可选，在联网机上执行）

**Files:**
- Test: 在联网机上手动验证

- [ ] **Step 1: 构建镜像并验证**

在联网机（Linux 或 WSL）上执行：

```bash
cd servers/HiddenRiskGlassServer
# 构建镜像
docker build -t hiddenrisk-server:latest .
# 检查镜像
docker images hiddenrisk-server:latest
```

- [ ] **Step 2: 本地运行验证**

```bash
# 创建临时 .env
cat > .env << 'EOF'
ADMIN_PASSWORD=test123
SESSION_SECRET=test-secret-for-validation-only
EOF
# 启动容器（前台运行，验证后 Ctrl+C 停止）
docker run --rm -p 8080:8080 --env-file .env -e APK_UPDATE_DATA_DIR=/app/data hiddenrisk-server:latest
```

在另一个终端验证：

```bash
curl -s http://localhost:8080/login | head -20
```

预期：返回 HTML 登录页面内容。

- [ ] **Step 3: 验证构建脚本**

```bash
bash scripts/build-offline.sh
# 检查输出文件
ls -la hiddenrisk-server.tar hiddenrisk-server-deploy.tar.gz
```

---

## Spec Coverage 自检

| Spec 要求 | 对应 Task | 状态 |
|-----------|-----------|------|
| 基础镜像 python:3.12-slim | Task 1 | ✅ |
| 非 root 运行 (UID/GID=1000) | Task 1 | ✅ |
| 数据目录 /app/data | Task 1, 3 | ✅ |
| 端口 8080 | Task 1, 3 | ✅ |
| .dockerignore 排除运行时数据 | Task 2 | ✅ |
| docker-compose 端口映射 + 卷挂载 + env_file | Task 3 | ✅ |
| 宿主机数据目录 /data/HiddenRiskGlass/data/ | Task 3, 6 | ✅ |
| 环境变量通过 .env 注入 | Task 3, 4 | ✅ |
| build-offline.sh 构建 + 导出 tar + 打包 | Task 5 | ✅ |
| start-offline.sh 加载镜像 + 启动 | Task 6 | ✅ |
| stop-offline.sh 停止服务 | Task 7 | ✅ |
| 数据目录权限设置 (chown 1000:1000) | Task 6 | ✅ |

## Placeholder 扫描

- [x] 无 "TBD"/"TODO"
- [x] 无 "implement later"
- [x] 所有步骤包含完整代码
- [x] 无 "Add appropriate error handling" 等模糊描述

## Type/Name 一致性

- 镜像名：`hiddenrisk-server:latest` — 全文档一致
- 数据目录：`/data/HiddenRiskGlass/data` — Task 3, 6 一致
- 容器内数据目录：`/app/data` — Task 1, 3 一致
- env_file 名：`.env` — Task 3, 4, 6 一致
- APK_UPDATE_DATA_DIR 环境变量 — Task 1, 3 一致
