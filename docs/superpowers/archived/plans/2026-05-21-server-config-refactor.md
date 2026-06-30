# HiddenRiskGlassServer 配置重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 APK Update Server 改名为 HiddenRiskGlassServer，迁移目录，提取技术参数到 JSON 配置，清理冗余文件。

**Architecture:** 单一 JSON 配置文件（`config.json`）承载技术参数，环境变量仍保留最高优先级。目录从 `tools/apk_update_server` 整体迁移到 `servers/HiddenRiskGlassServer`。

**Tech Stack:** FastAPI, SQLite, Jinja2, pytest, PowerShell

---

## 文件结构

| 文件 | 操作 | 说明 |
|---|---|---|
| `servers/HiddenRiskGlassServer/config.json` | 新建 | 技术参数配置文件 |
| `servers/HiddenRiskGlassServer/app/config.py` | 修改 | 添加 JSON 加载逻辑和 6 个新字段 |
| `servers/HiddenRiskGlassServer/app/user_services.py` | 修改 | 删除 4 个模块常量，改为从 Settings 读取 |
| `servers/HiddenRiskGlassServer/app/services.py` | 修改 | 删除 `CHUNK_SIZE` 常量，改为从 Settings 读取 |
| `servers/HiddenRiskGlassServer/app/mailer.py` | 修改 | 邮件主题和模板使用 `settings.server_name` |
| `servers/HiddenRiskGlassServer/app/main.py` | 修改 | FastAPI title 使用 `settings.server_name` |
| `servers/HiddenRiskGlassServer/app/templates/*.html` | 修改 | 标题从 "APK 更新后台" 改为 `HiddenRiskGlassServer` |
| `servers/HiddenRiskGlassServer/serve.ps1` | 修改 | 输出信息更新 |
| `servers/HiddenRiskGlassServer/tests/test_api.py` | 修改 | 标题断言更新 |
| `servers/HiddenRiskGlassServer/tests/test_mailer.py` | 修改 | 匹配开发模式行为（SMTP 未配置时打印日志而非抛异常） |
| `servers/HiddenRiskGlassServer/generate_manifest.py` | 删除 | 已被 FastAPI 功能覆盖 |
| `servers/HiddenRiskGlassServer/README.md` | 修改 | 路径和名称更新 |
| `.gitignore` | 修改 | 路径从 `tools/apk_update_server/...` 改为 `servers/HiddenRiskGlassServer/...` |
| `tools/apk_update_server/` | git mv | 整体迁移到 `servers/HiddenRiskGlassServer/` |

---

### Task 1: 目录迁移（git mv）

**Files:**
- Move: `tools/apk_update_server/` → `servers/HiddenRiskGlassServer/`

- [ ] **Step 1: 创建目标目录并移动文件**

```bash
git mv tools/apk_update_server servers/HiddenRiskGlassServer
```

- [ ] **Step 2: 验证迁移结果**

```bash
git status
ls servers/HiddenRiskGlassServer/
```

Expected: `servers/HiddenRiskGlassServer/` 下包含 `app/`, `tests/`, `server.py`, `serve.ps1`, `requirements.txt`, `README.md`, `generate_manifest.py`

- [ ] **Step 3: 提交目录迁移**

```bash
git commit -m "chore: rename tools/apk_update_server to servers/HiddenRiskGlassServer"
```

---

### Task 2: 创建 config.json

**Files:**
- Create: `servers/HiddenRiskGlassServer/config.json`

- [ ] **Step 1: 写入配置文件**

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

- [ ] **Step 2: 提交**

```bash
git add servers/HiddenRiskGlassServer/config.json
git commit -m "feat: add config.json with server name and auth/upload parameters"
```

---

### Task 3: 修改 config.py 加载 JSON 配置

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/config.py`

- [ ] **Step 1: 添加 json import 和 JSON 加载辅助函数**

在文件顶部 `import os` 下方添加：

```python
import json
```

在 `APP_ROOT` 定义之后、`@dataclass` 之前添加：

```python
def _load_json_config(app_root: Path) -> dict:
    config_path = app_root / "config.json"
    if config_path.is_file():
        return json.loads(config_path.read_text(encoding="utf-8"))
    return {}
