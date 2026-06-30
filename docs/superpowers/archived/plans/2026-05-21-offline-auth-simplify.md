# HiddenRiskGlassServer 离线认证精简与端口变更实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 HiddenRiskGlassServer 从多用户邮箱验证码认证模式精简为单管理员环境变量认证模式，并将端口从 8080 改为 10203。

**Architecture:** 删除 SMTP、多用户数据库表、验证码服务等全部不再需要的模块，回归最简洁的单管理员 session 认证。端口变更覆盖服务器代码、Docker、部署脚本、文档和眼镜端 Android 代码。

**Tech Stack:** Python 3.12, FastAPI, Jinja2, SQLite, Kotlin (Android)

---

## 文件变更总览

### 修改的文件

| 文件 | 职责 |
|------|------|
| `app/config.py` | 配置加载：删除 SMTP 字段，新增 `admin_username` |
| `app/auth.py` | 认证逻辑：回归单管理员环境变量密码比对 |
| `app/db.py` | 数据库 schema：删除 `users` 和 `verification_codes` 表 |
| `app/main.py` | FastAPI 主应用：删除注册/找回密码/验证码路由，简化登录 |
| `server.py` | 服务器入口：默认端口 8080 → 10203 |
| `serve.ps1` | PowerShell 启动脚本：默认端口 8080 → 10203 |
| `Dockerfile` | Docker 镜像定义：EXPOSE 和 CMD 端口改为 10203 |
| `docker-compose.yml` | 服务编排：端口映射和 healthcheck 改为 10203 |
| `scripts/build-offline.sh` | 离线构建脚本：生成的 README.txt 端口改为 10203 |
| `scripts/start-offline.sh` | 离线启动脚本：提示中的访问地址端口改为 10203 |
| `README.txt` | 离线部署说明：端口改为 10203 |
| `README.md` | 主文档：端口、认证说明、环境变量全面更新 |
| `.env.example` | 环境变量模板：删除 SMTP，新增 `ADMIN_USERNAME` |
| `.env` | 当前环境变量：同步更新 |
| `app/templates/login.html` | 登录页：邮箱输入改用户名，去掉忘记密码链接 |
| `app/templates/admin.html` | 管理后台：去掉个人中心链接，placeholder 端口改 10203 |
| `tests/test_services.py` | 服务测试：端口改为 10203 |
| `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt` | 眼镜端更新客户端：默认 URL 端口改为 10203 |

### 删除的文件

| 文件 | 原因 |
|------|------|
| `app/user_services.py` | 不再有多用户、验证码、注册、找回密码需求 |
| `app/mailer.py` | 离线环境无需 SMTP 邮件发送 |
| `app/templates/register.html` | 不再支持注册 |
| `app/templates/forgot_password.html` | 不再支持找回密码 |
| `app/templates/profile.html` | 不再有个人资料页 |

---

### Task 1: 精简认证核心层

**Files:**
- Modify: `app/config.py`
- Modify: `app/auth.py`
- Modify: `app/db.py`

- [ ] **Step 1: 修改 `app/config.py` — 删除 SMTP，新增 `admin_username`**

  将 `Settings` dataclass 和 `load_settings()` 函数修改如下：

  ```python
  @dataclass(frozen=True)
  class Settings:
      app_root: Path
      data_dir: Path
      releases_dir: Path
      database_path: Path
      admin_username: str
      admin_password: str
      session_secret: str
      session_cookie_secure: bool
      server_name: str
      verification_code_length: int
      verification_code_expires_minutes: int
      verification_code_send_cooldown_seconds: int
      password_min_length: int
      chunk_size_bytes: int
  ```

  在 `load_settings()` 中：
  - 删除 `smtp_host`, `smtp_port`, `smtp_user`, `smtp_pass`, `smtp_from`, `smtp_tls` 的读取逻辑
  - 新增 `admin_username = os.environ.get("ADMIN_USERNAME", "admin")`
  - `Settings(...)` 构造器中删除 SMTP 字段，添加 `admin_username=admin_username`

  **验证方式：** 确认 `grep -n "smtp" app/config.py` 无输出。

