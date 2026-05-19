# APK 更新可部署服务器设计

## 目标

将当前 `tools/apk_update_server/` 中面向局域网测试的 APK 更新工具，演进为可部署到服务器上的设备更新服务。

第一版目标：

- 支持服务器长期运行。
- 支持后台登录后发布 APK。
- 支持默认最新版更新。
- 支持按眼镜 `nscode` 定向推送指定版本。
- 保留现有安卓端静态 manifest 兼容入口，降低迁移风险。

非目标：

- 不做多管理员账号和审计权限体系。
- 不做百分比灰度。
- 不做多实例部署。
- 不接入对象存储。
- 不接入公司统一登录。

## 当前上下文

现有服务位于 `tools/apk_update_server/`：

- `server.py`：Python 标准库 HTTP 服务，提供网页上传 APK 和生成 `releases/latest/update.json`。
- `generate_manifest.py`：命令行生成单一 `latest` manifest。
- `serve.ps1`：启动本地服务。
- `releases/latest/update.json`：安卓端当前检查更新的 manifest。
- `releases/latest/app.apk`：安卓端当前下载 APK 的路径。

安卓端当前通过 `AppUpdateClient.DEFAULT_MANIFEST_URL` 请求静态 manifest。后续动态检查需要改为携带眼镜 `nscode` 和当前版本号。

仓库中现有 `RokidSdkManager.getSerialNumber()` 已用于上报 `snCode`，可作为服务端规则字段 `nscode` 的来源。

## 推荐架构

采用 **FastAPI + SQLite + 本地文件存储**。

理由：

- FastAPI 适合把后台页面、管理接口、设备检查接口拆清楚。
- SQLite 足够支撑单实例部署和小团队使用，部署成本低。
- APK 先保存在服务器本地目录，便于从当前工具平滑迁移。
- 后续需要多实例时，可把 SQLite 替换为 PostgreSQL，把 APK 存储替换为 Nginx 静态目录或对象存储。

## 目录结构

新增 FastAPI 应用目录：

```text
tools/apk_update_server/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── db.py
│   ├── auth.py
│   ├── schemas.py
│   ├── services.py
│   ├── templates/
│   └── static/
├── releases/
├── generate_manifest.py
├── server.py
├── serve.ps1
└── README.md
```

职责：

- `main.py`：FastAPI 入口、路由注册、静态文件挂载。
- `db.py`：SQLite 连接、建表、轻量迁移。
- `auth.py`：管理员登录、session cookie 校验。
- `schemas.py`：请求和响应模型。
- `services.py`：发布 APK、计算 SHA-256、生成 manifest、匹配更新规则。
- `templates/`：后台 HTML 模板。
- `static/`：后台页面 CSS/JS。

`server.py` 第一阶段保留为兼容入口，可改为启动 FastAPI 或提示使用 `serve.ps1`。

## 数据模型

### `releases`

保存每个发布版本。

字段：

- `id`
- `version_code`
- `version_name`
- `apk_path`
- `apk_url`
- `sha256`
- `size_bytes`
- `release_notes`
- `mandatory`
- `status`：`draft`、`active`、`disabled`
- `created_at`

### `settings`

保存全局配置。

字段：

- `key`
- `value`

第一版使用：

- `default_release_id`

### `device_rules`

保存 `nscode` 定向推送规则。

字段：

- `id`
- `nscode`
- `release_id`
- `enabled`
- `note`
- `created_at`
- `updated_at`

约束：

- 第一版一个 `nscode` 只允许绑定一个启用规则。
- 不做规则优先级。

### `check_events`

记录设备检查结果，方便排查。

字段：

- `id`
- `nscode`
- `current_version_code`
- `matched_release_id`
- `result`：`update`、`no_update`、`no_release`
- `created_at`

不记录敏感请求头，不记录完整响应体。

## 文件存储

每个发布版本独立保存 APK：

```text
tools/apk_update_server/releases/<versionCode>_<versionName>/app.apk
```

发布时：

- 先写入临时文件。
- 校验文件非空。
- 计算 SHA-256 和文件大小。
- 再落库。
- 发布失败不覆盖已有可用版本。

兼容路径：

- `/releases/latest/update.json` 继续返回默认最新版 manifest。
- `/releases/latest/app.apk` 可保留为默认最新版 APK 的兼容下载路径，或在 README 中标记为旧客户端兼容入口。

新版本 APK 下载路径：

```text
/releases/{release_id}/app.apk
```

## 设备检查接口

### 请求

```text
GET /api/v1/updates/check?nscode=<nscode>&currentVersionCode=<versionCode>
```

参数：

- `nscode`：眼镜标识，来源为安卓端 `RokidSdkManager.getSerialNumber()`。
- `currentVersionCode`：当前安装版本号，必须为正整数。

### 匹配规则

1. 校验 `currentVersionCode`。
2. 如果 `nscode` 非空，查找启用状态的 `device_rules`。
3. 命中设备规则时，使用该规则绑定的 release。
4. 未命中时，使用 `settings.default_release_id`。
5. 如果没有可用 release，返回无更新。
6. 如果目标 release 的 `version_code <= currentVersionCode`，返回无更新。
7. 否则返回更新 manifest。

### 有更新响应

保持兼容当前 `AppUpdateInfo` 字段：