```

- [ ] **Step 2: 扩展 Settings dataclass**

在 `Settings` 中添加以下字段（在 `smtp_tls` 之后）：

```python
    server_name: str
    verification_code_length: int
    verification_code_expires_minutes: int
    verification_code_send_cooldown_seconds: int
    password_min_length: int
    chunk_size_bytes: int
```

- [ ] **Step 3: 扩展 load_settings 函数**

在 `load_settings` 函数中，`smtp_tls` 行之后、`releases_dir` 行之前添加：

```python
    json_config = _load_json_config(APP_ROOT)

    server_name = os.environ.get("SERVER_NAME") or json_config.get("server_name", "HiddenRiskGlassServer")
    verification_code_length = int(os.environ.get("VERIFICATION_CODE_LENGTH", json_config.get("auth", {}).get("verification_code_length", 6)))
    verification_code_expires_minutes = int(os.environ.get("VERIFICATION_CODE_EXPIRES_MINUTES", json_config.get("auth", {}).get("verification_code_expires_minutes", 15)))
    verification_code_send_cooldown_seconds = int(os.environ.get("VERIFICATION_CODE_SEND_COOLDOWN_SECONDS", json_config.get("auth", {}).get("verification_code_send_cooldown_seconds", 60)))
    password_min_length = int(os.environ.get("PASSWORD_MIN_LENGTH", json_config.get("auth", {}).get("password_min_length", 8)))
    chunk_size_bytes = int(os.environ.get("CHUNK_SIZE_BYTES", json_config.get("upload", {}).get("chunk_size_bytes", 1048576)))
```

在 `Settings(...)` 构造器中，最后添加：

```python
        server_name=server_name,
        verification_code_length=verification_code_length,
        verification_code_expires_minutes=verification_code_expires_minutes,
        verification_code_send_cooldown_seconds=verification_code_send_cooldown_seconds,
        password_min_length=password_min_length,
        chunk_size_bytes=chunk_size_bytes,
```

- [ ] **Step 4: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_auth.py -v
```

Expected: 全部通过（当前 2 个测试）

- [ ] **Step 5: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/config.py
git commit -m "feat: load server name and auth/upload params from config.json"
```

---

### Task 4: 修改 user_services.py 从 Settings 读取参数

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/user_services.py`

- [ ] **Step 1: 删除模块级常量**

删除以下三行：

```python
CODE_LENGTH = 6
CODE_EXPIRES_MINUTES = 15
CODE_SEND_COOLDOWN_SECONDS = 60
```

- [ ] **Step 2: 修改 _generate_code 函数**

```python
def _generate_code(length: int) -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(length))
```

- [ ] **Step 3: 修改 _validate_password_strength 函数**

```python
def _validate_password_strength(password: str, min_length: int) -> None:
    if len(password) < min_length:
        raise VerificationError(f"密码至少{min_length}位")
    if not re.search(r"[A-Za-z]", password):
        raise VerificationError("密码至少包含一个字母")
    if not re.search(r"\d", password):
        raise VerificationError("密码至少包含一个数字")
```

- [ ] **Step 4: 修改 _check_send_cooldown 函数**

```python
def _check_send_cooldown(conn, email: str, purpose: str, cooldown_seconds: int) -> None:
    row = conn.execute(
        """
        SELECT created_at FROM verification_codes
        WHERE email = ? AND purpose = ?
        ORDER BY created_at DESC LIMIT 1
        """,
        (email.strip().lower(), purpose),
    ).fetchone()
    if row is not None:
        last_sent = datetime.fromisoformat(row["created_at"])
        if last_sent.tzinfo is None:
            last_sent = last_sent.replace(tzinfo=timezone.utc)
        elapsed = (_now() - last_sent).total_seconds()
        if elapsed < cooldown_seconds:
            raise VerificationError("请稍后再试")
```

- [ ] **Step 5: 修改 send_verification_code 函数**

