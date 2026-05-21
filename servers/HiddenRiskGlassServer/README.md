# HiddenRiskGlassServer

HiddenRiskGlassServer 是一个基于 FastAPI 的小型服务器，用于局域网或内网的 APK 更新测试。它支持管理员登录、APK 上传、默认版本管理、按 `nscode` 分配的版本规则、更新检查日志，以及 Android 客户端使用的兼容端点。

## 安装依赖

从仓库根目录使用 Python 3.11+：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r .\servers\HiddenRiskGlassServer\requirements.txt
```

## 本地启动

启动服务器前需设置管理员密码：

```powershell
$env:ADMIN_PASSWORD = "change-me"
$env:SESSION_SECRET = "replace-with-a-long-random-secret"
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 10203
```

开发热重载：

```powershell
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 10203 -Reload
```

Python 入口点可以从仓库根目录或 `servers/HiddenRiskGlassServer` 目录运行：

```powershell
python .\servers\HiddenRiskGlassServer\server.py --host 127.0.0.1 --port 10203
cd .\servers\HiddenRiskGlassServer
python .\server.py --host 127.0.0.1 --port 10203 --reload
```

访问地址：

```text
http://127.0.0.1:10203/login
```

## 环境变量

- `ADMIN_USERNAME`：**可选**。管理后台的登录用户名，默认为 `admin`。
- `ADMIN_PASSWORD`：**必填**。管理后台的登录密码。
- `SESSION_SECRET`：**建议填写**。用于签名管理员会话 Cookie 的密钥。如果留空，每次启动会生成随机密钥，导致重启后会话失效。
- `SESSION_COOKIE_SECURE`：**可选**。仅通过 HTTPS 提供服务时，设置为 `1`、`true` 或 `yes`。
- `APK_UPDATE_DATA_DIR`：**可选**。`apk_update_server.sqlite3` 和上传的发布文件的存放目录。默认值为 `servers/HiddenRiskGlassServer`。

## 更新检查 API

Android 客户端应调用：

```text
GET /api/v1/updates/check?nscode=<nscode>&currentVersionCode=<versionCode>
```

示例：

```powershell
Invoke-RestMethod "http://127.0.0.1:10203/api/v1/updates/check?nscode=NSCODE-001&currentVersionCode=2"
```

当有可用更新时，返回：

```json
{
  "updateAvailable": true,
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "http://127.0.0.1:10203/releases/1/app.apk",
  "sha256": "...",
  "sizeBytes": 123,
  "releaseNotes": "notes",
  "mandatory": false
}
```

当当前版本已是最新时：

```json
{
  "updateAvailable": false
}
```

`currentVersionCode` 必须是正整数。`nscode` 可以为空，但按设备分配的规则仅匹配非空值。

## 兼容端点

以下端点用于兼容旧的本地更新流程：

- `GET /releases/latest/update.json`：返回当前默认版本的清单，无默认版本时返回 `404`。
- `GET /releases/latest/app.apk`：下载当前默认版本的 APK，无默认版本时返回 `404`。
- `GET /releases/{release_id}/app.apk`：下载指定已上传版本的 APK。

## 管理功能

登录后可在 `/admin` 访问管理后台，支持：

- 上传 APK 发布包，填写 `versionCode`、`versionName`、更新说明、是否强制更新。
- 将已上传的版本设为默认版本。
- 创建 `nscode` 规则，将指定设备码路由到指定版本。
- 删除 `nscode` 规则。
- 查看最近的更新检查事件。

未登录的管理请求会重定向到 `/login`。

## JSON 配置

可以通过 `servers/HiddenRiskGlassServer/config.json` 自定义技术参数：

```json
{
  "server_name": "HiddenRiskGlassServer",
  "auth": {
    "verification_code_length": 6,
    "verification_code_expires_minutes": 15,
    "verification_code_send_cooldown_seconds": 60,
    "password_min_length": 8
  },
  "upload": {
    "chunk_size_bytes": 1048576
  }
}
```

环境变量的优先级高于 JSON 配置。例如，`SERVER_NAME` 会覆盖 `server_name`。

## Docker 部署

### 快速启动（需本地安装 Docker）

```bash
cd servers/HiddenRiskGlassServer

# 1. 复制环境变量模板并填写配置
cp .env.example .env
# 编辑 .env，设置 ADMIN_PASSWORD 和 SESSION_SECRET

# 2. 构建镜像
docker build -t hiddenrisk-server:latest .

# 3. 启动服务（前台运行，Ctrl+C 停止）
docker compose up

# 或后台运行
docker compose up -d
```

访问 `http://localhost:10203` 即可使用。

### 离线服务器部署（无外网环境）

适用于 Kylin V10 等无外网 Linux 服务器的一键部署：

**联网机构建打包：**

```bash
cd servers/HiddenRiskGlassServer
bash scripts/build-offline.sh
```

构建完成后生成两个交付物：
- `hiddenrisk-server.tar` — Docker 镜像导出文件
- `hiddenrisk-server-deploy.tar.gz` — 完整离线部署包

**离线机部署：**

1. 将 `hiddenrisk-server-deploy.tar.gz` 传输到目标服务器并解压
2. 复制 `.env.example` 为 `.env`，填写 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 和 `SESSION_SECRET`
3. 执行启动脚本：

```bash
./start-offline.sh
```

4. 访问 `http://<服务器IP>:10203`

**离线机停止服务：**

```bash
./stop-offline.sh
```

### Docker 部署文件说明

| 文件 | 说明 |
|------|------|
| `Dockerfile` | 镜像构建定义，基于 `python:3.12-slim-bookworm`，非 root 用户运行 |
| `.dockerignore` | 排除缓存、测试、运行时数据等不需要打包进镜像的文件 |
| `docker-compose.yml` | 服务编排：端口映射 10203、数据卷挂载、健康检查 |
| `.env.example` | 环境变量模板，包含用户名、密码、密钥等配置项 |
| `scripts/build-offline.sh` | 联网机执行：构建镜像 + 导出 tar + 打包交付物 |
| `scripts/start-offline.sh` | 离线机执行：检查 Docker、加载镜像、启动容器 |
| `scripts/stop-offline.sh` | 离线机执行：停止并移除容器 |

### 数据持久化

Docker 部署时，数据通过 volume 挂载到宿主机：
- 容器内路径：`/app/data`
- 宿主机路径（Linux）：`/data/HiddenRiskGlass/data`
- 宿主机路径（Windows 本地测试）：`./data`

该目录包含：
- `apk_update_server.sqlite3` — SQLite 数据库
- `releases/` — 上传的 APK 文件

## 部署注意事项

- 务必设置强密码的 `ADMIN_PASSWORD` 和稳定的 `SESSION_SECRET`。
- 生产环境将 `APK_UPDATE_DATA_DIR` 设置为源码树之外的持久化目录。
- 当服务暴露到本地局域网测试之外时，通过 HTTPS 或受信任的内网反向代理提供服务。
- 启用 HTTPS 时，设置 `SESSION_COOKIE_SECURE=1`。
- 同时备份 `apk_update_server.sqlite3` 和 `releases/` 目录；数据库存储元数据和文件路径。
- 不要提交生成的 `apk_update_server.sqlite3`、上传的 `.apk` 文件、`.upload` 临时文件或 `__pycache__`。