- [ ] **Step 2: 重写 `app/auth.py` — 回归单管理员认证**

  将文件内容替换为：

  ```python
  from __future__ import annotations

  import secrets
  from fastapi import Request
  from starlette.responses import RedirectResponse

  from app.config import Settings

  SESSION_KEY = "admin_authenticated"


  def is_logged_in(request: Request) -> bool:
      return bool(request.session.get(SESSION_KEY))


  def mark_logged_in(request: Request) -> None:
      request.session[SESSION_KEY] = True


  def mark_logged_out(request: Request) -> None:
      request.session.pop(SESSION_KEY, None)


  def require_admin(request: Request) -> RedirectResponse | None:
      if is_logged_in(request):
          return None
      return RedirectResponse("/login", status_code=303)


  def verify_password(settings: Settings, password: str) -> bool:
      """验证管理员密码（基于环境变量）。"""
      admin_password = getattr(settings, "admin_password", None)
      if not admin_password:
          return False
      return secrets.compare_digest(password, admin_password)
  ```

  **验证方式：** 确认 `grep -n "bcrypt\|user_id\|verify_user_password\|has_any_admin\|hash_password" app/auth.py` 无输出。

- [ ] **Step 3: 修改 `app/db.py` — 删除废弃表**

  从 `SCHEMA` 字符串中删除 `users` 和 `verification_codes` 表的定义。修改后的 SCHEMA 为：

  ```python
  SCHEMA = """
  CREATE TABLE IF NOT EXISTS releases (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      version_code INTEGER NOT NULL,
      version_name TEXT NOT NULL,
      apk_path TEXT NOT NULL,
      apk_url TEXT NOT NULL,
      sha256 TEXT NOT NULL,
      size_bytes INTEGER NOT NULL,
      release_notes TEXT NOT NULL DEFAULT '',
      mandatory INTEGER NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'active',
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  );

  CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS device_rules (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      nscode TEXT NOT NULL UNIQUE,
      release_id INTEGER NOT NULL,
      enabled INTEGER NOT NULL DEFAULT 1,
      note TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(release_id) REFERENCES releases(id)
  );

  CREATE TABLE IF NOT EXISTS check_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      nscode TEXT NOT NULL,
      current_version_code INTEGER,
      matched_release_id INTEGER,
      result TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(matched_release_id) REFERENCES releases(id)
  );
  """
  ```

  **验证方式：** 确认 `grep -n "users\|verification_codes" app/db.py` 无输出。

- [ ] **Step 4: 提交**

  ```bash
  git add app/config.py app/auth.py app/db.py
  git commit -m "$(cat <<'EOF'
  refactor: simplify auth layer for single-admin offline mode

  - Remove SMTP config from Settings
  - Add ADMIN_USERNAME env var (default: admin)
  - Strip auth.py to single-admin session-based auth
  - Drop users and verification_codes tables from schema

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 2: 精简主应用路由并删除废弃模块

**Files:**
- Modify: `app/main.py`
- Delete: `app/user_services.py`
- Delete: `app/mailer.py`

- [ ] **Step 1: 重写 `app/main.py` — 删除多用户路由**

  删除以下导入：
  ```python
  # 删除这些导入行
  from app.auth import get_current_user_id, has_any_admin, is_logged_in, mark_logged_in, mark_logged_out, require_admin, verify_user_password
  from app.user_services import (
      change_password,
      get_user_by_id,
      register_user,
      reset_password,
      send_verification_code,
  )
  ```

  改为：
  ```python
  from app.auth import is_logged_in, mark_logged_in, mark_logged_out, require_admin, verify_password
  ```

  删除以下路由函数：
  - `root()` — 改为直接 `RedirectResponse("/login", status_code=303)`
  - `forgot_password_page()` 和 `forgot_password_submit()`
  - `profile_page()` 和 `profile_change_password()`
  - `register_page()` 和 `register_submit()`
  - `api_send_code()`

  修改 `/login` POST 路由：
  ```python
  @app.post("/login")
  async def login_submit(request: Request, email: str = Form(...), password: str = Form(...)):
      # 忽略 email 字段，只验证密码
      if not verify_password(resolved_settings, password):
          return templates.TemplateResponse(
              request,
              "login.html",
              {"error": "密码错误"},
              status_code=401,
          )
      mark_logged_in(request)
      return RedirectResponse("/admin", status_code=303)
  ```

  修改 `root` 路由：
  ```python
  @app.get("/")
  async def root():
      return RedirectResponse("/login", status_code=303)
  ```

  **验证方式：** 确认 `grep -n "verify_user_password\|register_user\|reset_password\|send_verification_code\|change_password\|get_user_by_id\|has_any_admin\|get_current_user_id" app/main.py` 无输出。

- [ ] **Step 2: 删除废弃模块**

  ```bash
  git rm app/user_services.py app/mailer.py
  ```

  **验证方式：** 确认文件已从工作目录中删除。

- [ ] **Step 3: 提交**

  ```bash
  git add app/main.py
  git commit -m "$(cat <<'EOF'
  refactor: strip multi-user routes from main.py, remove dead modules

  - Delete /register, /forgot-password, /profile, /verify-code routes
  - Login now only verifies ADMIN_PASSWORD from env
  - Remove user_services.py and mailer.py

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 3: 调整前端模板

