# Windows Android 开发环境迁移设计

## 背景

项目已经从 WSL 工作区迁移到 Windows 本地目录，但仓库的 Android 工具链仍以 WSL 为默认运行环境：构建入口调用 `wsl-gradle.sh`，环境检查要求 Linux NDK 工具链，`local.properties` 与 `.env.example` 也使用 Linux 路径。Windows 本机已经安装项目所需的 Android Studio、JBR、SDK、NDK、CMake、Build Tools、ADB 和 Gradle Wrapper，因此本次迁移重点是建立仓库内的 Windows 原生执行入口，而不是重新设计 Android 工程。

## 目标

建立以 Android Studio 管理 SDK/JDK、以 PowerShell 作为仓库统一命令入口的 Windows 原生开发环境，使开发者无需 WSL 或 Git Bash 即可完成：

- 环境诊断；
- `standardDebug` 单元测试与 APK 构建；
- APK 版本、包名和证书校验；
- Rokid Glass 真机识别、安装、启动、日志抓取和截图；
- 调试包与正式包的既有打包流程迁移。

## 非目标

- 不修改应用业务逻辑、推理链路或 JNI 接口。
- 不升级 Gradle、AGP、Kotlin、compileSdk、NDK、CMake 或其他依赖版本。
- 不引入新的跨平台 Python CLI。
- 不删除现有 Bash 脚本；它们仅降级为兼容入口。
- 不创建、迁移或提交正式签名密钥和密码。
- 不自动卸载真机上的现有应用，以免丢失用户数据。

## 已确认基线

| 项目 | 要求 | 当前 Windows 状态 |
| --- | --- | --- |
| 操作系统 | Windows 11 x64 | 已满足 |
| JDK | Android Studio JBR 21 | `C:\Program Files\Android\Android Studio\jbr` 已安装 JDK 21 |
| Gradle | Wrapper 8.6 | `gradlew.bat --version` 已可运行 |
| compileSdk / targetSdk | 34 / 34 | Android SDK Platform 34 已安装 |
| Build Tools | 35.0.0 | 已安装 |
| NDK | 29.0.14206865 | 已安装 |
| CMake | 3.22.1 | 已安装 |
| ADB | Windows platform-tools | 已安装并可从 PATH 解析 |
| 默认变体 | `standardDebug` | Gradle 配置已存在 |

## 方案选择

采用“Android Studio 管理工具链 + PowerShell 原生仓库入口”。

不继续扩展现有 Bash 脚本来兼容 Git Bash，因为这仍会引入 MSYS 路径转换、批处理文件调用和执行权限差异。也不采用 Python CLI，因为本次目标只是完成 Windows 迁移，新增 CLI 框架会扩大范围和维护成本。

## 工具链设计

### 目录和职责

在 `scripts/android/` 中新增 Windows 原生脚本：

| 文件 | 职责 |
| --- | --- |
| `common.ps1` | 加载 `.env`、解析项目路径、查找 SDK 工具、提供统一错误和 APK 元数据函数 |
| `doctor.ps1` | 检查 JBR、SDK 组件、Windows NDK/CMake 工具、Gradle 基线和可选设备通路 |
| `gradle.ps1` | 设置本次进程的 `JAVA_HOME` 与 SDK 环境，调用 `gradlew.bat` 并透传参数和退出码 |
| `build-debug.ps1` | 诊断环境后构建 `standardDebug`，确认 APK 产物存在 |
| `build-release.ps1` | 构建未签名的 `standardRelease` 产物 |
| `verify-apk.ps1` | 使用 `aapt.exe` 和 `apksigner.bat` 输出包名、版本和 SHA-256 证书摘要 |
| `install-debug.ps1` | 必要时构建，然后用 `adb.exe install -r` 安装指定设备 |
| `start-activity.ps1` | 启动允许外部启动的调试 Activity |
| `logcat.ps1` | 支持设备序列号、清空旧日志和标签正则过滤 |
| `screenshot.ps1` | 通过 `exec-out screencap -p` 保存截图 |
| `package-release.ps1` | 保留既有签名完整性检查、版本目录和证书校验规则 |

每个脚本只承担一个职责。公共路径、环境读取和工具定位放入 `common.ps1`，避免各入口重复实现。

### 配置来源

Windows 工具链按以下优先级解析本机配置：

1. 仓库根目录 `.env` 中的显式配置；
2. 当前 PowerShell 进程的环境变量；
3. Android Studio 和默认 Android SDK 安装位置。

`.env.example` 改为 Windows 优先模板，至少包含：

- `JAVA_HOME`：默认指向 Android Studio `jbr`；
- `ANDROID_HOME`：默认指向 `%LOCALAPPDATA%\Android\Sdk`；
- 已固定的 compileSdk、Build Tools、NDK 和 CMake 版本；
- 可选的 Gradle 代理；
- 可选的正式签名字段。

