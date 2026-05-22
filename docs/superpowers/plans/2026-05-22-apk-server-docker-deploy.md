# APK 更新服务器 Docker 部署计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 HiddenRiskGlassServer APK 更新分发服务器通过 Docker 离线部署包形式部署到 4090 服务器（Ubuntu/Debian，无外网）

**Architecture:** 利用项目已有的 Dockerfile + docker-compose.yml + build-offline.sh 构建离线部署包，在有外网的开发机上构建镜像后导出为 tar，传输到 4090 服务器后加载运行。同时更新 Android 客户端中的默认更新检查 URL 指向新服务器地址。

**Tech Stack:** Docker, FastAPI, Python 3.12, Gradle (Android), OkHttp (Android)

**前置条件:**
- 4090 服务器 IP 地址已知（本文档中用 `SERVER_IP` 占位）
- 4090 服务器已安装 Docker + Docker Compose
- 开发机（Windows WSL / Git Bash）可访问 Docker

---

### Task 1: 确认现有构建环境可用

**Files:**
- Read: `servers/HiddenRiskGlassServer/Dockerfile`
- Read: `servers/HiddenRiskGlassServer/docker-compose.yml`
- Read: `servers/HiddenRiskGlassServer/scripts/build-offline.sh`

- [ ] **Step 1: 检查 Docker 环境**

```bash
# 确认 Docker 可用
docker --version

# 预期输出:
# Docker version 27.x.x, build xxxxxxx
```

- [ ] **Step 2: 确认项目文件完整**

```bash
cd servers/HiddenRiskGlassServer

# 检查关键文件存在
ls -la Dockerfile docker-compose.yml scripts/build-offline.sh requirements.txt server.py config.json app/

# 预期: 所有文件存在，app/ 目录含 main.py / config.py / db.py / auth.py / services.py / schemas.py
```

- [ ] **Step 3: 清理旧构建产物（如存在）**

```bash
# 清理上次构建可能残留的文件
rm -f hiddenrisk-server.tar hiddenrisk-server-deploy.tar.gz
rm -rf docker/
```

---

### Task 2: 构建离线部署包（开发机）

**Files:**
- Read: `servers/HiddenRiskGlassServer/.env.example`
- Create: `servers/HiddenRiskGlassServer/docker/hiddenrisk-server.tar`（构建产物）
- Create: `servers/HiddenRiskGlassServer/docker/hiddenrisk-server-deploy.tar.gz`（构建产物）

- [ ] **Step 1: 构建 Docker 镜像**

```bash
cd servers/HiddenRiskGlassServer

docker build -t hiddenrisk-server:latest .
```

预期输出 — 构建过程日志结尾类似：
```
 => => writing image sha256:xxxx...xxx
 => => naming to docker.io/library/hiddenrisk-server:latest
```

- [ ] **Step 2: 运行构建脚本打包离线部署包**

```bash
bash scripts/build-offline.sh
```

预期输出：
```
========================================
HiddenRiskGlassServer 离线构建脚本
========================================
[1/5] 检查 Docker 环境...
[2/5] 构建 Docker 镜像...
[3/5] 导出镜像为 tar 文件...
[4/5] 准备离线部署包...
[5/5] 构建完成！

交付物：
  - hiddenrisk-server.tar        Docker 镜像导出文件
  - hiddenrisk-server-deploy.tar.gz  完整的离线部署包（含脚本和配置模板）
```

- [ ] **Step 3: 验证产物完整性**

```bash
# 确认两个文件生成
ls -lh hiddenrisk-server.tar hiddenrisk-server-deploy.tar.gz

# 验证 tar 内部结构
tar -tzf hiddenrisk-server-deploy.tar.gz
```

预期输出应包含：
```
.hiddenrisk-server.tar
.docker-compose.yml
.env.example
.start-offline.sh
.stop-offline.sh
.README.txt
```

---

### Task 3: 传输部署包到 4090 服务器

**Files:**
- Transfer: `hiddenrisk-server-deploy.tar.gz` → `SERVER_IP:/opt/hiddenrisk/`

- [ ] **Step 1: 传输文件**

```bash
# 替换 SERVER_IP 为实际 4090 服务器 IP
scp hiddenrisk-server-deploy.tar.gz user@SERVER_IP:/tmp/
```

预期输出：文件传输完毕，无报错。

---

### Task 4: 在 4090 服务器上部署并启动服务

**Files:**
- Create: `/opt/hiddenrisk/.env`（在 4090 服务器上）
- Run: `/opt/hiddenrisk/start-offline.sh`

- [ ] **Step 1: SSH 到 4090 服务器并解压部署包**

```bash
ssh user@SERVER_IP

# 在服务器上执行
sudo mkdir -p /opt/hiddenrisk
sudo chown $USER:$USER /opt/hiddenrisk
tar -xzf /tmp/hiddenrisk-server-deploy.tar.gz -C /opt/hiddenrisk
cd /opt/hiddenrisk
ls -la
```

预期输出：目录包含 `.env.example`, `docker-compose.yml`, `start-offline.sh`, `stop-offline.sh`, `hiddenrisk-server.tar` 等。

- [ ] **Step 2: 配置环境变量**

```bash
cp .env.example .env

# 编辑 .env，写入实际值
cat > .env << 'EOF'
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-strong-password-here
SESSION_SECRET=your-random-secret-at-least-32-chars-xxxxxxxxx
EOF
```

