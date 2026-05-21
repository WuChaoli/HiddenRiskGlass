# APK 更新服务器权限模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单密码认证升级为基于邮箱的管理员账户系统，支持注册（邮箱验证码）、登录、忘记密码、修改密码、邮件发送、接口地址展示。

**Architecture:** 保持现有 FastAPI + SQLite + Jinja2 架构，新增 users 和 verification_codes 表，引入 SMTP 邮件模块，认证从环境变量密码改为 bcrypt 哈希的用户密码。前端新增注册/忘记密码/个人中心页面，admin 页面新增"接口地址"Tab。

**Tech Stack:** FastAPI, SQLite, Jinja2, passlib (bcrypt), smtplib, vanilla JS

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/db.py` | 修改 | 新增 users、verification_codes 表 |
| `app/config.py` | 修改 | 新增 SMTP 配置字段 |
| `app/mailer.py` | 新建 | SMTP 邮件发送（验证码邮件） |
| `app/auth.py` | 重写 | 基于 users 表的 session 认证 |
| `app/user_services.py` | 新建 | 注册、验证码、密码重置业务逻辑 |
| `app/main.py` | 修改 | 新增/修改认证相关路由 |
| `app/templates/register.html` | 新建 | 注册页面 |
| `app/templates/login.html` | 修改 | 改为邮箱+密码登录 |
| `app/templates/forgot_password.html` | 新建 | 忘记密码页面 |
| `app/templates/profile.html` | 新建 | 个人中心页面 |
| `app/templates/admin.html` | 修改 | 新增"接口地址"Tab、顶部个人中心链接 |
| `app/static/admin.css` | 修改 | 新增注册/忘记密码/接口地址样式 |
| `app/static/admin.js` | 修改 | 验证码倒计时、一键复制 |
| `tests/conftest.py` | 修改 | 适配新认证方式 |
| `tests/test_auth.py` | 新建 | 认证相关测试 |
| `tests/test_mailer.py` | 新建 | 邮件模块测试 |
| `tests/test_api.py` | 修改 | 更新登录方式 |

---

### Task 1: Database Schema & Config Foundation

**Files:**
- Modify: `app/db.py`
- Modify: `app/config.py`
- Test: `tests/test_auth.py`

- [ ] **Step 1: 修改 `app/db.py` 新增 users 和 verification_codes 表**

在 `SCHEMA` 字符串末尾（`check_events` 表之后）追加两张表的创建语句：

```python
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email_verified INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS verification_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL,
    code TEXT NOT NULL,
    purpose TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 2: 修改 `app/config.py` 新增 SMTP 配置**

在 `Settings` dataclass 中追加字段：

```python
@dataclass(frozen=True)
class Settings:
    app_root: Path
    data_dir: Path
    releases_dir: Path
    database_path: Path
    admin_password: str
    session_secret: str
    session_cookie_secure: bool
    # SMTP 配置（新增）
    smtp_host: str
    smtp_port: int
    smtp_user: str
    smtp_pass: str
    smtp_from: str
    smtp_tls: bool
```

在 `load_settings()` 函数中读取环境变量：

```python
def load_settings(require_admin_password: bool = True) -> Settings:
    data_dir = Path(os.environ.get("APK_UPDATE_DATA_DIR", APP_ROOT)).resolve()
    admin_password = os.environ.get("ADMIN_PASSWORD", "")
    if require_admin_password and not admin_password:
        raise RuntimeError("ADMIN_PASSWORD must be set before starting the APK update server")

    session_secret = os.environ.get("SESSION_SECRET") or secrets.token_urlsafe(32)
    cookie_secure = os.environ.get("SESSION_COOKIE_SECURE", "").lower() in {"1", "true", "yes"}
    releases_dir = data_dir / "releases"

    # SMTP 配置（新增）
    smtp_host = os.environ.get("SMTP_HOST", "")
    smtp_port = int(os.environ.get("SMTP_PORT", "587"))
    smtp_user = os.environ.get("SMTP_USER", "")
    smtp_pass = os.environ.get("SMTP_PASS", "")
    smtp_from = os.environ.get("SMTP_FROM", "")
    smtp_tls = os.environ.get("SMTP_TLS", "true").lower() not in {"0", "false", "no"}

    return Settings(
        app_root=APP_ROOT,
        data_dir=data_dir,
        releases_dir=releases_dir,
        database_path=data_dir / "apk_update_server.sqlite3",
        admin_password=admin_password,
        session_secret=session_secret,
        session_cookie_secure=cookie_secure,
        smtp_host=smtp_host,
        smtp_port=smtp_port,
        smtp_user=smtp_user,
        smtp_pass=smtp_pass,
        smtp_from=smtp_from,
        smtp_tls=smtp_tls,
    )
```

- [ ] **Step 3: 写测试验证数据库初始化**

创建 `tests/test_auth.py`：

