# Android 构建与真机调试 Harness

本文档是仓库内 Android 构建、APK 打包和 Rokid Glass 真机排障的统一入口。AI Agent 和开发者均应优先调用本目录脚本，不要自行猜测 JDK、SDK、Gradle variant 或 ADB 路径。

## 当前构建基线

| 项目 | 基线 |
| --- | --- |
| 默认业务变体 | `standardDebug` / `standardRelease` |
| Gradle Wrapper | `8.6` |
| Android Gradle Plugin | `8.4.2` |
| JDK | Temurin 21（WSL 本地已验证） |
| compileSdk | `34` |
| NDK | `29.0.14206865` |
| CMake | `3.22.1` |
| 真机 ADB | Windows 侧 `adb.exe` |

构建优先使用 WSL 本地的 JDK 和 Android SDK；Rokid Glass 的 USB 设备访问使用 Windows 侧 `adb.exe`。Windows Gradle 透传仅作为项目位于 Windows 文件系统时的回退方案。

## 首次配置

```bash
cp .env.example .env
```

填写 `.env` 中的本机路径。该文件包含本地路径或签名配置，已被 `.gitignore` 忽略，不得提交。

```bash
bash scripts/android/doctor.sh
```

需要连接真机时，额外配置 `WIN_ANDROID_ADB` 并验证：

```bash
bash scripts/android/doctor.sh --device
```

`.env` 中完整填写 `RELEASE_KEYSTORE_PATH`、`RELEASE_KEY_ALIAS`、密码和 `RELEASE_CERT_SHA256` 后，打包脚本才会生成正式签名包。未完整配置时会降级生成 debug 签名的本地演示包。

## 标准工作流

### 日常构建

```bash
bash scripts/android/build-debug.sh
bash scripts/android/verify-apk.sh app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

脚本固定执行 `:app:assembleStandardDebug`；不要使用不带 flavor 的 `assembleDebug`。

### 安装与真机调试

```bash
bash scripts/android/install-debug.sh -s <serial>
bash scripts/android/logcat.sh -s <serial> --clear --tag 'HiddenRisk|AiInspection|Rokid'
bash scripts/android/start-activity.sh -s <serial> com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity --es mode sdk_demo_compare
bash scripts/android/screenshot.sh -s <serial> shots/debug-screen.png
```

当前可直接通过 ADB 启动的调试页包括：

- `com.rokid.glass.hiddenrisk.HiddenRiskProbeActivity`
- `com.rokid.glass.hiddenrisk.UnifiedInputDebugActivity`
- `com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity`

`EnterpriseQrScanActivity`、`EnterpriseInfoActivity`、`AiInspectionMenuActivity` 和 `InspectionEndReportActivity` 声明为 `exported=false`，必须通过应用内流程进入，不能把 ADB 启动失败误判为业务故障。

### APK 打包

```bash
bash scripts/android/package-release.sh
bash scripts/android/package-release.sh --replace-current
```

- 正式签名配置完整：生成 `release/全省版-v<versionName>.apk`，并强制校验证书 SHA-256。
- 正式签名配置不完整：生成 `release/local/全省版-v<versionName>-debug-signed.apk`，仅用于本地安装或演示。
- 默认要求新 `versionCode` 大于 `release/` 现有 APK 的最大版本。
- `--replace-current` 仅允许同 `versionCode` 重打，并要求证书与已有同版本 APK 一致；它不允许版本回退。
- APK 文件位于被忽略的 `release/`，不进入 Git 历史；`release/changelog.md` 仍可提交。

已有历史 APK 曾使用不同 `Android Debug` 证书。相同包名但证书不同的 APK 无法覆盖升级，因此 debug 签名产物不得称为正式升级包。

## 脚本入口

| 脚本 | 用途 |
| --- | --- |
| `doctor.sh [--device]` | 检查 JDK、SDK、NDK、CMake、Gradle、variant 和可选设备通路 |
| `wsl-gradle.sh <args...>` | 使用 WSL 本地工具链执行任意 Gradle 任务 |
| `win-gradle.sh <args...>` | Windows Gradle 回退入口，仅适用于非 UNC checkout |
| `win-adb.sh <args...>` | 使用 Windows `adb.exe` 执行设备命令 |
| `build-debug.sh` | 构建 `standardDebug` APK |
| `build-release.sh` | 构建 unsigned `standardRelease` APK |
| `install-debug.sh [-s serial]` | 安装 debug APK 到眼镜 |
| `start-activity.sh [-s serial] <class> [args...]` | 启动已导出的 Activity |
| `logcat.sh [-s serial] [--clear] [--tag regex]` | 清理或过滤抓取日志 |
| `screenshot.sh [-s serial] <path>` | 保存设备截图 |
| `verify-apk.sh <apk>` | 校验签名并输出包名、版本和证书指纹 |
| `package-release.sh [--replace-current]` | 构建、签名、校验并暂存 APK |

## 已知问题与处理

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `sdk.dir does not exist` | `local.properties` 指向另一操作系统的 SDK 路径 | 将 `sdk.dir` 修改为与 `.env` 的 `ANDROID_HOME` 相同，然后重跑 `doctor.sh` |
| 缺少 NDK/CMake | SDK 未安装项目要求的原生工具链 | 安装 `ndk;29.0.14206865` 与 `cmake;3.22.1` |
| Maven/Google 仓库 TLS handshake 失败 | Java/Gradle 继承了指向 `127.0.0.1` 的代理 | `wsl-gradle.sh` 会为本次构建移除本地代理变量 |
| WSL 内看不到 USB 眼镜设备 | USB 由 Windows ADB 管理 | 配置 `WIN_ANDROID_ADB`，使用 `win-adb.sh` |
| Windows Gradle 无法进入项目目录 | WSL checkout 被转换为 UNC 路径 | 使用 WSL 本地 `wsl-gradle.sh`；仅 Windows 文件系统 checkout 可走回退入口 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 已安装 APK 与待装 APK 证书不同 | 确认签名策略；不得自动卸载用户设备上的应用 |

## 验证习惯

- 调试前先清 `logcat`，避免旧日志影响判断。
- 页面跳转问题先使用 `dumpsys activity activities` 核对前台 Activity，再结合截图与日志。
- APK 交付前必须运行 `verify-apk.sh`，记录 `versionCode`、`versionName` 和证书摘要。
- 打包脚本只生成产物，不修改版本文件、不提交代码、不打 tag、不推送远端。
