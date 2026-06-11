# scripts/android/ — Android 构建与真机调试 Harness

仓库内 Android 构建、APK 打包和 Rokid Glass 真机排障的统一入口。所有 Agent 和开发者均应优先调用本目录脚本。

## 当前构建基线

| 项目 | 基线 |
| --- | --- |
| 默认变体 | `standardDebug` / `standardRelease` |
| Gradle | 8.6, AGP 8.4.2, JDK Temurin 21 |
| compileSdk / NDK / CMake | 34 / 29.0.14206865 / 3.22.1 |
| 真机 ADB | Windows 侧 `adb.exe` |

构建优先使用 WSL 本地 JDK/SDK；眼镜 USB 使用 Windows `adb.exe`。

## 首次配置

```bash
cp .env.example .env    # 填写本机路径，含签名配置
bash scripts/android/doctor.sh
bash scripts/android/doctor.sh --device   # 连接真机时
```

## 标准工作流

```bash
# 构建
bash scripts/android/build-debug.sh
bash scripts/android/verify-apk.sh app/build/outputs/apk/standard/debug/app-standard-debug.apk

# 安装与调试
bash scripts/android/install-debug.sh -s <serial>
bash scripts/android/logcat.sh -s <serial> --clear --tag 'HiddenRisk|AiInspection|Rokid'
bash scripts/android/screenshot.sh -s <serial> shots/debug-screen.png

# 打包
bash scripts/android/package-release.sh
bash scripts/android/package-release.sh --replace-current
```

可直接 ADB 启动的调试页：`HiddenRiskProbeActivity`、`UnifiedInputDebugActivity`、`RawCameraPreviewDebugActivity`。`EnterpriseQrScanActivity` 等声明为 `exported=false`，必须通过应用内流程进入。

打包规则：正式签名完整→`release/全省版-v<versionName>.apk`；不完整→`release/local/...-debug-signed.apk`。`--replace-current` 仅允许同 `versionCode` 重打。`release/` 被 gitignore。

## 脚本入口

| 脚本 | 用途 |
| --- | --- |
| `doctor.sh [--device]` | 检查 JDK/SDK/NDK/CMake/Gradle/设备通路 |
| `wsl-gradle.sh <args...>` | WSL 本地工具链执行任意 Gradle 任务 |
| `win-gradle.sh <args...>` | Windows Gradle 回退入口 |
| `win-adb.sh <args...>` | Windows adb.exe 执行设备命令 |
| `build-debug.sh` | 构建 `standardDebug` APK |
| `build-release.sh` | 构建 unsigned `standardRelease` APK |
| `install-debug.sh [-s serial]` | 安装 debug APK |
| `start-activity.sh [-s serial] <class>` | 启动已导出 Activity |
| `logcat.sh [-s serial] [--clear] [--tag regex]` | 日志抓取 |
| `screenshot.sh [-s serial] <path>` | 设备截图 |
| `verify-apk.sh <apk>` | 校验签名、输出版本和证书指纹 |
| `package-release.sh [--replace-current]` | 构建、签名、校验并暂存 APK |

## 已知问题

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `sdk.dir does not exist` | SDK 路径跨系统不一致 | 修改 `local.properties` 为 `.env` 的 `ANDROID_HOME` |
| 缺少 NDK/CMake | SDK 未安装原生工具链 | 安装 `ndk;29.0.14206865` + `cmake;3.22.1` |
| Maven TLS handshake 失败 | Gradle 继承代理指向 127.0.0.1 | `wsl-gradle.sh` 移除本地代理变量 |
| WSL 看不到 USB 眼镜 | USB 由 Windows ADB 管理 | 配置 `WIN_ANDROID_ADB`，用 `win-adb.sh` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 已装 APK 证书不匹配 | 确认签名策略，不自动卸载用户应用 |

## 验证习惯

- 调试前先清 `logcat`，避免旧日志干扰
- 页面跳转先用 `dumpsys activity activities` 核对前台 Activity
- APK 交付前必须 `verify-apk.sh` 记录 versionCode/versionName/证书摘要
- 打包脚本只生成产物，不修改版本、不提交、不打 tag、不推送
