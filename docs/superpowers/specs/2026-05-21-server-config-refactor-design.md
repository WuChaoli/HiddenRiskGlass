# APK Update Server 配置重构设计

## 目标

1. 将服务器名称统一改为 `HiddenRiskGlassServer`
2. 新增 JSON 配置文件，把代码中写死的技术参数提炼出来
3. 清理冗余文件

## 方案概述

采用**单一 JSON 配置文件 + 环境变量并存**的方案。JSON 负责技术参数，环境变量继续承载敏感信息和部署相关配置。加载优先级：**环境变量 > JSON 配置 > 硬编码默认值**。

## 服务器名称修改

将所有产品展示名称从 "APK 更新后台" / "APK Update Server" 统一替换为 `HiddenRiskGlassServer`。

| 文件 | 位置 | 当前值 | 新值 |
|---|---|---|---|
| `main.py` | FastAPI title | `APK Update Server` | `HiddenRiskGlassServer` |
| `mailer.py` | 邮件主题常量 | `APK更新后台 - 您的验证码` | `HiddenRiskGlassServer - 您的验证码` |
| `mailer.py` | HTML 模板 `<h2>` | `APK 更新后台` | `HiddenRiskGlassServer` |
| `templates/admin.html` | `<title>` 和 `<h1>` | `APK 更新后台` | `HiddenRiskGlassServer` |
| `templates/login.html` | `<title>` 和 `<h1>` | `APK 更新后台` | `HiddenRiskGlassServer` |
| `templates/register.html` | `<title>` 和 `<h1>` | `APK 更新后台` | `HiddenRiskGlassServer` |
| `templates/forgot_password.html` | `<title>` 和 `<h1>` | `APK 更新后台` | `HiddenRiskGlassServer` |
| `templates/profile.html` | `<title>` | `APK 更新后台` | `HiddenRiskGlassServer` |

## JSON 配置文件设计

### 文件位置

`tools/apk_update_server/config.json`

### 配置结构

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

### 字段说明

| 字段 | 类型 | 默认值 | 来源 |
|---|---|---|---|
| `server_name` | string | `HiddenRiskGlassServer` | 原 `main.py` title |
| `auth.verification_code_length` | int | `6` | 原 `user_services.py` `CODE_LENGTH` |
| `auth.verification_code_expires_minutes` | int | `15` | 原 `user_services.py` `CODE_EXPIRES_MINUTES` |
| `auth.verification_code_send_cooldown_seconds` | int | `60` | 原 `user_services.py` `CODE_SEND_COOLDOWN_SECONDS` |
| `auth.password_min_length` | int | `8` | 原 `user_services.py` `_validate_password_strength` |
| `upload.chunk_size_bytes` | int | `1048576` | 原 `services.py` `CHUNK_SIZE` |

### 加载机制

`config.py` 的 `Settings` dataclass 和 `load_settings()` 函数扩展：

1. 先尝试读取 `config.json`（如果不存在则跳过）
2. 环境变量仍然具有最高优先级，可覆盖 JSON 中的任何值
3. 新增 `server_name` 字段到 `Settings`
4. 原有环境变量配置（`ADMIN_PASSWORD`, `SESSION_SECRET`, `SMTP_*`, `APK_UPDATE_DATA_DIR` 等）保持不变

### 代码修改范围

| 文件 | 修改内容 |
|---|---|
| `config.py` | 新增 `server_name` 字段；增加 JSON 加载逻辑；环境变量仍优先 |
| `user_services.py` | 删除模块级常量 `CODE_LENGTH`, `CODE_EXPIRES_MINUTES`, `CODE_SEND_COOLDOWN_SECONDS`；改为从 `Settings` 读取 |
| `services.py` | 删除模块级常量 `CHUNK_SIZE`；改为从 `Settings` 读取 |
| `main.py` | FastAPI title 使用 `settings.server_name` |
| `mailer.py` | 邮件主题和模板标题使用 `settings.server_name` |

## 冗余文件清理

### 确认删除

| 文件 | 原因 |
|---|---|
| `generate_manifest.py` | 旧版静态 manifest 生成工具，功能已被 FastAPI 的 `/admin/releases` 和 `/releases/latest/*` 端点完全覆盖 |

### 确认保留

| 文件/目录 | 原因 |
|---|---|
| `server.py` | 当前启动入口，保留 |
| `serve.ps1` | PowerShell 启动脚本，保留 |
| `requirements.txt` | 依赖声明，保留 |
| `tests/` | 测试目录，保留 |
| `README.md` | 需要更新以反映新配置方式 |

## 边界与约束

- **不提取的内容**：数据库 schema、HTML 结构、API 协议常量（如 `RESULT_UPDATE = "update"`）、邮件模板 HTML 结构。这些属于代码契约，不应可配置。
- **向后兼容**：`config.json` 是可选的。如果不存在，所有参数使用硬编码默认值，行为与现在完全一致。
- **环境变量优先**：现有部署脚本不需要修改，环境变量仍然可以覆盖 JSON 中的值。