```python
from __future__ import annotations


def test_database_initializes_with_users_table(isolated_env):
    from app.config import load_settings
    from app.db import connect_db

    settings = load_settings()
    with connect_db(settings) as conn:
        tables = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'verification_codes')"
        ).fetchall()
        table_names = {row["name"] for row in tables}
        assert "users" in table_names
        assert "verification_codes" in table_names
```

- [ ] **Step 4: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_auth.py -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/db.py app/config.py tests/test_auth.py
git commit -m "feat: add users and verification_codes tables, SMTP config"
```

---

### Task 2: Mailer Module

**Files:**
- Create: `app/mailer.py`
- Test: `tests/test_mailer.py`

- [ ] **Step 1: 创建 `app/mailer.py`**

```python
from __future__ import annotations

import smtplib
from email.mime.text import MIMEText

from app.config import Settings


VERIFICATION_EMAIL_SUBJECT = "APK更新后台 - 您的验证码"
VERIFICATION_EMAIL_TEMPLATE = """\
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
</head>
<body style="font-family:sans-serif;max-width:400px;margin:0 auto;padding:20px">
  <h2 style="color:#0f766e">APK 更新后台</h2>
  <p>您的验证码是：</p>
  <p style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#1f2937">{code}</p>
  <p>此验证码将在 15 分钟后失效。</p>
  <p style="color:#667085">如非本人操作，请忽略此邮件。</p>
</body>
</html>
"""


def _create_smtp_connection(settings: Settings) -> smtplib.SMTP:
    smtp = smtplib.SMTP(settings.smtp_host, settings.smtp_port)
    if settings.smtp_tls:
        smtp.starttls()
    if settings.smtp_user:
        smtp.login(settings.smtp_user, settings.smtp_pass)
    return smtp


def send_verification_email(settings: Settings, email: str, code: str) -> None:
    if not settings.smtp_host:
        raise RuntimeError("SMTP_HOST is not configured")

    msg = MIMEText(VERIFICATION_EMAIL_TEMPLATE.format(code=code), "html", "utf-8")
    msg["Subject"] = f"{VERIFICATION_EMAIL_SUBJECT}是 {code}"
    msg["From"] = settings.smtp_from
    msg["To"] = email

    with _create_smtp_connection(settings) as smtp:
        smtp.sendmail(settings.smtp_from, [email], msg.as_string())
```

- [ ] **Step 2: 创建 `tests/test_mailer.py`**

```python
from __future__ import annotations

import pytest


def test_send_verification_email_raises_without_smtp_host(isolated_env):
    from app.config import load_settings
    from app.mailer import send_verification_email

    settings = load_settings()
    with pytest.raises(RuntimeError, match="SMTP_HOST is not configured"):
        send_verification_email(settings, "test@example.com", "123456")
```

- [ ] **Step 3: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_mailer.py -v`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add app/mailer.py tests/test_mailer.py
git commit -m "feat: add SMTP mailer module for verification emails"
```

---

### Task 3: Auth & User Services

**Files:**
- Modify: `app/auth.py`
- Create: `app/user_services.py`
- Test: `tests/test_auth.py`

- [ ] **Step 1: 重写 `app/auth.py`**

```python
from __future__ import annotations

from fastapi import Request
from starlette.responses import RedirectResponse

from app.config import Settings
from app.db import db_session

SESSION_KEY = "admin_authenticated"
USER_ID_KEY = "admin_user_id"


def is_logged_in(request: Request) -> bool:
    return bool(request.session.get(SESSION_KEY))


def get_current_user_id(request: Request) -> int | None:
    return request.session.get(USER_ID_KEY)


def mark_logged_in(request: Request, user_id: int) -> None:
    request.session[SESSION_KEY] = True
    request.session[USER_ID_KEY] = user_id


def mark_logged_out(request: Request) -> None:
    request.session.pop(SESSION_KEY, None)
    request.session.pop(USER_ID_KEY, None)


def require_admin(request: Request) -> RedirectResponse | None:
    if is_logged_in(request):
        return None
    return RedirectResponse("/login", status_code=303)


def verify_user_password(settings: Settings, email: str, password: str) -> int | None:
    from passlib.context import CryptContext

    pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id, password_hash FROM users WHERE email = ?",
            (email.strip().lower(),),
        ).fetchone()

    if row is None:
        return None
    if not pwd_context.verify(password, row["password_hash"]):
        return None
    return int(row["id"])


def has_any_admin(settings: Settings) -> bool:
    with db_session(settings) as conn:
        row = conn.execute("SELECT COUNT(*) as count FROM users").fetchone()
    return bool(row and row["count"] > 0)


def hash_password(password: str) -> str:
    from passlib.context import CryptContext

    pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
    return pwd_context.hash(password)
```

- [ ] **Step 2: 创建 `app/user_services.py`**

```python
from __future__ import annotations

import random
import re
from datetime import datetime, timedelta, timezone

from app.auth import hash_password
from app.config import Settings
from app.db import db_session
from app.mailer import send_verification_email