`.env` 和 `local.properties` 继续作为本机文件，不纳入版本控制。PowerShell 读取 `.env` 时只接受简单的 `KEY=VALUE` 配置，不执行其中的命令，避免把配置文件当作脚本运行。

### `local.properties`

Windows Gradle 必须使用 Windows SDK 路径。环境初始化会确保 `local.properties` 包含与解析结果一致的 `sdk.dir`，并按照 Java properties 规则转义盘符，例如：

```properties
sdk.dir=C\:\\Users\\wuchaoli\\AppData\\Local\\Android\\Sdk
```

环境检查只报告不一致和修复命令，不在普通构建过程中静默重写该文件。

## 环境检查设计

`doctor.ps1` 必须检查以下条件并以非零退出码报告失败：

1. `JAVA_HOME\bin\java.exe` 存在，主版本为 21；
2. SDK Platform 34、Build Tools 35.0.0、NDK 29.0.14206865、CMake 3.22.1 已安装；
3. Windows NDK 中存在 `toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe` 和 `clang++.exe`；
4. CMake 目录存在 `bin\cmake.exe` 和 `bin\ninja.exe`；
5. `aapt.exe`、`apksigner.bat`、`zipalign.exe` 和 `adb.exe` 可用；
6. Gradle Wrapper 是 8.6，应用仍固定 NDK 29.0.14206865，且 `standard` flavor 与 `assembleStandardDebug` 别名存在；
7. `local.properties` 的 `sdk.dir` 与当前 Windows SDK 一致；
8. 使用 `-Device` 时，执行 `adb devices -l`，并要求至少一个状态为 `device` 的设备。

检查输出应包含最终采用的 JDK、SDK、版本基线和设备列表，但不得打印签名密码等敏感配置。

缺少 SDK 组件时，错误信息给出 Android Studio SDK Manager 中的组件名，同时输出可选的 `sdkmanager.bat` 安装命令；环境检查本身不自动下载安装。

## 执行流程

### 开发构建

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

### 真机验证

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial <serial>
powershell -ExecutionPolicy Bypass -File scripts/android/logcat.ps1 -Serial <serial> -Clear -Tag 'HiddenRisk|AiInspection|Rokid'
```

真机验证必须使用本次启动后产生的新日志。若安装返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，脚本只报告证书不匹配，不自动卸载旧应用。

## 错误处理

- 所有入口启用严格错误处理，并保留外部命令的实际退出码。
- 路径统一使用 PowerShell 和 .NET 路径 API，不手工拼接 `/mnt/c`、`/c` 或 WSL UNC 路径。
- 外部命令通过参数数组调用，避免含空格路径和签名参数被错误拆分。
- `.env` 缺失时给出从 `.env.example` 初始化的明确提示。
- 正式签名配置不完整时，沿用当前规则生成 debug 签名演示包，不伪装为正式发布包。
- Gradle 下载或 Maven 访问失败时，区分组件缺失、代理配置和网络连接错误，不修改全局代理。

## 文档迁移

更新以下导航内容：

- 根 `AGENTS.md` 和 `CLAUDE.md`：标准命令切换为 PowerShell；
- `scripts/android/CLAUDE.md`：Windows 原生工作流成为权威入口，记录安装组件、配置、构建、真机和常见错误；
- `.env.example`：改为 Windows 路径和变量说明；
- 仍需维护的计划或开发文档不进行批量历史重写，避免无关差异。

现有 `.sh` 文件保留，文档明确它们是旧 WSL 兼容入口，不再作为迁移后的验收命令。

## 验证与验收标准

迁移完成必须依次满足：

1. `doctor.ps1` 在当前 Windows 主机通过，并准确输出 JDK 21、SDK 34、Build Tools 35.0.0、NDK 29.0.14206865 和 CMake 3.22.1；
2. `:app:testStandardDebugUnitTest` 通过；
3. `build-debug.ps1` 成功生成 `app-standard-debug.apk`；
4. `verify-apk.ps1` 能输出 `com.rokid.glesse`、版本号和证书 SHA-256；
5. `doctor.ps1 -Device` 能识别 Rokid Glass；
6. `install-debug.ps1` 能在不卸载应用数据的前提下完成安装；
7. 应用可启动，并能抓取当前运行产生的有效 logcat；
8. `git diff` 中不包含签名密钥、密码、用户私有 `.env` 或无关业务代码变更。

若当前没有连接真机，前四项可先完成，但迁移不能标记为完全验收，必须明确记录真机验证待完成。

## 实施边界

当前工作区已有业务代码、`local.properties`、Python 脚本和其他计划文档的未提交修改。实施时必须只暂存和提交本迁移直接创建或修改的文件；涉及 `local.properties` 的本机调整不得进入提交。每个阶段都应检查 `git diff --cached`，确保没有夹带用户现有工作。
