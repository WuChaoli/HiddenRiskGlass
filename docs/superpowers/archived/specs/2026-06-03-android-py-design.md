# Android 构建脚本 Python 化设计文档

> 目标：将 `scripts/android/` 下的 shell 脚本改造为 Python 版本，实现 WSL / Windows 跨平台统一测试入口。
> Python 版本：3.10+

---

## 1. 背景与动机

当前 `scripts/android/` 下有 14 个 bash 脚本，覆盖环境检查、构建、安装、日志、截图、APK 验证等功能。核心痛点：

- **跨平台不一致**：WSL 编译用 `./gradlew`，Windows 用 `gradlew.bat`；WSL 下 ADB 必须用 Windows 侧 `adb.exe`
- **参数解析脆弱**：shell 脚本的手动参数检查容易出错，帮助信息分散
- **测试集成困难**：pytest/subprocess 调用 shell 脚本时返回值和错误信息难以统一断言

Python 化后，同一套 CLI 在 WSL 和 Windows 原生环境下行为完全一致，平台差异由代码自动处理。

---

## 2. CLI 命令映射

| shell 脚本 | Python CLI 命令 |
|---|---|
| `doctor.sh [--device]` | `python -m android_py doctor [--device]` |
| `build-debug.sh` | `python -m android_py build --debug` |
| `build-release.sh` | `python -m android_py build --release` |
| `install-debug.sh [-s serial]` | `python -m android_py install --debug [-s serial]` |
| `wsl-gradle.sh <args>` | `python -m android_py gradle <args>` |
| `win-adb.sh <args>` | `python -m android_py adb <args>` |
| `logcat.sh [-s serial] [--clear] [--tag regex]` | `python -m android_py logcat [-s serial] [--clear] [--tag regex]` |
| `screenshot.sh [-s serial] <path>` | `python -m android_py screenshot [-s serial] <path>` |
| `start-activity.sh [-s serial] <class> [args...]` | `python -m android_py start [-s serial] <class> [args...]` |
| `verify-apk.sh <apk>` | `python -m android_py verify-apk <apk>` |
| `package-release.sh [--replace-current]` | `python -m android_py package [--replace-current]` |
| `get-qrcode.sh` | `python -m android_py get-qrcode` |

`-s <serial>` 作为全局选项下沉到所有子命令，通过 `argparse` parent parser 统一处理。

---

## 3. 模块架构

```
scripts/android-py/
├── android_py/                 # 核心包
│   ├── __init__.py
│   ├── __main__.py             # CLI 入口: python -m android_py
│   ├── cli.py                  # argparse 子命令定义
│   ├── env.py                  # .env 加载 + 环境变量管理
│   ├── platform_.py            # 平台检测 (WSL vs Windows vs Linux)
│   ├── apk.py                  # APK 信息提取 (aapt/apksigner 封装)
│   ├── adb.py                  # ADB 跨平台代理
│   ├── gradle.py               # Gradle 调用 + 代理清理
│   ├── doctor.py               # 环境检查逻辑
│   ├── build.py                # 构建逻辑
│   ├── install.py              # 安装逻辑
│   ├── logcat.py               # 日志抓取
│   ├── screenshot.py           # 截图
│   └── package_.py             # 打包签名
├── tests/                      # pytest 测试
│   ├── test_env.py
│   ├── test_apk.py
│   ├── test_doctor.py
│   └── fixtures/               # 测试用 APK/fixture
├── requirements.txt
└── README.md
```

### 模块职责

| 模块 | 职责 | 不做什么 |
|------|------|----------|
| `env.py` | 加载 `.env` 和 `local.properties`，类型校验，提供 `EnvConfig` | 不执行任何 shell 命令 |
| `platform_.py` | 检测 WSL/Windows/Linux，返回平台标识 | 不处理业务逻辑 |
| `apk.py` | 调用 `aapt`/`apksigner` 提取 APK 元数据和证书 | 不判断签名策略 |
| `adb.py` | 根据平台选择正确的 adb 路径，封装 subprocess 调用 | 不处理设备状态业务逻辑 |
| `gradle.py` | 组装 Gradle 命令，清理 localhost 代理，执行构建 | 不解析构建输出 |
| `doctor.py` | 逐项检查环境（JDK/SDK/NDK/CMake/Gradle/设备） | 不修改任何配置 |

---

## 4. 跨平台环境检测

`platform_.py` 核心逻辑：