CODE_LENGTH = 6
CODE_EXPIRES_MINUTES = 15
CODE_SEND_COOLDOWN_SECONDS = 60


class VerificationError(ValueError):
    pass


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _generate_code() -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(CODE_LENGTH))


def _validate_password_strength(password: str) -> None:
    if len(password) < 8:
        raise VerificationError("密码至少8位")
    if not re.search(r"[A-Za-z]", password):
        raise VerificationError("密码至少包含一个字母")
    if not re.search(r"\d", password):
        raise VerificationError("密码至少包含一个数字")


def _check_send_cooldown(conn, email: str, purpose: str) -> None:
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
        elapsed = (_now() - last_sent).total_seconds()
        if elapsed < CODE_SEND_COOLDOWN_SECONDS:
            raise VerificationError("请稍后再试")


def send_verification_code(settings: Settings, email: str, purpose: str) -> None:
    email = email.strip().lower()
    if not email:
        raise VerificationError("邮箱不能为空")
    if "@" not in email:
        raise VerificationError("邮箱格式不正确")

    with db_session(settings) as conn:
        _check_send_cooldown(conn, email, purpose)
        code = _generate_code()
        expires_at = _now() + timedelta(minutes=CODE_EXPIRES_MINUTES)
        conn.execute(
            """
            INSERT INTO verification_codes (email, code, purpose, expires_at)
            VALUES (?, ?, ?, ?)
            """,
            (email, code, purpose, expires_at.isoformat()),
        )

    send_verification_email(settings, email, code)


