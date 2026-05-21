# APK 更新服务器权限模块设计文档

> 日期：2026-05-21
> 范围：tools/apk_update_server

---

## 目标

将当前基于环境变量单密码的认证系统升级为基于邮箱的单管理员账户系统，支持：
- 首次启动注册（邮箱验证）
- 邮箱 + 密码登录
- 忘记密码（验证码方式重置）
- 登录后修改密码
- 邮件发送能力
- 接口地址页面（展示眼镜端调用的更新接口 URL，支持一键复制）

---

## 架构

保持单管理员模型，但数据层支持未来扩展多用户。认证流程改为邮箱验证码模式，邮件通过 SMTP 发送。原有 session cookie 机制保持不变。

---

## 数据库设计

### users 表

```sql
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email_verified INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### verification_codes 表

注册和忘记密码共用此表，通过 `purpose` 字段区分。

```sql
CREATE TABLE IF NOT EXISTS verification_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL,
    code TEXT NOT NULL,
    purpose TEXT NOT NULL,  -- 'register' 或 'reset_password'
    expires_at TEXT NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## 页面与流程

### 首次启动注册流程

```
GET / → 检测到无管理员账户 → 302 跳转 /register

GET /register → 显示注册页面（邮箱、密码、确认密码、验证码输入框）
  → 点击"获取验证码" → POST /verify-code {email, purpose: "register"}
    → 后端生成6位验证码，写入 verification_codes，SMTP发送邮件
  → 用户输入验证码 → POST /verify-code {email, code, purpose: "register"}
    → 后端验证通过 → 创建 users 记录 → 自动登录 → 302 跳转 /admin
```

### 登录流程

```
GET /login → 显示登录页面（邮箱、密码）
  → POST /login {email, password}
    → 验证邮箱+密码 → 设置 session → 302 跳转 /admin
```

### 忘记密码流程

```
GET /forgot-password → 显示忘记密码页面（邮箱输入）
  → 点击"获取验证码" → POST /verify-code {email, purpose: "reset_password"}
    → 后端生成验证码并发送邮件
  → 输入验证码+新密码 → POST /reset-password {email, code, newPassword}
    → 验证验证码 → 更新密码 → 跳转 /login
```

### 修改密码流程

```
GET /profile → 显示个人中心（当前邮箱、修改密码表单）
  → POST /profile/password {oldPassword, newPassword}
    → 验证旧密码 → 更新 password_hash → 返回成功
```

---

## API 设计

| 路由 | 方法 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `/register` | GET | - | HTML | 注册页面，无管理员时可用 |
| `/register` | POST | `email`, `password`, `code` | 302 /admin | 提交注册 |
| `/verify-code` | POST | `email`, `purpose` | JSON `{sent: true}` | 发送验证码 |
| `/verify-code` | PUT | `email`, `code`, `purpose` | JSON `{valid: true}` | 验证验证码 |
| `/login` | GET | - | HTML | 登录页面 |
| `/login` | POST | `email`, `password` | 302 /admin | 登录 |
| `/logout` | POST | - | 302 /login | 退出 |
| `/forgot-password` | GET | - | HTML | 忘记密码页面 |
| `/forgot-password` | POST | `email`, `code`, `newPassword` | 302 /login | 重置密码 |
| `/profile` | GET | - | HTML | 个人中心页面 |
| `/profile/password` | POST | `oldPassword`, `newPassword` | 302 /admin | 修改密码 |

### 旧路由变更

- `/login` POST：从接收 `password`（环境变量密码）改为接收 `email` + `password`（用户密码）
- 删除对 `ADMIN_PASSWORD` 环境变量的依赖（首次启动后不再需要）

---

## 邮件模块

新建 `app/mailer.py`，职责单一：发送邮件。

### SMTP 配置（环境变量）

```
SMTP_HOST=smtp.example.com      # SMTP 服务器地址
SMTP_PORT=587                   # 端口（默认 587）
SMTP_USER=xxx                   # 用户名
SMTP_PASS=xxx                   # 密码
SMTP_FROM=noreply@example.com   # 发件人地址
SMTP_TLS=true                   # 是否启用 TLS（默认 true）
```

### 邮件内容

- **主题**：`APK更新后台 - 您的验证码是 123456`
- **内容**：HTML 模板，包含验证码和有效期提示（15 分钟）

### 邮件模板（HTML）

```html
<div style="font-family:sans-serif;max-width:400px;margin:0 auto">
  <h2>APK 更新后台</h2>
  <p>您的验证码是：</p>
  <p style="font-size:32px;font-weight:bold;letter-spacing:8px">{{ code }}</p>
  <p>此验证码将在 15 分钟后失效。</p>
  <p style="color:#666">如非本人操作，请忽略此邮件。</p>
</div>
```