```python
def is_wsl() -> bool:
    return "microsoft" in platform.release().lower()

def is_windows() -> bool:
    return sys.platform == "win32"

def resolve_adb(env: EnvConfig) -> Path:
    if is_wsl():
        # WSL 必须使用 Windows 侧的 adb.exe（USB 设备由 Windows 管理）
        return Path(env.win_android_adb)
    elif is_windows():
        return Path(env.android_home) / "platform-tools" / "adb.exe"
    else:
        return Path(env.android_home) / "platform-tools" / "adb"

def resolve_gradle(env: EnvConfig) -> list[str]:
    if is_windows():
        return ["gradlew.bat"]
    return ["./gradlew"]
```

**设计原则**：环境检测全自动，CLI 用户不需要关心自己在 WSL 还是 Windows。`wsl-gradle.sh` / `win-gradle.sh` / `win-adb.sh` 三个脚本的功能由 `gradle.py` / `adb.py` 内部消化。

---

## 5. `.env` 集成

沿用现有 `.env` + `local.properties`，用 `pydantic` 做类型校验：

```python
from pydantic import BaseModel
from pydantic_settings import BaseSettings
from pathlib import Path

class EnvConfig(BaseSettings):
    java_home: Path
    android_home: Path
    android_compile_sdk: str
    android_ndk_version: str
    android_cmake_version: str
    android_build_tools_version: str
    win_android_adb: Path | None = None
    release_keystore_path: Path | None = None
    release_key_alias: str | None = None
    release_key_password: str | None = None
    release_cert_sha256: str | None = None

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
```

**加载顺序**：
1. 从项目根目录加载 `.env`
2. 校验必需字段（`java_home`、`android_home` 等缺失时给出明确错误）
3. 读取 `local.properties` 校验 `sdk.dir` 与 `ANDROID_HOME` 一致性
4. `doctor` 命令额外检查路径存在性和版本基线

---

## 6. 错误处理与日志

### 返回码

| 返回码 | 含义 |
|---|---|
| `0` | 成功 |
| `1` | 通用错误 |
| `2` | 环境配置缺失/不正确 |
| `3` | 构建失败（Gradle 返回非零） |
| `4` | 设备未连接或 ADB 命令失败 |
| `5` | APK 验证失败（签名不匹配、版本冲突等） |

### 日志级别

- 默认 `INFO`
- `--verbose` / `-v` → `DEBUG`
- `--quiet` / `-q` → `WARNING`（只输出错误）

使用 `logging` 标准库，格式统一为 `[LEVEL] message`。

---

## 7. 测试策略

```python
# tests/test_doctor.py
def test_doctor_checks_java_home():
    result = run_cli("doctor")
    assert result.returncode in (0, 2)

def test_doctor_with_missing_env():
    with patch_env({}):
        result = run_cli("doctor")
        assert result.returncode == 2
        assert "ANDROID_HOME" in result.stderr

# tests/test_apk.py
def test_apk_info_extraction():
    apk = FIXTURES / "app-standard-debug.apk"
    info = apk_info(apk)
    assert info.package_name == "com.rokid.glesse"
    assert info.version_name == "2.0.9"
```

| 测试类型 | 覆盖范围 | 环境要求 |
|---------|----------|----------|
| 单元测试 | `env.py`、`apk.py`、`platform_.py` 的纯函数 | 无需 Android SDK |
| 集成测试 | `doctor`、`build`、`verify-apk` 完整流程 | 需要有效 `.env` 和工具链 |
| CI 测试 | 单元测试 + mock 集成测试 | GitHub Actions 可运行 |

---

## 8. 与 Shell 脚本的共存

**不删除** `scripts/android/` 下的 shell 脚本，并行存在：

```
scripts/
├── android/        # 现有 shell 脚本（保留）
└── android-py/     # 新增 Python 版本
```

- Python 版本是 shell 脚本的**功能等价替代**
- Shell 脚本保留作为**兜底/参考实现**
- 新功能优先在 Python 版本中添加
- `android-py/README.md` 说明迁移关系

---

## 9. 依赖

```
pydantic>=2.0
pydantic-settings>=2.0
```

标准库：`argparse`, `logging`, `pathlib`, `subprocess`, `sys`, `platform`, `os`。

---

## 10. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| `pydantic` 引入额外依赖 | 仅开发/测试环境需要，打包时不影响 APK |
| Windows 路径分隔符差异 | 统一使用 `pathlib.Path`，避免字符串拼接路径 |
| WSL 下 `subprocess` 调用 Windows exe | `adb.py` 显式使用 `win_android_adb` 路径，不依赖 PATH |
| 环境检测误判 | `is_wsl()` 基于 `platform.release()` 包含 `"microsoft"`，WSL2 已验证 |
