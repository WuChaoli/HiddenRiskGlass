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
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 8080
```

开发热重载：

```powershell
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 8080 -Reload
```

Python 入口点可以从仓库根目录或 `servers/HiddenRiskGlassServer` 目录运行：

```powershell
python .\servers\HiddenRiskGlassServer\server.py --host 127.0.0.1 --port 8080
cd .\servers\HiddenRiskGlassServer
python .\server.py --host 127.0.0.1 --port 8080 --reload
```

访问地址：

```text
http://127.0.0.1:8080/login
```

## 环境变量

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
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/updates/check?nscode=NSCODE-001&currentVersionCode=2"
```

当有可用更新时，返回：

```json
{
  "updateAvailable": true,
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "http://127.0.0.1:8080/releases/1/app.apk",
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

## 部署注意事项

- 务必设置强密码的 `ADMIN_PASSWORD` 和稳定的 `SESSION_SECRET`。
- 生产环境将 `APK_UPDATE_DATA_DIR` 设置为源码树之外的持久化目录。
- 当服务暴露到本地局域网测试之外时，通过 HTTPS 或受信任的内网反向代理提供服务。
- 启用 HTTPS 时，设置 `SESSION_COOKIE_SECURE=1`。
- 同时备份 `apk_update_server.sqlite3` 和 `releases/` 目录；数据库存储元数据和文件路径。
- 不要提交生成的 `apk_update_server.sqlite3`、上传的 `.apk` 文件、`.upload` 临时文件或 `__pycache__`。