---

## 安全策略

| 策略 | 实现 |
|------|------|
| 密码哈希 | bcrypt（通过 `passlib`） |
| 密码强度 | 最少 8 位，至少包含 1 个字母和 1 个数字 |
| 验证码 | 6 位纯数字随机生成 |
| 验证码有效期 | 15 分钟 |
| 验证码使用次数 | 一次性，验证后标记 `used=1` |
| 发送频率限制 | 同一邮箱 60 秒内只能发送 1 次 |
| Session | 保持现有 session cookie 机制，不设置过期时间（浏览器关闭失效） |

---

## 页面设计

### 注册页面 (`/register`)

- 标题：创建管理员账户
- 表单字段：
  - 邮箱（带格式验证）
  - 密码（最少8位，带可见性切换）
  - 确认密码（需与密码一致）
  - 验证码（输入框 + "获取验证码"按钮）
- 提交按钮：创建账户

### 登录页面 (`/login`)

- 标题：管理员登录
- 表单字段：
  - 邮箱
  - 密码
- 链接：忘记密码？
- 提交按钮：登录

### 忘记密码页面 (`/forgot-password`)

- 标题：重置密码
- 步骤一：输入邮箱 → 获取验证码
- 步骤二：输入验证码
- 步骤三：输入新密码 + 确认密码 → 提交

### 个人中心页面 (`/profile`)

- 显示当前登录邮箱
- 修改密码表单：
  - 旧密码
  - 新密码
  - 确认新密码

### 接口地址页面 (`/admin` 新增 "接口地址" Tab)

此页面展示眼镜端需要配置的服务器基础地址。接口路径在眼镜端代码中已固定，只需配置服务器地址即可。

**展示内容：**
```
http://192.168.1.100:8080
```
或
```
https://update.example.com
```

**自动检测：**
- 基于当前请求的 `Host` 头自动推断基础 URL
- 尝试检测服务器内网 IP（如 `192.168.x.x`）作为备选展示

**交互功能：**
- "复制"按钮：一键复制基础地址到剪贴板
- 手动输入框：管理员可手动填写公网域名或 IP，点击确认后更新展示
- 提示信息：说明此地址需填入眼镜端的更新服务器配置中

---

## 错误处理

| 场景 | 错误提示 |
|------|----------|
| 邮箱已注册 | 该邮箱已注册 |
| 验证码错误/过期 | 验证码错误或已过期 |
| 发送频率过快 | 请稍后再试（60秒） |
| 邮箱或密码错误 | 邮箱或密码错误 |
| 旧密码错误 | 旧密码不正确 |
| 两次密码不一致 | 两次输入的密码不一致 |
| 密码强度不足 | 密码至少8位，包含字母和数字 |

---

## 文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `app/db.py` | 新增 users、verification_codes 表到 SCHEMA |
| 修改 | `app/config.py` | 新增 SMTP 配置字段 |
| 新建 | `app/mailer.py` | SMTP 邮件发送模块 |
| 修改 | `app/auth.py` | 改为基于 users 表的认证 |
| 新建 | `app/services.py`（或新文件） | 用户注册、验证码管理、密码重置业务逻辑 |
| 修改 | `app/main.py` | 新增/修改认证相关路由 |
| 新建 | `app/templates/register.html` | 注册页面 |
| 修改 | `app/templates/login.html` | 改为邮箱登录 |
| 新建 | `app/templates/forgot_password.html` | 忘记密码页面 |
| 新建 | `app/templates/profile.html` | 个人中心页面 |
| 修改 | `app/templates/admin.html` | 顶部增加"个人中心"链接；新增"接口地址"Tab |
| 修改 | `app/static/admin.css` | 新增注册/忘记密码页面样式 |
| 修改 | `app/static/admin.js` | 验证码倒计时、表单验证、接口地址复制功能 |

---

## 兼容性说明

- 首次部署：如果没有 `users` 表记录，自动跳转到注册页面
- 环境变量 `ADMIN_PASSWORD`：保留作为向后兼容，但优先级低于 users 表。如果存在 users 记录，则使用邮箱登录；如果不存在 users 但存在 ADMIN_PASSWORD，仍可用旧方式登录（或统一要求注册）。
- **建议**：首次启动检测到无管理员时强制注册，不再使用环境变量密码登录。`ADMIN_PASSWORD` 仅作为应急回退（通过环境变量直接重置）。