- [ ] **Step 3: 启动服务**

```bash
./start-offline.sh
```

预期输出（关键行）：
```
[1/6] 检查 Docker 环境...
[2/6] 检查 .env 配置...
[3/6] 创建数据目录 /data/HiddenRiskGlass/data...
[4/6] 设置数据目录权限...
[5/6] 加载 Docker 镜像...
镜像加载完成
[6/6] 启动服务...
服务启动成功！
```

- [ ] **Step 4: 验证容器运行状态**

```bash
# 查看容器状态
docker ps --filter name=hiddenrisk-server

# 预期输出:
# CONTAINER ID   IMAGE                     COMMAND                  CREATED         STATUS         PORTS                     NAMES
# xxxxxxxxxxxx   hiddenrisk-server:latest  "python server.py --…"   X seconds ago   Up X seconds   0.0.0.0:10203->10203/tcp  hiddenrisk-server

# 查看容器日志
docker logs hiddenrisk-server --tail 20
```

预期日志内容应包含类似：
```
INFO:     Started server process [1]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:10203
```

---

### Task 5: 验证服务功能

**Files:**
- None（使用 curl 验证 API）

- [ ] **Step 1: 验证健康检查和登录页面**

```bash
# 健康检查（healthcheck）
curl -f http://localhost:10203/login -o /dev/null -w "%{http_code}"

# 预期输出: 200

# 检查返回的 HTML 包含登录表单
curl -s http://localhost:10203/login | head -5
```

预期输出包含 HTML 中的 `login` 相关标签。

- [ ] **Step 2: 验证更新检查 API**

```bash
# 无可用更新时的响应
curl -s "http://localhost:10203/api/v1/updates/check?nscode=test&currentVersionCode=1"

# 预期输出:
# {"updateAvailable":false}
```

- [ ] **Step 3: 验证管理后台登录**

```bash
# 模拟登录（使用 .env 中配置的密码）
curl -c /tmp/cookies.txt -X POST http://localhost:10203/login \
  -d "email=admin&password=your-strong-password-here" \
  -o /dev/null -w "%{http_code}"

# 预期输出: 303（重定向到 /admin）
# 或用 -L 跟随重定向:
curl -L -c /tmp/cookies.txt -b /tmp/cookies.txt -X POST http://localhost:10203/login \
  -d "email=admin&password=your-strong-password-here" \
  -o /dev/null -w "%{http_code}"
```

---

### Task 6: 更新 Android 客户端更新检查 URL

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt:86-87`

- [ ] **Step 1: 修改默认更新检查 URL**

将 `AppUpdateClient.kt` 中的 `DEFAULT_CHECK_URL` 和 `DEFAULT_MANIFEST_URL` 从旧的 `192.168.1.152` 改为 4090 服务器的新 IP。

```kotlin
// 修改前:
const val DEFAULT_CHECK_URL = "http://192.168.1.152:10203/api/v1/updates/check"
const val DEFAULT_MANIFEST_URL = "http://192.168.1.152:10203/releases/latest/update.json"

// 修改后 (替换 SERVER_IP 为实际 IP):
const val DEFAULT_CHECK_URL = "http://SERVER_IP:10203/api/v1/updates/check"
const val DEFAULT_MANIFEST_URL = "http://SERVER_IP:10203/releases/latest/update.json"
```

- [ ] **Step 2: 确认修改仅涉及常量**

```bash
# 确认没有其他文件硬编码旧 IP
grep -rn "192.168.1.152" app/src/
```

预期输出只显示 `AppUpdateClient.kt` 中的两处常量。如有其他处，一并更新。

- [ ] **Step 3: 构建 APK 验证编译通过**

```bash
./gradlew assembleDebug
```

预期输出：`BUILD SUCCESSFUL`。

---

### Task 7: 管理后台功能验证（可选 — 生产环境上线前）

**Files:**
- None（功能验证）

- [ ] **Step 1: 登录管理后台**

在浏览器访问 `http://SERVER_IP:10203/admin`，输入 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录。

- [ ] **Step 2: 上传一个测试 APK 验证发布流程**

1. 点击 "上传 APK" 选择一个测试 APK 文件
2. 填写 `versionCode`（如 1）、`versionName`（如 1.0.0）
3. 勾选 "设为默认版本"
4. 点击发布

- [ ] **Step 3: 验证更新检查返回新版本**

```bash
curl -s "http://SERVER_IP:10203/api/v1/updates/check?nscode=test&currentVersionCode=0"
```

预期输出包含 `"updateAvailable": true`。

---

## 自检清单

1. **Spec 覆盖检查**:
   - Task 1-2: ✅ 开发机上构建镜像和离线部署包
   - Task 3: ✅ 传输到 4090 服务器
   - Task 4: ✅ 在无外网服务器上部署启动
   - Task 5: ✅ API 功能验证
   - Task 6: ✅ Android 客户端 URL 更新
   - Task 7: ✅ 管理后台端到端验证

2. **Placeholder 扫描**: 无 `TBD`、`TODO`、`implement later` 等占位符。所有代码和命令都是完整的。

3. **类型一致性**: 全文中 `SERVER_IP` 作为占位符统一使用，Android 常量名 `DEFAULT_CHECK_URL` / `DEFAULT_MANIFEST_URL` 在 Task 6 的读写中一致。