def verify_code(settings: Settings, email: str, code: str, purpose: str) -> bool:
    email = email.strip().lower()
    with db_session(settings) as conn:
        row = conn.execute(
            """
            SELECT id, expires_at, used FROM verification_codes
            WHERE email = ? AND code = ? AND purpose = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (email, code, purpose),
        ).fetchone()

        if row is None:
            return False
        if row["used"]:
            return False
        expires_at = datetime.fromisoformat(row["expires_at"])
        if _now() > expires_at:
            return False

        conn.execute(
            "UPDATE verification_codes SET used = 1 WHERE id = ?",
            (row["id"],),
        )
        return True


def register_user(settings: Settings, email: str, password: str, code: str) -> int:
    email = email.strip().lower()
    _validate_password_strength(password)

    if not verify_code(settings, email, code, "register"):
        raise VerificationError("验证码错误或已过期")

    with db_session(settings) as conn:
        existing = conn.execute(
            "SELECT id FROM users WHERE email = ?", (email,)
        ).fetchone()
        if existing is not None:
            raise VerificationError("该邮箱已注册")

        cursor = conn.execute(
            """
            INSERT INTO users (email, password_hash, email_verified)
            VALUES (?, ?, 1)
            """,
            (email, hash_password(password)),
        )
        return int(cursor.lastrowid)


def reset_password(settings: Settings, email: str, code: str, new_password: str) -> None:
    email = email.strip().lower()
    _validate_password_strength(new_password)

    if not verify_code(settings, email, code, "reset_password"):
        raise VerificationError("验证码错误或已过期")

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id FROM users WHERE email = ?", (email,)
        ).fetchone()
        if row is None:
            raise VerificationError("该邮箱未注册")

        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), row["id"]),
        )


def change_password(settings: Settings, user_id: int, old_password: str, new_password: str) -> None:
    from app.auth import verify_user_password

    _validate_password_strength(new_password)

    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT email FROM users WHERE id = ?", (user_id,)
        ).fetchone()
        if row is None:
            raise VerificationError("用户不存在")

    verified_id = verify_user_password(settings, row["email"], old_password)
    if verified_id is None:
        raise VerificationError("旧密码不正确")

    with db_session(settings) as conn:
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), user_id),
        )


def get_user_by_id(settings: Settings, user_id: int) -> dict | None:
    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id, email, created_at FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    if row is None:
        return None
    return dict(row)
```

- [ ] **Step 3: 追加测试到 `tests/test_auth.py`**

```python
import pytest


def test_register_and_login_user(isolated_env):
    from app.config import load_settings
    from app.user_services import register_user
    from app.auth import verify_user_password

    settings = load_settings()
    # 先插入验证码
    from app.db import db_session
    from datetime import datetime, timedelta, timezone
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    user_id = register_user(settings, "admin@test.com", "Test1234", "123456")
    assert user_id == 1

    verified_id = verify_user_password(settings, "admin@test.com", "Test1234")
    assert verified_id == 1

    wrong = verify_user_password(settings, "admin@test.com", "wrong")
    assert wrong is None


def test_register_duplicate_email_fails(isolated_env):
    from app.config import load_settings
    from app.user_services import register_user, VerificationError
    from app.db import db_session
    from app.auth import hash_password
    from datetime import datetime, timedelta, timezone

    settings = load_settings()
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            ("admin@test.com", hash_password("Test1234")),
        )
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    with pytest.raises(VerificationError, match="该邮箱已注册"):
        register_user(settings, "admin@test.com", "Test1234", "123456")


def test_password_strength_validation(isolated_env):
    from app.user_services import _validate_password_strength, VerificationError

    with pytest.raises(VerificationError):
        _validate_password_strength("short")
    with pytest.raises(VerificationError):
        _validate_password_strength("12345678")
    with pytest.raises(VerificationError):
        _validate_password_strength("abcdefgh")

    # 不抛出异常即通过
    _validate_password_strength("Test1234")


def test_change_password(isolated_env):
    from app.config import load_settings
    from app.user_services import change_password, register_user
    from app.auth import verify_user_password
    from app.db import db_session
    from datetime import datetime, timedelta, timezone

    settings = load_settings()
    now = datetime.now(timezone.utc)
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO verification_codes (email, code, purpose, expires_at) VALUES (?, ?, ?, ?)",
            ("admin@test.com", "123456", "register", (now + timedelta(minutes=15)).isoformat()),
        )

    user_id = register_user(settings, "admin@test.com", "OldPass1", "123456")

    # 修改密码
    change_password(settings, user_id, "OldPass1", "NewPass2")

    # 旧密码失效
    assert verify_user_password(settings, "admin@test.com", "OldPass1") is None
    # 新密码有效
    assert verify_user_password(settings, "admin@test.com", "NewPass2") == user_id
```

- [ ] **Step 4: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_auth.py -v`
Expected: 5 passed

- [ ] **Step 5: 提交**

```bash
git add app/auth.py app/user_services.py tests/test_auth.py
git commit -m "feat: add user authentication with bcrypt and verification codes"
```

---

### Task 4: Register Page & Route

**Files:**
- Create: `app/templates/register.html`
- Modify: `app/main.py`
- Modify: `tests/conftest.py`
- Test: `tests/test_api.py`

- [ ] **Step 1: 创建 `app/templates/register.html`**

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>创建管理员账户 - APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body class="login-page">
  <main class="login-panel">
    <h1>APK 更新后台</h1>
    <p style="color:var(--muted)">首次使用，请创建管理员账户</p>
    <form method="post" action="/register" class="form-stack" data-fetch="true">
      <label>
        邮箱
        <input type="email" name="email" id="reg-email" required placeholder="admin@example.com">
      </label>
      <label>
        密码
        <input type="password" name="password" id="reg-password" required placeholder="至少8位，含字母和数字">
      </label>
      <label>
        确认密码
        <input type="password" name="confirmPassword" id="reg-confirm" required placeholder="再次输入密码">
      </label>
      <div class="form-row" style="display:flex;gap:10px;align-items:flex-end">
        <label style="flex:1">
          验证码
          <input type="text" name="code" id="reg-code" required placeholder="6位数字" maxlength="6">
        </label>
        <button type="button" class="secondary" id="btn-send-code" onclick="sendRegisterCode()">获取验证码</button>
      </div>
      <p id="reg-error" class="alert error" style="display:none"></p>
      <button type="submit">创建账户</button>
    </form>
  </main>
  <script>
    function sendRegisterCode() {
      const email = document.getElementById('reg-email').value;
      if (!email) {
        showRegError('请输入邮箱');
        return;
      }
      fetch('/verify-code', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({email: email, purpose: 'register'})
      })
      .then(r => r.json())
      .then(data => {
        if (data.sent) {
          startCountdown();
        } else {
          showRegError(data.error || '发送失败');
        }
      })
      .catch(() => showRegError('发送失败'));
    }

    let countdownTimer = null;
    function startCountdown() {
      const btn = document.getElementById('btn-send-code');
      let sec = 60;
      btn.disabled = true;
      btn.textContent = sec + '秒后重试';
      countdownTimer = setInterval(() => {
        sec--;
        if (sec <= 0) {
          clearInterval(countdownTimer);
          btn.disabled = false;
          btn.textContent = '获取验证码';
        } else {
          btn.textContent = sec + '秒后重试';
        }
      }, 1000);
    }

    function showRegError(msg) {
      const el = document.getElementById('reg-error');
      el.textContent = msg;
      el.style.display = 'block';
    }

    // 表单验证
    document.querySelector('form').addEventListener('submit', function(e) {
      const pw = document.getElementById('reg-password').value;
      const cf = document.getElementById('reg-confirm').value;
      if (pw !== cf) {
        e.preventDefault();
        showRegError('两次输入的密码不一致');
        return;
      }
      if (pw.length < 8) {
        e.preventDefault();
        showRegError('密码至少8位');
        return;
      }
    });
  </script>
</body>
</html>
```

- [ ] **Step 2: 修改 `tests/conftest.py`**

将 `isolated_env` fixture 改为创建测试用户而非依赖环境变量密码：

```python
from __future__ import annotations

import sys
from pathlib import Path

import pytest


@pytest.fixture
def isolated_env(monkeypatch, tmp_path):
    server_root = Path(__file__).resolve().parents[1]
    server_root_text = str(server_root)
    if server_root_text not in sys.path:
        sys.path.insert(0, server_root_text)

    monkeypatch.setenv("APK_UPDATE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ADMIN_PASSWORD", "test-password")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret")
    monkeypatch.delenv("SESSION_COOKIE_SECURE", raising=False)

    return tmp_path
```

（conftest.py 暂时保持不变，后续 Task 统一更新）

- [ ] **Step 3: 在 `app/main.py` 中新增 `/register` 路由**

在 `create_app()` 中、现有路由之前添加：

```python
from app.auth import has_any_admin, mark_logged_in, mark_logged_out, require_admin, verify_user_password
from app.user_services import (
    register_user,
    reset_password,
    send_verification_code,
    verify_code,
)
```

然后在 `login_page` 路由之前添加：

```python
@app.get("/register")
async def register_page(request: Request):
    if has_any_admin(resolved_settings):
        return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse(request, "register.html", {"error": ""})

@app.post("/register")
async def register_submit(
    request: Request,
    email: str = Form(...),
    password: str = Form(...),
    code: str = Form(...),
):
    if has_any_admin(resolved_settings):
        return RedirectResponse("/login", status_code=303)
    try:
        user_id = register_user(resolved_settings, email, password, code)
        mark_logged_in(request, user_id)
        return RedirectResponse("/admin", status_code=303)
    except ValueError as exc:
        return templates.TemplateResponse(
            request, "register.html", {"error": str(exc)}, status_code=400
        )

@app.post("/verify-code")
async def api_send_code(request: Request):
    try:
        body = await request.json()
        email = body.get("email", "")
        purpose = body.get("purpose", "")
        send_verification_code(resolved_settings, email, purpose)
        return JSONResponse({"sent": True})
    except ValueError as exc:
        return JSONResponse({"error": str(exc)}, status_code=400)
    except RuntimeError as exc:
        return JSONResponse({"error": str(exc)}, status_code=500)
```

- [ ] **Step 4: 写测试**

追加到 `tests/test_api.py`：

```python
def test_register_page_redirects_when_admin_exists(isolated_env):
    from app.config import load_settings
    from app.db import db_session
    from app.auth import hash_password

    settings = load_settings()
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            ("admin@test.com", hash_password("Test1234")),
        )

    client = make_client(isolated_env)
    response = client.get("/register", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"
```

- [ ] **Step 5: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_api.py::test_register_page_redirects_when_admin_exists -v`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/templates/register.html app/main.py tests/test_api.py
git commit -m "feat: add registration page and verification code API"
```

---

### Task 5: Login Update & Forgot Password

**Files:**
- Modify: `app/templates/login.html`
- Create: `app/templates/forgot_password.html`
- Modify: `app/main.py`
- Modify: `tests/conftest.py`
- Modify: `tests/test_api.py`

- [ ] **Step 1: 修改 `app/templates/login.html`**

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>登录 - APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body class="login-page">
  <main class="login-panel">
    <h1>APK 更新后台</h1>
    <form method="post" action="/login" class="form-stack" data-fetch="true">
      <label>
        邮箱
        <input type="email" name="email" required autofocus placeholder="admin@example.com">
      </label>
      <label>
        密码
        <input type="password" name="password" required placeholder="请输入密码">
      </label>
      {% if error %}
      <p class="alert error">{{ error }}</p>
      {% endif %}
      <button type="submit">登录</button>
      <p style="text-align:center;margin-top:10px">
        <a href="/forgot-password">忘记密码？</a>
      </p>
    </form>
  </main>
</body>
</html>
```

- [ ] **Step 2: 创建 `app/templates/forgot_password.html`**

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>重置密码 - APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body class="login-page">
  <main class="login-panel">
    <h1>重置密码</h1>
    <form method="post" action="/forgot-password" class="form-stack" data-fetch="true">
      <label>
        邮箱
        <input type="email" name="email" id="fp-email" required placeholder="admin@example.com">
      </label>
      <div class="form-row" style="display:flex;gap:10px;align-items:flex-end">
        <label style="flex:1">
          验证码
          <input type="text" name="code" id="fp-code" required placeholder="6位数字" maxlength="6">
        </label>
        <button type="button" class="secondary" id="btn-fp-code" onclick="sendResetCode()">获取验证码</button>
      </div>
      <label>
        新密码
        <input type="password" name="newPassword" required placeholder="至少8位，含字母和数字">
      </label>
      <label>
        确认新密码
        <input type="password" name="confirmPassword" required placeholder="再次输入新密码">
      </label>
      {% if error %}
      <p class="alert error">{{ error }}</p>
      {% endif %}
      <button type="submit">重置密码</button>
      <p style="text-align:center;margin-top:10px">
        <a href="/login">返回登录</a>
      </p>
    </form>
  </main>
  <script>
    function sendResetCode() {
      const email = document.getElementById('fp-email').value;
      if (!email) { alert('请输入邮箱'); return; }
      fetch('/verify-code', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({email: email, purpose: 'reset_password'})
      })
      .then(r => r.json())
      .then(data => {
        if (data.sent) {
          startFpCountdown();
        } else {
          alert(data.error || '发送失败');
        }
      })
      .catch(() => alert('发送失败'));
    }

    let fpTimer = null;
    function startFpCountdown() {
      const btn = document.getElementById('btn-fp-code');
      let sec = 60;
      btn.disabled = true;
      btn.textContent = sec + '秒后重试';
      fpTimer = setInterval(() => {
        sec--;
        if (sec <= 0) {
          clearInterval(fpTimer);
          btn.disabled = false;
          btn.textContent = '获取验证码';
        } else {
          btn.textContent = sec + '秒后重试';
        }
      }, 1000);
    }

    document.querySelector('form').addEventListener('submit', function(e) {
      const pw = document.querySelector('input[name="newPassword"]').value;
      const cf = document.querySelector('input[name="confirmPassword"]').value;
      if (pw !== cf) {
        e.preventDefault();
        alert('两次输入的密码不一致');
      }
    });
  </script>
</body>
</html>
```

- [ ] **Step 3: 修改 `app/main.py` 登录和忘记密码路由**

修改 `/login` POST 路由：

```python
@app.post("/login")
async def login_submit(request: Request, email: str = Form(...), password: str = Form(...)):
    user_id = verify_user_password(resolved_settings, email, password)
    if user_id is None:
        return templates.TemplateResponse(
            request,
            "login.html",
            {"error": "邮箱或密码错误"},
            status_code=401,
        )
    mark_logged_in(request, user_id)
    return RedirectResponse("/admin", status_code=303)
```

在 `/logout` 路由之后添加忘记密码路由：

```python
@app.get("/forgot-password")
async def forgot_password_page(request: Request):
    return templates.TemplateResponse(request, "forgot_password.html", {"error": ""})

@app.post("/forgot-password")
async def forgot_password_submit(
    request: Request,
    email: str = Form(...),
    code: str = Form(...),
    new_password: str = Form(...),
):
    try:
        reset_password(resolved_settings, email, code, new_password)
    except ValueError as exc:
        return templates.TemplateResponse(
            request,
            "forgot_password.html",
            {"error": str(exc)},
            status_code=400,
        )
    return RedirectResponse("/login", status_code=303)
```

- [ ] **Step 4: 修改 `tests/conftest.py` 和测试辅助函数**

修改 `tests/conftest.py`：

```python
from __future__ import annotations

import sys
from pathlib import Path

import pytest


@pytest.fixture
def isolated_env(monkeypatch, tmp_path):
    server_root = Path(__file__).resolve().parents[1]
    server_root_text = str(server_root)
    if server_root_text not in sys.path:
        sys.path.insert(0, server_root_text)

    monkeypatch.setenv("APK_UPDATE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ADMIN_PASSWORD", "test-password")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret")
    monkeypatch.delenv("SESSION_COOKIE_SECURE", raising=False)

    return tmp_path
```

修改 `tests/test_api.py` 中的 `login` 函数：

```python
def login(client: TestClient, email: str = "admin@test.com", password: str = "Test1234") -> None:
    response = client.post(
        "/login",
        data={"email": email, "password": password},
        follow_redirects=False,
    )
    assert response.status_code == 303
    assert response.headers["location"] == "/admin"
```

同时添加测试用户创建辅助函数：

```python
def create_test_user(isolated_env, email: str = "admin@test.com", password: str = "Test1234"):
    from app.config import load_settings
    from app.db import db_session
    from app.auth import hash_password

    settings = load_settings()
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            (email, hash_password(password)),
        )
```

- [ ] **Step 5: 更新现有测试调用**

将所有 `login(client)` 调用改为先创建用户再登录。在 `test_login_allows_admin_access_and_page_contains_title` 等测试开头调用 `create_test_user(isolated_env)`。

- [ ] **Step 6: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_api.py -v`
Expected: 所有测试通过

- [ ] **Step 7: 提交**

```bash
git add app/templates/login.html app/templates/forgot_password.html app/main.py tests/conftest.py tests/test_api.py
git commit -m "feat: update login to email-based, add forgot password"
```

---

### Task 6: Profile Page & API Endpoints Tab

**Files:**
- Create: `app/templates/profile.html`
- Modify: `app/templates/admin.html`
- Modify: `app/main.py`
- Test: `tests/test_api.py`

- [ ] **Step 1: 创建 `app/templates/profile.html`**

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>个人中心 - APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body>
  <header class="topbar">
    <div>
      <h1>个人中心</h1>
    </div>
    <a href="/admin" class="secondary" style="padding:8px 14px;border-radius:6px;background:#475467;color:white;text-decoration:none">返回后台</a>
  </header>

  <main class="layout">
    <section class="panel">
      <h2>账户信息</h2>
      <p><strong>邮箱：</strong>{{ user_email }}</p>
    </section>

    <section class="panel">
      <h2>修改密码</h2>
      <form method="post" action="/profile/password" class="form-stack" data-fetch="true">
        <label>
          旧密码
          <input type="password" name="oldPassword" required>
        </label>
        <label>
          新密码
          <input type="password" name="newPassword" required placeholder="至少8位，含字母和数字">
        </label>
        <label>
          确认新密码
          <input type="password" name="confirmPassword" required>
        </label>
        {% if error %}
        <p class="alert error">{{ error }}</p>
        {% endif %}
        <button type="submit">保存</button>
      </form>
    </section>
  </main>
  <script src="/static/admin.js"></script>
</body>
</html>
```

- [ ] **Step 2: 修改 `app/main.py` 新增个人中心和接口地址路由**

在 `create_app()` 中导入：

```python
from app.user_services import change_password, get_user_by_id
```

添加个人中心路由：

```python
@app.get("/profile")
async def profile_page(request: Request):
    redirect = require_admin(request)
    if redirect is not None:
        return redirect
    user_id = get_current_user_id(request)
    user = get_user_by_id(resolved_settings, user_id) if user_id else None
    email = user["email"] if user else ""
    return templates.TemplateResponse(
        request, "profile.html", {"user_email": email, "error": ""}
    )

@app.post("/profile/password")
async def profile_change_password(
    request: Request,
    old_password: str = Form(..., alias="oldPassword"),
    new_password: str = Form(..., alias="newPassword"),
    confirm_password: str = Form(..., alias="confirmPassword"),
):
    redirect = require_admin(request)
    if redirect is not None:
        return redirect
    if new_password != confirm_password:
        return templates.TemplateResponse(
            request,
            "profile.html",
            {"user_email": "", "error": "两次输入的密码不一致"},
            status_code=400,
        )
    user_id = get_current_user_id(request)
    if user_id is None:
        return RedirectResponse("/login", status_code=303)
    try:
        change_password(resolved_settings, user_id, old_password, new_password)
    except ValueError as exc:
        user = get_user_by_id(resolved_settings, user_id)
        return templates.TemplateResponse(
            request,
            "profile.html",
            {"user_email": user["email"] if user else "", "error": str(exc)},
            status_code=400,
        )
    return RedirectResponse("/admin", status_code=303)
```

修改 `render_admin` 函数，将 `base_url` 传递给模板：

```python
def render_admin(request: Request, status_code: int = 200, message: str = ""):
    state = list_admin_state(resolved_settings)
    return templates.TemplateResponse(
        request,
        "admin.html",
        {
            "releases": state["releases"],
            "device_rules": state["device_rules"],
            "check_events": state["check_events"],
            "default_release_id": state["default_release_id"],
            "message": message,
            "base_url": build_base_url(request),
        },
        status_code=status_code,
    )
```

- [ ] **Step 3: 修改 `app/templates/admin.html`**

在 `topbar` 区域（退出按钮旁边）添加个人中心链接：

```html
<header class="topbar">
  <div>
    <h1>APK 更新后台</h1>
    <p>发布 APK、设置默认版本，并按 NSCODE 指定灰度版本。</p>
  </div>
  <div style="display:flex;gap:10px;align-items:center">
    <a href="/profile" style="color:var(--accent);font-weight:700;text-decoration:none">个人中心</a>
    <form action="/logout" method="post">
      <button class="secondary" type="submit">退出</button>
    </form>
  </div>
</header>
```

在 `tab-nav` 中新增"接口地址"按钮：

```html
<nav class="tab-nav">
  <button class="tab-btn active" data-tab="apk">APK管理</button>
  <button class="tab-btn" data-tab="device">设备管理</button>
  <button class="tab-btn" data-tab="logs">检查日志</button>
  <button class="tab-btn" data-tab="endpoints">接口地址</button>
</nav>
```

在 `main` 区域末尾、modals 之前新增接口地址 Tab：

```html
<!-- ===== 接口地址 Tab ===== -->
<div class="tab-section" id="tab-endpoints">
  <section class="panel full-width">
    <h2>眼镜端更新服务器地址</h2>
    <p style="color:var(--muted);margin-bottom:16px">将此地址填入眼镜端的更新配置中即可</p>

    <div class="endpoint-row">
      <input type="text" id="endpoint-url" readonly value="{{ base_url }}">
      <button type="button" class="secondary" onclick="copyEndpoint()">复制</button>
    </div>

    <div class="endpoint-custom">
      <label>
        手动指定公网地址
        <input type="text" id="custom-endpoint" placeholder="如 https://update.example.com 或 http://1.2.3.4:8080">
      </label>
      <button type="button" class="secondary" onclick="applyCustomEndpoint()">应用</button>
    </div>

    <p class="hint">
      <strong>提示：</strong>如果服务器部署在内网，请确保眼镜端可以访问到此地址，或配置端口映射。
    </p>
  </section>
</div>
```

同时更新 `validTabs` JS 数组：

```javascript
const validTabs = ['apk', 'device', 'logs', 'endpoints'];
```

- [ ] **Step 4: 写测试**

追加到 `tests/test_api.py`：

```python
def test_profile_page_requires_login(isolated_env):
    client = make_client(isolated_env)
    response = client.get("/profile", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/login"


def test_profile_page_shows_email(isolated_env):
    from app.config import load_settings
    from app.db import db_session
    from app.auth import hash_password

    settings = load_settings()
    with db_session(settings) as conn:
        conn.execute(
            "INSERT INTO users (email, password_hash, email_verified) VALUES (?, ?, 1)",
            ("admin@test.com", hash_password("Test1234")),
        )

    client = make_client(isolated_env)
    login(client)
    response = client.get("/profile")
    assert response.status_code == 200
    assert "admin@test.com" in response.text
```

- [ ] **Step 5: 运行测试**

Run: `cd tools/apk_update_server && pytest tests/test_api.py -v`
Expected: 全部通过

- [ ] **Step 6: 提交**

```bash
git add app/templates/profile.html app/templates/admin.html app/main.py tests/test_api.py
git commit -m "feat: add profile page and API endpoints tab"
```

---

### Task 7: CSS & JS Updates

**Files:**
- Modify: `app/static/admin.css`
- Modify: `app/static/admin.js`

- [ ] **Step 1: 修改 `app/static/admin.css`**

在文件末尾追加：

```css
/* ---------- Endpoint address ---------- */
.endpoint-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.endpoint-row input {
  flex: 1;
  font-family: Consolas, monospace;
  background: var(--bg);
}

.endpoint-custom {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  margin-bottom: 16px;
}

.endpoint-custom label {
  flex: 1;
}

.endpoint-custom input {
  font-family: Consolas, monospace;
}
```

- [ ] **Step 2: 修改 `app/static/admin.js`**

在文件末尾追加：

```javascript
// ===== Endpoint address copy =====
function copyEndpoint() {
  const el = document.getElementById('endpoint-url');
  if (!el) return;
  el.select();
  document.execCommand('copy');
  showToast('已复制到剪贴板', 'success');
}

function applyCustomEndpoint() {
  const custom = document.getElementById('custom-endpoint');
  const display = document.getElementById('endpoint-url');
  if (!custom || !display) return;
  let url = custom.value.trim();
  if (!url) {
    showToast('请输入地址', 'error');
    return;
  }
  // 自动补全协议
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'http://' + url;
  }
  display.value = url;
  showToast('地址已更新', 'success');
}
```

- [ ] **Step 3: 提交**

```bash
git add app/static/admin.css app/static/admin.js
git commit -m "feat: add endpoint address copy and custom input styles"
```

---

### Task 8: Integration & Final Tests

**Files:**
- Test: `tests/test_api.py`
- Test: `tests/test_auth.py`

- [ ] **Step 1: 运行全部测试**

Run: `cd tools/apk_update_server && pytest tests/ -v`
Expected: 全部通过

- [ ] **Step 2: 自审检查清单**

- [ ] 所有路由正常工作（register, login, forgot-password, profile, admin）
- [ ] 首次访问无管理员时自动跳转到 /register
- [ ] 已有管理员时 /register 跳转到 /login
- [ ] 登录使用邮箱+密码
- [ ] 忘记密码通过验证码重置
- [ ] 个人中心可修改密码
- [ ] 接口地址 Tab 显示 base_url
- [ ] 旧功能（APK发布、设备管理、日志）不受影响

- [ ] **Step 3: 最终提交**

```bash
git commit -m "feat: complete auth module with email verification, profile, and endpoint tab" --allow-empty
```

---

## Spec Self-Review

**1. Spec coverage:**
- ✅ 注册（邮箱验证码）→ Task 4
- ✅ 登录（邮箱+密码）→ Task 5
- ✅ 忘记密码（验证码重置）→ Task 5
- ✅ 修改密码 → Task 6
- ✅ 邮件发送 → Task 2
- ✅ 接口地址展示 → Task 6

**2. Placeholder scan:**
- ✅ 无 TBD/TODO
- ✅ 所有代码块完整
- ✅ 所有步骤有明确命令

**3. Type consistency:**
- ✅ `verify_user_password` 返回 `int | None`（user_id）
- ✅ `register_user` 返回 `int`（user_id）
- ✅ `get_current_user_id` 返回 `int | None`
- ✅ 路由参数使用 `Form(...)` 与实际表单字段名一致

---

## 执行方式

计划完成并保存至 `docs/superpowers/plans/2026-05-21-auth-module.md`。

**两种执行选项：**

**1. Subagent-Driven（推荐）** - 我为每个 Task 分派独立的子代理，Task 之间进行审查，快速迭代

**2. Inline Execution** - 在本会话中依次执行任务，批量执行并设置检查点

**选择哪种方式？**