```python
def send_verification_code(settings: Settings, email: str, purpose: str) -> None:
    email = email.strip().lower()
    if not email:
        raise VerificationError("邮箱不能为空")
    if "@" not in email:
        raise VerificationError("邮箱格式不正确")

    with db_session(settings) as conn:
        _check_send_cooldown(conn, email, purpose, settings.verification_code_send_cooldown_seconds)
        code = _generate_code(settings.verification_code_length)
        expires_at = _now() + timedelta(minutes=settings.verification_code_expires_minutes)
        conn.execute(
            """
            INSERT INTO verification_codes (email, code, purpose, expires_at)
            VALUES (?, ?, ?, ?)
            """,
            (email, code, purpose, expires_at.isoformat()),
        )

    send_verification_email(settings, email, code)
```

- [ ] **Step 6: 修改 register_user 函数**

```python
def register_user(settings: Settings, email: str, password: str, code: str) -> int:
    email = email.strip().lower()
    _validate_password_strength(password, settings.password_min_length)

    if not verify_code(settings, email, code, "register"):
        raise VerificationError("验证码错误或已过期")
    ...
```

（其余部分保持不变）

- [ ] **Step 7: 修改 reset_password 函数**

```python
def reset_password(settings: Settings, email: str, code: str, new_password: str) -> None:
    email = email.strip().lower()
    _validate_password_strength(new_password, settings.password_min_length)

    if not verify_code(settings, email, code, "reset_password"):
        raise VerificationError("验证码错误或已过期")
    ...
```

（其余部分保持不变）

- [ ] **Step 8: 修改 change_password 函数**

```python
def change_password(settings: Settings, user_id: int, old_password: str, new_password: str) -> None:
    from app.auth import verify_user_password

    _validate_password_strength(new_password, settings.password_min_length)
    ...
```

（其余部分保持不变）

- [ ] **Step 9: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_auth.py tests/test_api.py::test_register_page_redirects_when_admin_exists -v
```

Expected: 全部通过

- [ ] **Step 10: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/user_services.py
git commit -m "refactor: read auth params from Settings instead of module constants"
```

---

### Task 5: 修改 services.py 从 Settings 读取 CHUNK_SIZE

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/services.py`

- [ ] **Step 1: 删除 CHUNK_SIZE 常量**

删除：

```python
CHUNK_SIZE = 1024 * 1024
```

- [ ] **Step 2: 修改 sha256_file 函数**

```python
def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()
```

- [ ] **Step 3: 找到 sha256_file 调用处并传入 chunk_size**

在 `publish_release` 函数中，找到：

```python
sha256 = sha256_file(final_apk)
```

改为：

```python
sha256 = sha256_file(final_apk, settings.chunk_size_bytes)
```

- [ ] **Step 4: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_services.py::test_publish_release_writes_apk_and_manifest_fields -v
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/services.py
git commit -m "refactor: read chunk_size from Settings instead of module constant"
```

---

### Task 6: 修改 mailer.py 使用 settings.server_name

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/mailer.py`

- [ ] **Step 1: 修改邮件主题和模板为可 format 的字符串**

将 `VERIFICATION_EMAIL_SUBJECT` 改为：

```python
VERIFICATION_EMAIL_SUBJECT = "{server_name} - 您的验证码"
```

将 `VERIFICATION_EMAIL_TEMPLATE` 中的 `<h2>` 行改为：

```html
  <h2 style="color:#0f766e">{server_name}</h2>
```

- [ ] **Step 2: 修改 send_verification_email 函数**

```python
def send_verification_email(settings: Settings, email: str, code: str) -> None:
    if not settings.smtp_host:
        import logging
        logging.basicConfig(level=logging.INFO)
        logging.info("=" * 50)
        logging.info("[开发模式] SMTP 未配置，验证码直接输出：")
        logging.info(f"  邮箱: {email}")
        logging.info(f"  验证码: {code}")
        logging.info("=" * 50)
        return

    server_name = settings.server_name
    msg = MIMEText(VERIFICATION_EMAIL_TEMPLATE.format(code=code, server_name=server_name), "html", "utf-8")
    msg["Subject"] = f"{VERIFICATION_EMAIL_SUBJECT.format(server_name=server_name)}是 {code}"
    msg["From"] = settings.smtp_from
    msg["To"] = email

    with _create_smtp_connection(settings) as smtp:
        smtp.sendmail(settings.smtp_from, [email], msg.as_string())