```json
{
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "https://example.com/releases/1/app.apk",
  "sha256": "...",
  "sizeBytes": 12345678,
  "releaseNotes": "本次更新说明",
  "mandatory": false
}
```

### 无更新响应

第一版建议返回：

```json
{
  "updateAvailable": false
}
```

安卓端动态接口接入时，需要能识别该响应。旧静态 manifest 接口不使用这个格式。

## 后台管理接口

公开接口：

- `GET /api/v1/updates/check`
- `GET /releases/{release_id}/app.apk`
- `GET /releases/latest/update.json`
- `GET /releases/latest/app.apk`

需要登录：

- `GET /admin`
- `POST /admin/releases`
- `POST /admin/default-release`
- `POST /admin/device-rules`
- `POST /admin/device-rules/{id}/delete`
- `GET /login`
- `POST /login`
- `POST /logout`

后台页面第一版能力：

- 查看发布版本列表。
- 上传 APK 并填写版本信息。
- 设置默认发布版本。
- 添加、查看、删除 `nscode -> release` 定向规则。
- 查看最近检查事件。

## 认证

第一版使用单管理员密码和 Cookie Session。

配置：

- `ADMIN_PASSWORD`：必填。未配置时服务拒绝启动，避免误部署裸后台。
- `SESSION_SECRET`：可选。生产部署必须固定配置；开发模式可自动生成。
- `SESSION_COOKIE_SECURE`：可选。HTTPS 反向代理后可启用。

Cookie：

- `HttpOnly`
- `SameSite=Lax`
- 可配置 `Secure`

设备检查接口不需要登录，但只返回必要更新信息。

## 安卓端迁移

第二期修改安卓端：

- `AppUpdateClient` 从静态 manifest URL 改为动态检查接口。
- 请求参数带：
  - `nscode = RokidSdkManager.getSerialNumber()`
  - `currentVersionCode = BuildConfig.VERSION_CODE`
- 动态接口返回有更新时继续映射为 `AppUpdateInfo`。
- 动态接口返回无更新时映射为 `AppUpdateCheckResult(null, currentVersionCode)`。
- 可保留静态 manifest fallback，便于服务器迁移期间回退。

日志要求：

- 打印 `nscode` 是否为空。
- 打印 HTTP 状态。
- 打印是否命中更新。
- 不打印完整 APK URL 以外的敏感信息。

## 部署方式

第一版按单实例部署：

- FastAPI 使用 `uvicorn` 启动。
- SQLite 数据库和 APK 文件目录放在持久化目录。
- Nginx 负责 TLS 和反向代理。
- APK 下载流量较大时，后续可由 Nginx 直接托管 `releases/` 目录。

示例环境变量：

```text
ADMIN_PASSWORD=<strong-password>
SESSION_SECRET=<random-secret>
APK_UPDATE_DATA_DIR=/var/lib/apk-update-server
```

Windows 本地开发仍可通过 `serve.ps1` 启动。

## 分期计划

### 第一期：服务端 FastAPI 化

- 新增 FastAPI 应用目录。
- 新增 SQLite 初始化。
- 实现登录和 session。
- 实现 APK 发布。
- 实现默认发布版本。
- 实现 `nscode` 定向规则。
- 实现动态检查接口。
- 保留旧静态兼容入口。
- 更新 `serve.ps1` 和 README。

### 第二期：安卓端动态检查

- `AppUpdateClient` 请求动态检查接口。
- 接入 `RokidSdkManager.getSerialNumber()` 作为 `nscode`。
- 支持无更新响应。
- 保留静态 manifest fallback。
- 真机验证默认更新和 `nscode` 定向更新。

## 测试与验证

服务端自动化测试：

- 未登录不能访问后台发布。
- 登录成功后可访问后台。
- 登录后可上传 APK。
- 上传后 `sha256` 与实际 APK 一致。
- 设置默认 release 后，未命中 `nscode` 的设备返回默认版本。
- 设置 `nscode` 定向规则后，目标设备返回定向版本。
- 非目标设备不返回定向版本。
- `currentVersionCode >= target.version_code` 时返回无更新。
- 没有可用 release 时返回无更新。
- `/releases/latest/update.json` 返回默认最新版 manifest。

手动验证：

- `serve.ps1` 可启动 FastAPI 服务。
- 浏览器可登录后台。
- 后台可上传 APK。
- 后台可配置默认版本和 `nscode` 规则。
- `Invoke-RestMethod` 可检查动态接口。

安卓端验证：

- Gradle 编译通过。
- 真机默认最新版能弹更新。
- 指定 `nscode` 的眼镜能收到定向版本。
- 非指定 `nscode` 的眼镜不收到定向版本。
- APK 下载后 SHA-256 校验通过。
- 系统安装器仍能拉起。

## 风险与后续演进

风险：

- SQLite + 本地 APK 文件不适合多实例部署。
- 简单管理员密码不提供多用户审计。
- `nscode` 为空时只能走默认版本。
- 兼容旧静态入口期间，需要明确旧客户端不会使用设备定向规则。

后续演进：

- 数据库迁移到 PostgreSQL。
- APK 存储迁移到对象存储或 Nginx 静态目录。
- 增加发布回滚按钮。
- 增加百分比灰度。
- 增加多用户和操作审计。
- 增加设备列表导入和批量绑定规则。
