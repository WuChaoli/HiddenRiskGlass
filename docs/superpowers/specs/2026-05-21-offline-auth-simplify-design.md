# HiddenRiskGlassServer 离线部署认证精简与端口变更设计

## 背景

服务器需要部署到内网离线环境，无法使用 SMTP 发送邮件验证码。同时，内网场景只需要单一管理员账户，无需多用户注册、邮箱验证、找回密码等复杂功能。

## 目标

1. 关闭 SMTP 机制，删除所有邮件发送相关代码
2. 取消邮箱验证码机制
3. 回归单管理员认证模式：通过 `.env` 配置用户名和密码
4. 将服务端口从 `8080` 统一改为 `10203`（代码、文档、眼镜端接口）

## 方案概述

采用**彻底精简方案**：删除所有与多用户、邮箱验证码相关的代码和数据表，回归最简洁的单管理员模式。

## 详细设计

### 一、认证与账户体系精简

#### 1. `app/config.py`

- **删除**所有 SMTP 相关字段：
  - `smtp_host`, `smtp_port`, `smtp_user`, `smtp_pass`, `smtp_from`, `smtp_tls`
- **新增**字段：
  - `admin_username: str` — 从环境变量 `ADMIN_USERNAME` 读取，默认值为 `"admin"`
- **保留**字段：
  - `admin_password: str` — 仍为必填
  - `session_secret`, `session_cookie_secure`, `data_dir` 等

#### 2. `app/auth.py`

- **保留**函数：
  - `is_logged_in(request)` — 检查 session 中是否有登录标记
  - `mark_logged_in(request)` — 标记为已登录
  - `mark_logged_out(request)` — 清除登录标记
  - `require_admin(request)` — 未登录时重定向到 `/login`
  - `verify_password(settings, password)` — 使用 `secrets.compare_digest` 比对环境变量 `ADMIN_PASSWORD`
- **删除**函数：
  - `get_current_user_id` — 不再有多用户概念
  - `verify_user_password` — 不再使用数据库存储密码
  - `has_any_admin` — 不再通过数据库判断是否存在管理员
  - `hash_password` — 不再使用 bcrypt 哈希

#### 3. `app/user_services.py`

- **整文件删除**
- 删除内容：验证码生成与验证、用户注册、找回密码、修改密码、用户信息查询等全部多用户相关逻辑

#### 4. `app/mailer.py`

- **整文件删除**
- 删除内容：SMTP 连接、邮件模板、验证码邮件发送等全部邮件相关逻辑

#### 5. `app/db.py`

- **从 `SCHEMA` 中删除**以下表定义：
  - `users` 表
  - `verification_codes` 表
- **保留**表：
  - `releases` — APK 发布版本
  - `settings` — 系统设置
  - `device_rules` — 设备分配规则
  - `check_events` — 更新检查日志

#### 6. `app/main.py`

路由调整：

- **删除路由**：
  - `GET /register` — 不再支持注册
  - `POST /register` — 同上
  - `GET /forgot-password` — 不再支持找回密码
  - `POST /forgot-password` — 同上
  - `GET /profile` — 不再有个人资料页
  - `POST /profile/password` — 同上
  - `POST /verify-code` — 不再发送验证码
- **修改路由**：
  - `POST /login`：从 `verify_user_password(resolved_settings, email, password)` 改为 `verify_password(resolved_settings, password)`，只验证密码，不再验证邮箱
  - `GET /`：从判断 `has_any_admin` 决定跳转到 `/register` 或 `/admin`，改为直接重定向到 `/login`
- **删除导入**：
  - `get_current_user_id`, `has_any_admin`, `verify_user_password`
  - `register_user`, `reset_password`, `send_verification_code`, `change_password`, `get_user_by_id`

### 二、端口 8080 → 10203 变更

#### 服务器代码

| 文件 | 修改内容 |
|------|----------|
| `server.py` | `default=8080` → `default=10203` |
| `serve.ps1` | `[int]$Port = 8080` → `[int]$Port = 10203` |
| `Dockerfile` | `EXPOSE 8080` → `EXPOSE 10203`；`--port 8080` → `--port 10203` |
| `docker-compose.yml` | `"8080:8080"` → `"10203:10203"`；healthcheck URL `:8080` → `:10203` |

#### 部署脚本

| 文件 | 修改内容 |
|------|----------|
| `scripts/build-offline.sh` | 生成的 `README.txt` 中 `:8080` → `:10203` |
| `scripts/start-offline.sh` | 启动成功提示中的访问地址 `:8080` → `:10203` |

#### 当前文档