```

- [ ] **Step 3: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_mailer.py -v
```

Expected: 测试需要更新（见 Task 10），当前可能失败

- [ ] **Step 4: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/mailer.py
git commit -m "refactor: use settings.server_name in email subject and template"
```

---

### Task 7: 修改 main.py 使用 settings.server_name

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/main.py`

- [ ] **Step 1: 修改 FastAPI title**

```python
    app = FastAPI(title=resolved_settings.server_name)
```

- [ ] **Step 2: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_api.py::test_login_allows_admin_access_and_page_contains_title -v
```

Expected: 当前会失败（因为标题还未更新，见 Task 9）

- [ ] **Step 3: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/main.py
git commit -m "refactor: use settings.server_name as FastAPI title"
```

---

### Task 8: 修改 HTML 模板标题

**Files:**
- Modify: `servers/HiddenRiskGlassServer/app/templates/admin.html`
- Modify: `servers/HiddenRiskGlassServer/app/templates/login.html`
- Modify: `servers/HiddenRiskGlassServer/app/templates/register.html`
- Modify: `servers/HiddenRiskGlassServer/app/templates/forgot_password.html`
- Modify: `servers/HiddenRiskGlassServer/app/templates/profile.html`

- [ ] **Step 1: 修改 admin.html**

`<title>APK 更新后台</title>` → `<title>HiddenRiskGlassServer</title>`

`<h1>APK 更新后台</h1>` → `<h1>HiddenRiskGlassServer</h1>`

- [ ] **Step 2: 修改 login.html**

`<title>登录 - APK 更新后台</title>` → `<title>登录 - HiddenRiskGlassServer</title>`

`<h1>APK 更新后台</h1>` → `<h1>HiddenRiskGlassServer</h1>`

- [ ] **Step 3: 修改 register.html**

`<title>创建管理员账户 - APK 更新后台</title>` → `<title>创建管理员账户 - HiddenRiskGlassServer</title>`

`<h1>APK 更新后台</h1>` → `<h1>HiddenRiskGlassServer</h1>`

- [ ] **Step 4: 修改 forgot_password.html**

`<title>重置密码 - APK 更新后台</title>` → `<title>重置密码 - HiddenRiskGlassServer</title>`

- [ ] **Step 5: 修改 profile.html**

`<title>个人中心 - APK 更新后台</title>` → `<title>个人中心 - HiddenRiskGlassServer</title>`

- [ ] **Step 6: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_api.py::test_login_allows_admin_access_and_page_contains_title -v
```

Expected: PASS（标题断言匹配新名称）

- [ ] **Step 7: 提交**

```bash
git add servers/HiddenRiskGlassServer/app/templates/
git commit -m "refactor: rename UI title from APK 更新后台 to HiddenRiskGlassServer"
```

---

### Task 9: 修改 serve.ps1 输出信息

**Files:**
- Modify: `servers/HiddenRiskGlassServer/serve.ps1`

- [ ] **Step 1: 更新输出信息**

```powershell
Write-Host "Serving HiddenRiskGlassServer from $Root"
```

- [ ] **Step 2: 提交**

```bash
git add servers/HiddenRiskGlassServer/serve.ps1
git commit -m "chore: update serve.ps1 output message to HiddenRiskGlassServer"
```

---

### Task 10: 更新测试

**Files:**
- Modify: `servers/HiddenRiskGlassServer/tests/test_api.py`
- Modify: `servers/HiddenRiskGlassServer/tests/test_mailer.py`

- [ ] **Step 1: 更新 test_api.py 中的标题断言**

第 122 行：

```python
    assert "HiddenRiskGlassServer" in response.text
```

- [ ] **Step 2: 更新 test_mailer.py**

当前测试期望抛出 `RuntimeError`，但 mailer.py 在 SMTP 未配置时进入开发模式打印日志。修改为验证开发模式行为：

```python
def test_send_verification_email_logs_code_in_dev_mode(isolated_env, caplog):
    from app.config import load_settings
    from app.mailer import send_verification_email

    settings = load_settings()
    with caplog.at_level("INFO"):
        send_verification_email(settings, "test@example.com", "123456")
    assert "[开发模式] SMTP 未配置" in caplog.text
    assert "123456" in caplog.text
