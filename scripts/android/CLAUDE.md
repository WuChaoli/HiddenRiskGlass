# scripts/android/ — Windows Android 构建与真机调试 Harness

仓库内 Android 构建、APK 打包和 Rokid Glass 真机排障的统一入口。Windows PowerShell 是权威工作流；旧 `.sh` 文件只用于 WSL 兼容。

## 当前基线

| 项目 | 基线 |
| --- | --- |
| 默认变体 | `standardDebug` / `standardRelease` |
| Gradle / JDK | Gradle 8.6 / Android Studio JBR 21 |
| compileSdk / Build Tools | 34 / 35.0.0 |
| NDK / CMake | 29.0.14206865 / 3.22.1 |
| Native ABI | 仅 `arm64-v8a` |
| 真机 ADB | Windows SDK `platform-tools/adb.exe` |

## 首次配置

在 Android Studio SDK Manager 安装 Android SDK Platform 34、Build Tools 35.0.0、NDK 29.0.14206865、CMake 3.22.1、Platform Tools 和 Command-line Tools。复制 `.env.example` 为 `.env`，填写本机 Windows 路径；`local.properties` 使用 Java properties 转义路径：

```properties
sdk.dir=C\:\\Users\\wuchaoli\\AppData\\Local\\Android\\Sdk
```

## 标准工作流

```powershell
# 环境、测试与构建
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk

# 真机
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial $env:ROKID_SERIAL
powershell -ExecutionPolicy Bypass -File scripts/android/start-activity.ps1 -Serial $env:ROKID_SERIAL -Activity '.MainMenuActivity'
powershell -ExecutionPolicy Bypass -File scripts/android/start-alignment-test.ps1 -Serial $env:ROKID_SERIAL -Eye right
powershell -ExecutionPolicy Bypass -File scripts/android/start-alignment-test.ps1 -Serial $env:ROKID_SERIAL -Eye left
powershell -ExecutionPolicy Bypass -File scripts/android/logcat.ps1 -Serial $env:ROKID_SERIAL -Clear -Tag 'HiddenRisk|AiInspection|Rokid'
powershell -ExecutionPolicy Bypass -File scripts/android/screenshot.ps1 -Serial $env:ROKID_SERIAL -OutputPath shots/debug-screen.png

# 打包；正式签名不完整时生成 debug 签名演示包
powershell -ExecutionPolicy Bypass -File scripts/android/package-release.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/package-release.ps1 -ReplaceCurrent
```

可直接 ADB 启动的调试页包括 `HiddenRiskProbeActivity`、`UnifiedInputDebugActivity` 和 `RawCameraPreviewDebugActivity`。`exported=false` 的页面必须通过应用内流程进入。

## PowerShell 入口

| 脚本 | 用途 |
| --- | --- |
| `doctor.ps1 [-Device]` | 检查 JDK、SDK、NDK、CMake、Gradle 和可选设备通路 |
| `gradle.ps1 <args...>` | 在 Windows 环境中执行任意 Gradle Wrapper 任务 |
| `build-debug.ps1` / `build-release.ps1` | 构建 ARM64 debug / unsigned release APK |
| `verify-apk.ps1 <apk>` | 输出包名、版本、证书摘要和签名状态 |
| `install-debug.ps1 -Serial <serial>` | 安装 debug APK，不自动卸载旧应用 |
| `start-activity.ps1` / `logcat.ps1` / `screenshot.ps1` | 真机启动、日志与截图 |
| `start-alignment-test.ps1 [-Serial <serial>] [-Eye left\|right]` | 启动摄像头画面对齐测试页，默认右眼 |
| `package-release.ps1 [-ReplaceCurrent]` | 版本门禁、签名、校验并暂存 APK |

## 常见问题

| 现象 | 处理 |
| --- | --- |
| PowerShell 阻止脚本执行 | 使用命令中的 `-ExecutionPolicy Bypass`，不修改系统全局策略 |
| `sdk.dir does not exist` | 将 `local.properties` 改为当前 Windows SDK 的转义路径 |
| 缺少 NDK/CMake | 在 Android Studio SDK Tools 勾选精确版本 29.0.14206865 / 3.22.1 |
| Maven/Gradle 需要代理 | `.env` 同时设置 `GRADLE_PROXY_HOST` 和 `GRADLE_PROXY_PORT` |
| 设备 `unauthorized` | 在眼镜端接受 USB 调试授权，再运行 `adb devices -l` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | APK 证书与已装应用不一致；脚本不会自动卸载用户数据 |
| 首次 native 构建耗时较长 | NCNN 需要完整编译；后续增量构建复用 `.cxx` 产物 |

## 验证习惯

- 调试前先清空 logcat，避免旧日志干扰。
- 页面跳转后用 `dumpsys activity activities` 核对前台 Activity。
- APK 交付前必须运行 `verify-apk.ps1`，记录版本和证书摘要。
- 正式包必须确认 `RELEASE_*` 配置完整；否则产物只属于本地演示包。
- Bash 脚本不再作为 Windows 迁移后的验收命令。