| 文件 | 修改内容 |
|------|----------|
| `README.txt` | 所有 `:8080` → `:10203` |
| `README.md` | 所有 `:8080` → `:10203` |

#### 前端模板

| 文件 | 修改内容 |
|------|----------|
| `app/templates/admin.html` | `custom-endpoint` placeholder 中的 `:8080` → `:10203` |

#### 测试文件

| 文件 | 修改内容 |
|------|----------|
| `tests/test_services.py` | `127.0.0.1:8080` → `127.0.0.1:10203` |

#### 眼镜端 Android 代码

| 文件 | 修改内容 |
|------|----------|
| `AppUpdateClient.kt` | `DEFAULT_CHECK_URL` 和 `DEFAULT_MANIFEST_URL` 中的 `:8080` → `:10203` |

#### 不修改的范围

- `docs/superpowers/plans/`、`docs/superpowers/specs/`（本文件除外）、`docs/superpowers/archived/` 下的历史设计文档和计划文件保持原样，这些是过去时点的快照，不应追溯修改。

### 三、前端页面调整

#### 删除的模板文件

- `app/templates/register.html` — 不再支持注册
- `app/templates/forgot_password.html` — 不再支持找回密码
- `app/templates/profile.html` — 不再有个人资料页

#### 修改的模板文件

**`app/templates/login.html`**：
- 邮箱输入框 `type="email"` → `type="text"`
- placeholder 从 `admin@example.com` → 用户名提示
- 删除底部的"忘记密码？"链接

**`app/templates/admin.html`**：
- 删除指向个人资料或注册页面的导航链接（如果存在）

### 四、环境变量与文档更新

#### `.env.example`

- **删除**所有 SMTP 相关配置项
- **新增**：
  ```bash
  ADMIN_USERNAME=admin
  ```
- **保留**：
  ```bash
  ADMIN_PASSWORD=your-strong-password-here
  SESSION_SECRET=replace-with-a-long-random-secret-at-least-32-chars
  ```

#### `.env`（当前已有文件）

- 同步更新：去掉 SMTP 配置，添加 `ADMIN_USERNAME`，保留 `ADMIN_PASSWORD` 和 `SESSION_SECRET`

#### `README.md`

- 删除"邮件验证码配置（生产环境）"章节
- 更新所有端口引用 `8080` → `10203`
- 更新认证说明：从"管理员登录密码"改为"管理员用户名和密码"
- 更新环境变量列表：去掉 SMTP 项，新增 `ADMIN_USERNAME`
- 删除 JSON 配置中 `verification_code_*` 相关字段的说明（这些字段虽仍在 `config.json` 中但不再生效）

### 五、`config.json` 处理

`config.json` 中保留 `verification_code_length`、`verification_code_expires_minutes`、`verification_code_send_cooldown_seconds` 等字段，但代码中不再读取和使用。这些字段可以保留在 JSON 文件中不影响功能，也可以后续清理。

## 测试策略

1. 本地启动：`python server.py`（应默认监听 10203）
2. 登录测试：使用 `.env` 中配置的 `ADMIN_USERNAME` + `ADMIN_PASSWORD` 登录 `/login`
3. API 测试：`curl http://127.0.0.1:10203/api/v1/updates/check?nscode=test&currentVersionCode=1`
4. 管理后台：登录后访问 `/admin`，验证 APK 上传、版本管理、设备规则等功能正常
5. Docker 测试：`docker compose up` 后访问 `http://localhost:10203/login`

## 风险与回滚

| 风险 | 缓解措施 |
|------|----------|
| 删除代码后功能遗漏 | 逐项对照设计文档检查，删除前先确认无其他引用 |
| 端口变更后眼镜端无法连接 | 同步更新 `AppUpdateClient.kt` 中的默认 URL |
| 旧数据库中 `users` 和 `verification_codes` 表残留 | 不影响功能，属于废弃数据，可手动清理或重建数据库 |

## 变更清单

### 修改的文件

- `app/config.py`
- `app/auth.py`
- `app/db.py`
- `app/main.py`
- `server.py`
- `serve.ps1`
- `Dockerfile`
- `docker-compose.yml`
- `scripts/build-offline.sh`
- `scripts/start-offline.sh`
- `README.txt`
- `README.md`
- `.env.example`
- `.env`
- `app/templates/login.html`
- `app/templates/admin.html`
- `tests/test_services.py`
- `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`

### 删除的文件

- `app/user_services.py`
- `app/mailer.py`
- `app/templates/register.html`
- `app/templates/forgot_password.html`
- `app/templates/profile.html`