```

- [ ] **Step 3: 运行测试验证**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/test_api.py::test_login_allows_admin_access_and_page_contains_title tests/test_mailer.py -v
```

Expected: 全部通过

- [ ] **Step 4: 提交**

```bash
git add servers/HiddenRiskGlassServer/tests/test_api.py servers/HiddenRiskGlassServer/tests/test_mailer.py
git commit -m "test: update assertions for HiddenRiskGlassServer rename and dev-mode mailer"
```

---

### Task 11: 删除冗余文件 generate_manifest.py

**Files:**
- Delete: `servers/HiddenRiskGlassServer/generate_manifest.py`

- [ ] **Step 1: 删除文件**

```bash
git rm servers/HiddenRiskGlassServer/generate_manifest.py
```

- [ ] **Step 2: 提交**

```bash
git commit -m "chore: remove legacy generate_manifest.py (replaced by FastAPI admin)"
```

---

### Task 12: 更新 .gitignore

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: 更新 APK Update Server 相关路径**

将所有 `tools/apk_update_server/` 前缀改为 `servers/HiddenRiskGlassServer/`：

```gitignore
# Local APK update server generated artifacts
servers/HiddenRiskGlassServer/releases/**/*.apk
servers/HiddenRiskGlassServer/releases/**/*.json
servers/HiddenRiskGlassServer/releases/**/*.upload
servers/HiddenRiskGlassServer/apk_update_server.sqlite3
servers/HiddenRiskGlassServer/smoke-server.*
servers/HiddenRiskGlassServer/.tmp_*.log
servers/HiddenRiskGlassServer/**/__pycache__/
```

- [ ] **Step 2: 提交**

```bash
git add .gitignore
git commit -m "chore: update .gitignore paths for servers/HiddenRiskGlassServer"
```

---

### Task 13: 更新 README.md

**Files:**
- Modify: `servers/HiddenRiskGlassServer/README.md`

- [ ] **Step 1: 更新标题和路径**

- 标题 `# Deployable APK Update Server` → `# HiddenRiskGlassServer`
- 所有 `tools/apk_update_server/` 路径改为 `servers/HiddenRiskGlassServer/`
- 所有 `APK update server` 描述文字更新为 `HiddenRiskGlassServer`
- 环境变量说明后添加 JSON 配置说明段落

- [ ] **Step 2: 提交**

```bash
git add servers/HiddenRiskGlassServer/README.md
git commit -m "docs: update README for HiddenRiskGlassServer rename and config.json"
```

---

### Task 14: 全量测试

**Files:**
- Test: `servers/HiddenRiskGlassServer/tests/`

- [ ] **Step 1: 运行全部测试**

```bash
cd servers/HiddenRiskGlassServer
python -m pytest tests/ -v
```

Expected: 全部通过（当前约 49 个测试）

- [ ] **Step 2: 如测试失败，修复并重新运行**

---

### Task 15: 最终提交

- [ ] **Step 1: 检查 git status**

```bash
git status
```

Expected: working tree clean，所有修改已提交

- [ ] **Step 2: 查看提交历史**

```bash
git log --oneline -15
```

---

## Self-Review

**1. Spec coverage:**
- ✅ 服务器名称修改：Task 6-9 覆盖了 mailer.py、main.py、templates、serve.ps1
- ✅ JSON 配置文件：Task 2 创建，Task 3 加载
- ✅ 技术参数提取：Task 3-5 覆盖了 config.py、user_services.py、services.py
- ✅ 目录迁移：Task 1
- ✅ 冗余文件清理：Task 11
- ✅ 边界约束：README 和 .gitignore 更新在 Task 12-13

**2. Placeholder scan:**
- ✅ 无 TBD/TODO
- ✅ 每个步骤都有具体代码或命令
- ✅ 无 "appropriate error handling" 等模糊描述

**3. Type consistency:**
- ✅ `Settings` 中新增字段类型一致（str/int）
- ✅ 函数签名修改后，所有调用处均已更新
- ✅ `_generate_code(length: int)`、`_validate_password_strength(password, min_length)`、`_check_send_cooldown(..., cooldown_seconds)` 签名明确