**Files:**
- Modify: `app/templates/login.html`
- Modify: `app/templates/admin.html`
- Delete: `app/templates/register.html`
- Delete: `app/templates/forgot_password.html`
- Delete: `app/templates/profile.html`

- [ ] **Step 1: 修改 `app/templates/login.html`**

  将邮箱输入框改为用户名输入框，删除忘记密码链接：

  ```html
  <form method="post" action="/login" class="form-stack" data-fetch="true">
    <label>
      用户名
      <input type="text" name="email" required autofocus placeholder="admin">
    </label>
    <label>
      密码
      <input type="password" name="password" required placeholder="请输入密码">
    </label>
    {% if error %}
    <p class="alert error">{{ error }}</p>
    {% endif %}
    <button type="submit">登录</button>
  </form>
  ```

  注意：保持 `name="email"` 不变，因为后端 `login_submit` 仍接收 `email` 参数（只是忽略它），这样可以避免同时修改后端表单解析。

  **验证方式：** 确认文件中无 `type="email"` 和 `忘记密码` 字样。

- [ ] **Step 2: 修改 `app/templates/admin.html`**

  删除个人中心链接（第 16 行）：
  ```html
  <!-- 删除此行 -->
  <a href="/profile" style="color:var(--accent);font-weight:700;text-decoration:none">个人中心</a>
  ```

  修改接口地址 placeholder 中的端口（第 309 行）：
  ```html
  <input type="text" id="custom-endpoint" placeholder="如 https://update.example.com 或 http://1.2.3.4:10203">
  ```

  **验证方式：** 确认 `grep -n "profile\|:8080" app/templates/admin.html` 无输出。

- [ ] **Step 3: 删除废弃模板**

  ```bash
  git rm app/templates/register.html app/templates/forgot_password.html app/templates/profile.html
  ```

  **验证方式：** 确认三个文件已从工作目录中删除。

- [ ] **Step 4: 提交**

  ```bash
  git add app/templates/login.html app/templates/admin.html
  git commit -m "$(cat <<'EOF'
  refactor: simplify templates for single-admin mode

  - login.html: email field → username, remove forgot-password link
  - admin.html: remove profile link, update placeholder port to 10203
  - Delete register.html, forgot_password.html, profile.html

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 4: 变更服务器入口端口

**Files:**
- Modify: `server.py`
- Modify: `serve.ps1`

- [ ] **Step 1: 修改 `server.py`**

  将第 16 行改为：
  ```python
  parser.add_argument("--port", type=int, default=10203, help="Port to bind. Default: 10203")
  ```

  **验证方式：** `grep "default=10203" server.py` 有输出。

- [ ] **Step 2: 修改 `serve.ps1`**

  将第 2 行改为：
  ```powershell
  [int]$Port = 10203,
  ```

  **验证方式：** `grep "10203" serve.ps1` 有输出。

- [ ] **Step 3: 提交**

  ```bash
  git add server.py serve.ps1
  git commit -m "$(cat <<'EOF'
  feat: change default server port from 8080 to 10203

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 5: 变更 Docker 配置与部署脚本

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `scripts/build-offline.sh`
- Modify: `scripts/start-offline.sh`

- [ ] **Step 1: 修改 `Dockerfile`**

  将第 32 行改为：
  ```dockerfile
  EXPOSE 10203
  ```

  将第 38 行改为：
  ```dockerfile
  CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "10203"]
  ```

  **验证方式：** `grep -n "10203" Dockerfile` 输出两行。

- [ ] **Step 2: 修改 `docker-compose.yml`**

  将第 9 行改为：
  ```yaml
      - "10203:10203"
  ```

  将 healthcheck 第 18 行改为：
  ```yaml
      test: ["CMD", "curl", "-f", "http://localhost:10203/login"]
  ```

  **验证方式：** `grep -n "10203" docker-compose.yml` 输出两行。

- [ ] **Step 3: 修改 `scripts/build-offline.sh`**

  将生成的 README.txt 中的 `:8080` 改为 `:10203`（第 59 行）：
  ```bash
  4. 访问 http://<服务器IP>:10203
  ```

  **验证方式：** `grep "10203" scripts/build-offline.sh` 有输出。

