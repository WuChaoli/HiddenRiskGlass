# HiddenRiskGlassServer Docker 离线部署方案

## 项目概述

将 `servers/HiddenRiskGlassServer` 打包为 Docker 镜像，支持在离线 Linux x86 服务器（Kylin V10）上一键部署运行。目标服务器无外网访问能力，所有依赖必须在联网构建机上预先打包。

## 技术方案

### 镜像设计

- **基础镜像**：`python:3.12-slim-bookworm`
  - 选用 Debian Bookworm 系 slim 镜像，glibc 兼容性好，避免 Alpine 的 musl 兼容问题
  - 镜像体积约 150MB（含 Python + 依赖），可接受
- **运行用户**：非 root（`appuser:appgroup`，UID/GID=1000）
- **工作目录**：`/app`
- **暴露端口**：`8080`
- **数据目录**：容器内 `/app/data/`，挂载宿主机卷

### 数据持久化

- **宿主机路径**：`/data/HiddenRiskGlass/data/`
- **容器内路径**：`/app/data/`
- **包含内容**：
  - `apk_update_server.sqlite3` — SQLite 数据库
  - `releases/` — 上传的 APK 文件
- **权限**：宿主机目录需 `chown 1000:1000`，确保容器内非 root 用户可读写

### 环境变量配置

通过 `.env` 文件注入，打包在交付包内：

| 变量 | 说明 | 必填 |
|------|------|------|
| `ADMIN_PASSWORD` | 管理后台登录密码 | 是 |
| `SESSION_SECRET` | Cookie 签名密钥（建议固定值） | 是 |
| `SESSION_COOKIE_SECURE` | 是否仅 HTTPS 传输 Cookie | 否 |
| `APK_UPDATE_DATA_DIR` | 数据目录（固定为 `/app/data/`） | 否 |

`.env` 文件由部署人员填写后放置于启动脚本同级目录。

## 交付物清单

| 文件 | 路径 | 说明 |
|------|------|------|
| `Dockerfile` | `servers/HiddenRiskGlassServer/Dockerfile` | 镜像构建定义 |
| `.dockerignore` | `servers/HiddenRiskGlassServer/.dockerignore` | 排除不需要打包的文件 |
| `docker-compose.yml` | `servers/HiddenRiskGlassServer/docker-compose.yml` | 容器编排定义（端口、卷、环境变量） |
| `build-offline.sh` | `servers/HiddenRiskGlassServer/scripts/build-offline.sh` | 联网构建机执行：构建 + 导出 tar |
| `start-offline.sh` | `servers/HiddenRiskGlassServer/scripts/start-offline.sh` | 离线服务器执行：加载镜像 + 启动服务 |
| `stop-offline.sh` | `servers/HiddenRiskGlassServer/scripts/stop-offline.sh` | 离线服务器执行：停止服务 |

## 离线部署流程

### 第一步：联网构建机执行

```bash
cd servers/HiddenRiskGlassServer
bash scripts/build-offline.sh
```

脚本完成以下操作：
1. `docker build -t hiddenrisk-server:latest .`
2. `docker save hiddenrisk-server:latest -o hiddenrisk-server.tar`
3. 将 tar 文件、启动脚本、docker-compose.yml、.env 模板打包为 `hiddenrisk-server-deploy.tar.gz`

### 第二步：传输到离线服务器

通过 U 盘、内网 FTP 或其他物理/安全方式，将 `hiddenrisk-server-deploy.tar.gz` 传输到 Kylin V10 服务器。

### 第三步：离线服务器执行

```bash
tar -xzf hiddenrisk-server-deploy.tar.gz
cd hiddenrisk-server-deploy/
# 编辑 .env 文件，填写 ADMIN_PASSWORD 和 SESSION_SECRET
vim .env
# 首次启动（自动创建宿主机数据目录）
bash start-offline.sh
```

`start-offline.sh` 完成以下操作：
1. 检查 Docker 是否安装
2. `mkdir -p /data/HiddenRiskGlass/data/`
3. `chown 1000:1000 /data/HiddenRiskGlass/data/`
4. `docker load -i hiddenrisk-server.tar`
5. `docker-compose up -d`
6. 输出访问地址

## 运维操作

| 操作 | 命令 |
|------|------|
| 查看日志 | `docker-compose logs -f` |
| 停止服务 | `bash stop-offline.sh` 或 `docker-compose down` |
| 重启服务 | `docker-compose restart` |
| 更新版本 | 重新执行构建 → 传输 → `docker-compose down && bash start-offline.sh` |
| 备份数据 | 备份 `/data/HiddenRiskGlass/data/` 目录 |

## 约束与注意事项

1. **Docker 前提**：离线服务器需预先安装 Docker 和 Docker Compose（通常内网服务器已具备）。
2. **端口冲突**：默认映射宿主机 `8080` 端口，如被占用需修改 `docker-compose.yml`。
3. **数据安全**：`releases/` 目录下的 APK 文件和 SQLite 数据库需定期备份。
4. **镜像体积**：约 200-250MB，包含完整 Python 运行时和所有依赖。