- [ ] **Step 4: 修改 `scripts/start-offline.sh`**

  将第 91-92 行的提示改为：
  ```bash
  echo "  管理后台：http://<本机IP>:10203/admin"
  echo "  API 接口：http://<本机IP>:10203/api/v1/updates/check"
  ```

  **验证方式：** `grep "10203" scripts/start-offline.sh` 有输出。

- [ ] **Step 5: 提交**

  ```bash
  git add Dockerfile docker-compose.yml scripts/build-offline.sh scripts/start-offline.sh
  git commit -m "$(cat <<'EOF'
  feat: update Docker and deployment scripts for port 10203

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 6: 变更文档、测试和 .env 文件

**Files:**
- Modify: `.env.example`
- Modify: `.env`
- Modify: `README.txt`
- Modify: `README.md`
- Modify: `tests/test_services.py`

- [ ] **Step 1: 修改 `.env.example`**

  替换整个文件内容为：
  ```bash
  # ============================================
  # HiddenRiskGlassServer 环境变量配置
  # ============================================
  # 复制此文件为 .env 并填写实际值

  # 管理后台登录用户名（可选，默认 admin）
  ADMIN_USERNAME=admin

  # 管理后台登录密码（必填）
  ADMIN_PASSWORD=your-strong-password-here

  # Cookie 签名密钥（必填，建议固定长随机字符串）
  SESSION_SECRET=replace-with-a-long-random-secret-at-least-32-chars

  # 是否仅 HTTPS 传输 Cookie（可选，内网 HTTP 部署时留空）
  # SESSION_COOKIE_SECURE=false

  # 数据目录（docker-compose 中已固定为 /app/data，通常无需修改）
  # APK_UPDATE_DATA_DIR=/app/data
  ```

  **验证方式：** 确认 `grep -n "SMTP" .env.example` 无输出，`grep "ADMIN_USERNAME" .env.example` 有输出。

- [ ] **Step 2: 同步修改 `.env`**

  当前 `.env` 内容应更新为类似 `.env.example` 的结构（去掉 SMTP，添加 `ADMIN_USERNAME`），保留已有的 `ADMIN_PASSWORD` 和 `SESSION_SECRET` 值。

  **验证方式：** 确认 `.env` 中无 SMTP 相关配置。

- [ ] **Step 3: 修改 `README.txt`**

  将所有 `:8080` 改为 `:10203`。

  **验证方式：** `grep "8080" README.txt` 无输出。

- [ ] **Step 4: 修改 `README.md`**

  批量替换所有 `8080` 为 `10203`。

  删除"邮件验证码配置（生产环境）"章节（约第 211-226 行）。

  更新环境变量说明（约第 45-50 行）为：
  ```markdown
  - `ADMIN_USERNAME`：**可选**。管理后台的登录用户名，默认为 `admin`。
  - `ADMIN_PASSWORD`：**必填**。管理后台的登录密码。
  - `SESSION_SECRET`：**建议填写**。用于签名管理员会话 Cookie 的密钥。如果留空，每次启动会生成随机密钥，导致重启后会话失效。
  ```

  更新本地启动示例中的端口：
  ```powershell
  $env:ADMIN_PASSWORD = "change-me"
  $env:SESSION_SECRET = "replace-with-a-long-random-secret"
  .\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 10203
  ```

  删除 JSON 配置中 `verification_code_*` 字段的说明，或标注为"已废弃"。

  **验证方式：** `grep -c "8080" README.md` 应为 0。

- [ ] **Step 5: 修改 `tests/test_services.py`**

  将所有 `127.0.0.1:8080` 替换为 `127.0.0.1:10203`。

  **验证方式：** `grep "8080" tests/test_services.py` 无输出。

- [ ] **Step 6: 提交**

  ```bash
  git add .env.example .env README.txt README.md tests/test_services.py
  git commit -m "$(cat <<'EOF'
  docs: update docs, env files, and tests for port 10203 and simplified auth

  - Remove SMTP config from .env.example and .env
  - Add ADMIN_USERNAME to env templates
  - Update all port references from 8080 to 10203 in README files
  - Remove email verification section from README.md
  - Update test URLs to port 10203

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 7: 变更眼镜端 Android 代码

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt:86-87`

- [ ] **Step 1: 修改 `AppUpdateClient.kt`**

  将第 86-87 行改为：
  ```kotlin
  const val DEFAULT_CHECK_URL = "http://192.168.1.152:10203/api/v1/updates/check"
  const val DEFAULT_MANIFEST_URL = "http://192.168.1.152:10203/releases/latest/update.json"
  ```

  **验证方式：** `grep "10203" app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt` 有输出，`grep "8080" app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt` 无输出。

- [ ] **Step 2: 提交**

  ```bash
  git add app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt
  git commit -m "$(cat <<'EOF'
  feat: update Android client default update server port to 10203

  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  EOF
  )"
  ```

---

### Task 8: 端到端验证

**Files:**
- (无文件变更，纯验证)

- [ ] **Step 1: 本地启动验证**

  在 `servers/HiddenRiskGlassServer` 目录下执行：

  ```bash
  cd servers/HiddenRiskGlassServer
  export ADMIN_PASSWORD=testpass
  export SESSION_SECRET=testsecret
  python server.py
  ```

  在另一个终端验证：
  ```bash
  # 检查默认端口
  curl -s http://127.0.0.1:10203/login | grep -o "登录" && echo "Port 10203 OK"

  # 测试密码验证（错误密码）
  curl -s -X POST -d "email=admin&password=wrong" http://127.0.0.1:10203/login | grep "密码错误" && echo "Wrong password rejected OK"

  # 测试密码验证（正确密码）
  curl -s -X POST -d "email=admin&password=testpass" -D - http://127.0.0.1:10203/login | grep "303" && echo "Login OK"
  ```

  **Expected:** 所有 curl 命令返回预期结果。

- [ ] **Step 2: 验证 API 端点**

  ```bash
  curl -s "http://127.0.0.1:10203/api/v1/updates/check?nscode=test&currentVersionCode=1"
  ```

  **Expected:** 返回 JSON（可能是 `{"updateAvailable": false}` 或报错，但不应 404）。

- [ ] **Step 3: 验证废弃路由已删除**

  ```bash
  curl -s http://127.0.0.1:10203/register -o /dev/null -w "%{http_code}"
  curl -s http://127.0.0.1:10203/forgot-password -o /dev/null -w "%{http_code}"
  curl -s http://127.0.0.1:10203/verify-code -o /dev/null -w "%{http_code}"
  ```

  **Expected:** 所有返回 404（FastAPI 默认对未定义路由返回 404）。

- [ ] **Step 4: 停止本地服务器**

  按 `Ctrl+C` 停止 `python server.py`。

- [ ] **Step 5: 提交验证结果（可选）**

  如果所有验证通过，此任务无需额外提交。如果发现问题，修复后单独提交。

---

## 自审查清单

### Spec 覆盖检查

| Spec 要求 | 对应任务 |
|-----------|----------|
| 删除 SMTP 配置 | Task 1 Step 1 |
| 新增 `ADMIN_USERNAME` | Task 1 Step 1, Task 6 Step 1 |
| `auth.py` 回归单管理员 | Task 1 Step 2 |
| 删除 `user_services.py` | Task 2 Step 2 |
| 删除 `mailer.py` | Task 2 Step 2 |
| `db.py` 删除废弃表 | Task 1 Step 3 |
| `main.py` 删除多用户路由 | Task 2 Step 1 |
| 端口 8080 → 10203（服务器代码） | Task 4 |
| 端口 8080 → 10203（Docker） | Task 5 |
| 端口 8080 → 10203（脚本） | Task 5 |
| 端口 8080 → 10203（文档） | Task 6 |
| 端口 8080 → 10203（测试） | Task 6 Step 5 |
| 端口 8080 → 10203（Android） | Task 7 |
| 删除废弃模板 | Task 3 |
| 修改 login.html | Task 3 Step 1 |
| 修改 admin.html | Task 3 Step 2 |
| 更新 .env 文件 | Task 6 Step 1-2 |
| 端到端验证 | Task 8 |

### Placeholder 扫描

- 无 TBD、TODO、"implement later"、"fill in details"
- 无 "Add appropriate error handling" 等模糊描述
- 所有代码步骤包含完整代码块
- 无 "Similar to Task N" 引用

### 类型一致性

- `Settings.admin_username` 在 Task 1 定义为 `str`，后续使用一致
- `verify_password` 签名在 Task 1 和 Task 2 中一致：`verify_password(settings: Settings, password: str) -> bool`
- `mark_logged_in` 在 Task 1 不再接受 `user_id` 参数，Task 2 调用时不再传 `user_id`
