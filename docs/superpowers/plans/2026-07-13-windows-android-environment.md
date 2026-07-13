# Windows Android Environment Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不依赖 WSL 或 Git Bash 的前提下，通过 Android Studio 管理的 Windows 工具链完成项目环境诊断、测试、构建、APK 校验、真机调试和发布包暂存。

**Architecture:** 在 `scripts/android/` 增加职责单一的 PowerShell 入口，并由 `common.ps1` 集中处理安全的 `.env` 解析、路径发现、外部命令执行和 APK 元数据读取。保留现有 Bash 脚本作为旧兼容入口，PowerShell 成为文档和验收的权威路径。

**Tech Stack:** Windows PowerShell 5.1、Android Studio JBR 21、Gradle Wrapper 8.6、Android SDK 34、Build Tools 35.0.0、NDK 29.0.14206865、CMake 3.22.1、Windows ADB。

## Global Constraints

- 默认构建变体固定为 `standardDebug` / `standardRelease`。
- JDK 固定使用 Android Studio JBR 21，不升级 Gradle、AGP、Kotlin 或 Android 依赖。
- compileSdk / targetSdk 固定为 34 / 34，Build Tools 固定为 35.0.0。
- NDK 固定为 29.0.14206865，CMake 固定为 3.22.1。
- 不修改业务代码、推理链路、JNI 接口和模型资产。
- 不删除现有 Bash 脚本；它们只降级为 WSL 兼容入口。
- 不提交 `.env`、`local.properties`、签名密钥、密码或 `release/` 产物。
- 不自动卸载真机应用；遇到证书不匹配只报告错误。
- 当前工作区已有用户改动。每次提交只能暂存本任务列出的文件，并在提交前运行 `git diff --cached --name-only`。

## File Map

| 文件 | 责任 |
| --- | --- |
| `scripts/android/common.ps1` | 配置解析、路径发现、命令执行、SDK 工具和 APK 元数据公共函数 |
| `scripts/android/tests/common.Tests.ps1` | 不依赖第三方模块的公共函数回归测试 |
| `scripts/android/doctor.ps1` | Windows JDK/SDK/NDK/CMake/Gradle/设备环境检查 |
| `scripts/android/gradle.ps1` | 在当前进程设置 Android 环境并调用 `gradlew.bat` |
| `scripts/android/build-debug.ps1` | 构建并确认 `standardDebug` APK |
| `scripts/android/build-release.ps1` | 构建并确认 unsigned `standardRelease` APK |
| `scripts/android/verify-apk.ps1` | APK 签名、包名、版本和证书摘要校验 |
| `scripts/android/install-debug.ps1` | 构建缺失 APK 并通过 Windows ADB 安装 |
| `scripts/android/start-activity.ps1` | 启动指定导出 Activity |
| `scripts/android/logcat.ps1` | 清理并过滤当前设备日志 |
| `scripts/android/screenshot.ps1` | 将设备 PNG 截图保存到指定路径 |
| `scripts/android/package-release.ps1` | Windows 原生发布包版本门禁、签名和暂存 |
| `.env.example` | Windows 本机配置模板，不含秘密 |
| `AGENTS.md`、`CLAUDE.md` | 根级 Windows 标准命令导航 |
| `scripts/android/CLAUDE.md` | Windows Android harness 权威说明 |

---

### Task 1: 建立安全的 Windows 配置与工具解析核心

**Files:**
- Create: `scripts/android/common.ps1`
- Create: `scripts/android/tests/common.Tests.ps1`

**Interfaces:**
- Produces: `Get-AndroidConfiguration`, `Resolve-AndroidTool`, `Invoke-External`, `Get-ApkMetadata`, `Get-ApkCertificateSha256`, `Get-AdbArguments`。
- Consumes: 仓库根 `.env`、进程环境变量以及 Android Studio/SDK 默认安装位置。

- [ ] **Step 1: 创建失败的自包含测试**

测试脚本必须创建临时 `.env`，验证引号去除、空值、环境变量优先级、包含空格的路径、恶意命令不执行以及 ADB 序列号参数：

```powershell
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\common.ps1"

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "$Message expected=[$Expected] actual=[$Actual]" }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "glassdemo-android-test-$PID"
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $marker = Join-Path $tempRoot 'must-not-exist.txt'
    @"
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
ANDROID_HOME=C:\Users\tester\AppData\Local\Android\Sdk
GRADLE_PROXY_HOST=
EVIL=$(New-Item -ItemType File -Path '$marker')
"@ | Set-Content -Encoding UTF8 (Join-Path $tempRoot '.env')

    $values = Read-DotEnv -Path (Join-Path $tempRoot '.env')
    Assert-Equal 'C:\Program Files\Android\Android Studio\jbr' $values.JAVA_HOME 'quoted value'
    Assert-Equal '' $values.GRADLE_PROXY_HOST 'empty value'
    Assert-Equal $false (Test-Path $marker) 'dotenv must not execute commands'
    Assert-Equal '-s,glass-001' ((Get-AdbArguments -Serial 'glass-001') -join ',') 'serial args'
    Write-Host 'PASS common.ps1 tests'
} finally {
    Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/tests/common.Tests.ps1`

Expected: FAIL，错误包含 `common.ps1` 不存在或 `Read-DotEnv` 未定义。

- [ ] **Step 3: 实现最小公共模块**

`common.ps1` 必须：

```powershell
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:AndroidScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:ProjectRoot = (Resolve-Path (Join-Path $script:AndroidScriptRoot '..\..')).Path

function Read-DotEnv([Parameter(Mandatory)][string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (!$trimmed -or $trimmed.StartsWith('#')) { continue }
        if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            throw "Invalid .env line: $line"
        }
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$Matches[1]] = $value
    }
    return $values
}

function Get-AndroidConfiguration {
    $fileValues = @{}
    $envPath = Join-Path $script:ProjectRoot '.env'
    if (Test-Path -LiteralPath $envPath) { $fileValues = Read-DotEnv $envPath }
    function Pick([string]$Name, [string]$Default) {
        if ($fileValues.ContainsKey($Name) -and $fileValues[$Name]) { return $fileValues[$Name] }
        $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
        if ($processValue) { return $processValue }
        return $Default
    }
    [pscustomobject]@{
        ProjectRoot = $script:ProjectRoot
        JavaHome = Pick 'JAVA_HOME' 'C:\Program Files\Android\Android Studio\jbr'
        AndroidHome = Pick 'ANDROID_HOME' (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
        CompileSdk = Pick 'ANDROID_COMPILE_SDK' '34'
        BuildToolsVersion = Pick 'ANDROID_BUILD_TOOLS_VERSION' '35.0.0'
        NdkVersion = Pick 'ANDROID_NDK_VERSION' '29.0.14206865'
        CmakeVersion = Pick 'ANDROID_CMAKE_VERSION' '3.22.1'
        DebugKeystorePath = Pick 'DEBUG_KEYSTORE_PATH' (Join-Path $HOME '.android\debug.keystore')
        Values = $fileValues
    }
}

function Resolve-AndroidTool($Config, [string]$Name) {
    $base = Join-Path $Config.AndroidHome "build-tools\$($Config.BuildToolsVersion)"
    $candidates = switch ($Name) {
        'adb' { @(Join-Path $Config.AndroidHome 'platform-tools\adb.exe') }
        'apksigner' { @(Join-Path $base 'apksigner.bat') }
        default { @(Join-Path $base "$Name.exe", Join-Path $base "$Name.bat") }
    }
    $tool = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$tool) { throw "Android tool not found: $Name" }
    return $tool
}

function Invoke-External([string]$FilePath, [string[]]$Arguments = @()) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $FilePath" }
}

function Get-AdbArguments([string]$Serial) {
    if ($Serial) { return @('-s', $Serial) }
    return @()
}
```

同时实现 APK 函数：`Get-ApkMetadata` 使用 `aapt.exe dump badging` 的第一行正则解析 `PackageName`、`VersionCode`、`VersionName`；`Get-ApkCertificateSha256` 使用 `apksigner.bat verify --print-certs` 解析 `Signer #1 certificate SHA-256 digest`。

- [ ] **Step 4: 运行测试和 PowerShell 语法检查**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/tests/common.Tests.ps1
powershell -NoProfile -Command "[void][scriptblock]::Create((Get-Content -Raw scripts/android/common.ps1)); 'PASS syntax'"
```

Expected: 两条命令均输出 `PASS`。

- [ ] **Step 5: 精确提交**

```powershell
git add scripts/android/common.ps1 scripts/android/tests/common.Tests.ps1
git diff --cached --name-only
git commit -m "开发：建立 Windows Android 脚本公共模块"
```

Expected staged files: 仅上述两个文件。

---

### Task 2: 实现 Windows 环境诊断与配置模板

**Files:**
- Create: `scripts/android/doctor.ps1`
- Modify: `.env.example`
- Test: `scripts/android/tests/common.Tests.ps1`

**Interfaces:**
- Consumes: Task 1 的 `Get-AndroidConfiguration`、`Resolve-AndroidTool`、`Invoke-External`。
- Produces: `doctor.ps1 [-Device]`，成功返回 0，失败返回非零。

- [ ] **Step 1: 扩展失败测试**

在 `common.Tests.ps1` 增加 `ConvertFrom-LocalPropertiesSdkDir` 测试：

```powershell
Assert-Equal 'C:\Users\tester\Android\Sdk' `
    (ConvertFrom-LocalPropertiesSdkDir 'C\:\\Users\\tester\\Android\\Sdk') `
    'local.properties sdk.dir decoding'
```

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/tests/common.Tests.ps1`

Expected: FAIL，`ConvertFrom-LocalPropertiesSdkDir` 未定义。

- [ ] **Step 2: 实现 properties 路径解析和诊断脚本**

在 `common.ps1` 增加：

```powershell
function ConvertFrom-LocalPropertiesSdkDir([string]$Value) {
    return $Value.Replace('\:', ':').Replace('\\', '\')
}
```

`doctor.ps1` 必须使用 `Test-Path` 检查设计文档列出的所有精确组件，并额外执行：

```powershell
$javaLine = & (Join-Path $config.JavaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1
if ($javaLine -notmatch 'version "21[\.]') { throw "JDK 21 required: $javaLine" }

$wrapper = Get-Content -Raw (Join-Path $config.ProjectRoot 'gradle\wrapper\gradle-wrapper.properties')
if ($wrapper -notmatch 'gradle-8\.6-') { throw 'Gradle wrapper must remain 8.6.' }

$appGradle = Get-Content -Raw (Join-Path $config.ProjectRoot 'app\build.gradle')
foreach ($pattern in @('ndkVersion "29\.0\.14206865"', 'standard\s*\{', 'assembleStandardDebug')) {
    if ($appGradle -notmatch $pattern) { throw "Android baseline missing: $pattern" }
}
```

使用 `-Device` 时调用 `adb devices -l`，过滤标题行并要求至少一行匹配 `\sdevice(\s|$)`；`offline` 和 `unauthorized` 必须作为失败并原样显示设备列表。

- [ ] **Step 3: 将 `.env.example` 改为 Windows 优先模板**

模板使用：

```dotenv
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
ANDROID_HOME="C:\Users\developer\AppData\Local\Android\Sdk"
GRADLE_PROXY_HOST=
GRADLE_PROXY_PORT=
ANDROID_COMPILE_SDK=34
ANDROID_NDK_VERSION=29.0.14206865
ANDROID_CMAKE_VERSION=3.22.1
ANDROID_BUILD_TOOLS_VERSION=35.0.0
DEBUG_KEYSTORE_PATH="C:\Users\developer\.android\debug.keystore"
RELEASE_KEYSTORE_PATH=
RELEASE_KEY_ALIAS=
RELEASE_KEYSTORE_PASSWORD=
RELEASE_KEY_PASSWORD=
RELEASE_CERT_SHA256=
```

- [ ] **Step 4: 修正本机配置但不暂存**

手工把本机 `.env` 的 `JAVA_HOME` / `ANDROID_HOME` 改为当前 Windows 路径，把 `local.properties` 改为：

```properties
sdk.dir=C\:\\Users\\wuchaoli\\AppData\\Local\\Android\\Sdk
```

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/doctor.ps1`

Expected: 输出 `Environment OK`、JDK 21、SDK 34、NDK 29.0.14206865、CMake 3.22.1、Build Tools 35.0.0。

- [ ] **Step 5: 回归并提交**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/tests/common.Tests.ps1
git add .env.example scripts/android/common.ps1 scripts/android/doctor.ps1 scripts/android/tests/common.Tests.ps1
git diff --cached --name-only
git commit -m "开发：增加 Windows Android 环境诊断"
```

Expected: `.env` 和 `local.properties` 不在 staged files 中。

---

### Task 3: 实现原生 Gradle、测试与构建入口

**Files:**
- Create: `scripts/android/gradle.ps1`
- Create: `scripts/android/build-debug.ps1`
- Create: `scripts/android/build-release.ps1`

**Interfaces:**
- Consumes: Task 2 的 `doctor.ps1` 和 Task 1 配置对象。
- Produces: 参数透传的 `gradle.ps1`，以及固定变体构建入口。

- [ ] **Step 1: 先验证入口尚不存在**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 tasks`

Expected: FAIL，文件不存在。

- [ ] **Step 2: 实现 `gradle.ps1`**

核心逻辑必须只影响当前进程：

```powershell
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"
$config = Get-AndroidConfiguration
$env:JAVA_HOME = $config.JavaHome
$env:ANDROID_HOME = $config.AndroidHome
$env:ANDROID_SDK_ROOT = $config.AndroidHome
$gradlew = Join-Path $config.ProjectRoot 'gradlew.bat'
Push-Location $config.ProjectRoot
try {
    & $gradlew @GradleArguments
    exit $LASTEXITCODE
} finally { Pop-Location }
```

若代理 host/port 只配置一个则报错；两者都配置时追加四个 `-Dhttp(s).proxy*` 参数。

- [ ] **Step 3: 实现 debug/release 构建入口**

两个脚本先调用 `doctor.ps1`，再分别调用：

```powershell
& "$PSScriptRoot\gradle.ps1" ':app:assembleStandardDebug' @GradleArguments
& "$PSScriptRoot\gradle.ps1" ':app:assembleStandardRelease' @GradleArguments
```

然后检查精确产物：

- `app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- `app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk`

- [ ] **Step 4: 运行单元测试和 debug 构建**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
```

Expected: Gradle `BUILD SUCCESSFUL`，debug APK 存在。

- [ ] **Step 5: 语法检查并提交**

```powershell
Get-ChildItem scripts/android/*.ps1 | ForEach-Object { [void][scriptblock]::Create((Get-Content -Raw $_)) }
git add scripts/android/gradle.ps1 scripts/android/build-debug.ps1 scripts/android/build-release.ps1
git diff --cached --name-only
git commit -m "开发：增加 Windows 原生 Gradle 构建入口"
```

---

### Task 4: 实现 APK 校验与 ADB 调试入口

**Files:**
- Create: `scripts/android/verify-apk.ps1`
- Create: `scripts/android/install-debug.ps1`
- Create: `scripts/android/start-activity.ps1`
- Create: `scripts/android/logcat.ps1`
- Create: `scripts/android/screenshot.ps1`

**Interfaces:**
- Consumes: `Resolve-AndroidTool`、`Get-ApkMetadata`、`Get-ApkCertificateSha256`、`Get-AdbArguments`。
- Produces: Windows APK 校验、安装、Activity 启动、日志和截图命令。

- [ ] **Step 1: 验证 APK 校验入口尚不存在**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk`

Expected: FAIL，文件不存在。

- [ ] **Step 2: 实现 `verify-apk.ps1`**

参数为必填 `[string]$ApkPath`；解析相对路径到项目根，调用 `apksigner verify --verbose`，再输出：

```text
APK: C:\Users\wuchaoli\Desktop\codespace\HiddenRiskGlass\glassdemo\app\build\outputs\apk\standard\debug\app-standard-debug.apk
Size: a non-zero integer followed by bytes
Package: com.rokid.glesse
Version: versionCode and versionName parsed from the APK
Certificate SHA-256: a non-empty 64-character hexadecimal digest
Signature: verified
```

- [ ] **Step 3: 实现 ADB 入口**

统一参数名为 `-Serial`。关键命令必须分别为：

```powershell
& $adb @serialArgs install -r $apk
& $adb @serialArgs shell am start -n "com.rokid.glesse/$Activity" @ActivityArguments
& $adb @serialArgs logcat -c
& $adb @serialArgs logcat
& $adb @serialArgs exec-out screencap -p
```

`install-debug.ps1` 在 APK 不存在时调用 `build-debug.ps1`，随后调用 `doctor.ps1 -Device`。若输出包含 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，抛出“签名不匹配且不会自动卸载”的明确错误。

`logcat.ps1` 参数为 `-Serial`、`-Clear`、`-Tag`；过滤使用 PowerShell `Select-String -Pattern $Tag`，不依赖 grep。

`screenshot.ps1` 使用 `[IO.File]::WriteAllBytes()` 写入 ADB 捕获的 PNG 字节，不能使用文本管道重定向，避免 Windows PowerShell 破坏二进制内容。

- [ ] **Step 4: 校验现有 APK**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk`

Expected: 包名为 `com.rokid.glesse`，签名状态为 `verified`，证书摘要非空。

- [ ] **Step 5: 静态检查并提交**

```powershell
Get-ChildItem scripts/android/*.ps1 | ForEach-Object { [void][scriptblock]::Create((Get-Content -Raw $_)) }
git add scripts/android/verify-apk.ps1 scripts/android/install-debug.ps1 scripts/android/start-activity.ps1 scripts/android/logcat.ps1 scripts/android/screenshot.ps1
git diff --cached --name-only
git commit -m "开发：迁移 Windows APK 与真机调试工具"
```

---

### Task 5: 迁移发布包签名与版本门禁

**Files:**
- Create: `scripts/android/package-release.ps1`

**Interfaces:**
- Consumes: `build-release.ps1`、`verify-apk.ps1`、APK 公共函数和 `.env` 中可选 `RELEASE_*` 配置。
- Produces: `package-release.ps1 [-ReplaceCurrent]`。

- [ ] **Step 1: 验证入口尚不存在**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/package-release.ps1`

Expected: FAIL，文件不存在。

- [ ] **Step 2: 实现版本门禁**

从 `app/build.gradle` 正则读取 `versionCode` 与 `versionName`；遍历 `release/**/*.apk` 并用 `Get-ApkMetadata` 获取已有版本。规则保持：

- 新 `versionCode` 小于已存在最大值时失败；
- 等于最大值且未传 `-ReplaceCurrent` 时失败；
- 同版本替换必须保持相同证书。

- [ ] **Step 3: 实现签名流程**

先运行 `build-release.ps1`，核对 unsigned APK 元数据。五个 `RELEASE_*` 全部非空时使用正式签名，否则使用 `$HOME\.android\debug.keystore`、alias `androiddebugkey`、密码 `android` 并输出演示包警告。

依次调用：

```powershell
& $zipalign '-f' '-p' '4' $sourceApk $temporaryAlignedApk
& $apksigner 'sign' '--ks' $keystore '--ks-key-alias' $alias `
    '--ks-pass' "pass:$storePassword" '--key-pass' "pass:$keyPassword" `
    '--out' $outputApk $temporaryAlignedApk
```

使用 `try/finally` 删除临时 aligned APK。证书不符合预期时删除刚生成的 APK 和 `.idsig`。

- [ ] **Step 4: 使用不完整正式签名配置验证演示包路径**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/package-release.ps1 -ReplaceCurrent`

Expected: 输出 `Signing type: debug-local-demo`，产物位于 `release/local/`，`verify-apk.ps1` 校验通过。若本机 debug keystore 不存在，先用 Android SDK 的 `keytool` 生成标准 debug keystore，不提交该文件。

- [ ] **Step 5: 提交**

```powershell
git add scripts/android/package-release.ps1
git diff --cached --name-only
git commit -m "开发：迁移 Windows Android 打包签名流程"
```

---

### Task 6: 切换仓库文档到 Windows 权威工作流

**Files:**
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `scripts/android/CLAUDE.md`

**Interfaces:**
- Consumes: Tasks 1–5 已验证的 PowerShell 命令和参数名。
- Produces: 新会话、开发者和 Agent 可直接执行的 Windows 标准流程。

- [ ] **Step 1: 找出仍声明 WSL 为默认入口的当前文档行**

Run:

```powershell
rg -n "构建优先使用 WSL|bash scripts/android|wsl-gradle|win-gradle.*回退" AGENTS.md CLAUDE.md scripts/android/CLAUDE.md
```

Expected: 至少命中当前 WSL 默认说明和 Bash 标准命令。

- [ ] **Step 2: 更新根级导航**

将 `AGENTS.md` 和 `CLAUDE.md` 的构建块替换为：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial $env:ROKID_SERIAL
powershell -ExecutionPolicy Bypass -File scripts/android/package-release.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
```

明确 Windows 本地构建使用 Android Studio JBR/SDK，Rokid Glass 使用同一 SDK 的 `adb.exe`；旧 `.sh` 仅为 WSL 兼容入口。

- [ ] **Step 3: 重写 Android harness 导航**

`scripts/android/CLAUDE.md` 必须记录：

- 当前版本基线；
- Android Studio SDK Manager 需要勾选的组件；
- `.env` 和 `local.properties` Windows 示例；
- PowerShell 构建、校验、安装、日志、截图、打包命令；
- `ExecutionPolicy`、`sdk.dir does not exist`、缺少 NDK/CMake、代理、`unauthorized`、`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 的处理；
- Bash 脚本兼容边界。

- [ ] **Step 4: 验证导航无冲突**

Run:

```powershell
rg -n "构建优先使用 WSL|win-gradle.*回退" AGENTS.md CLAUDE.md scripts/android/CLAUDE.md
rg -n "doctor.ps1|build-debug.ps1|install-debug.ps1|package-release.ps1" AGENTS.md CLAUDE.md scripts/android/CLAUDE.md
```

Expected: 第一条无匹配；第二条在三份文档均有匹配。

- [ ] **Step 5: 提交**

```powershell
git add AGENTS.md CLAUDE.md scripts/android/CLAUDE.md
git diff --cached --name-only
git commit -m "文档：切换 Android 开发到 Windows 原生流程"
```

---

### Task 7: Windows 本机端到端验收

**Files:**
- Modify only if verification exposes a defect: the directly responsible PowerShell script from Tasks 1–5。
- Do not commit: `.env`, `local.properties`, APK, screenshots, logs。

**Interfaces:**
- Consumes: 全部 Windows PowerShell 入口。
- Produces: 可复现的环境、构建、APK 和真机验收证据。

- [ ] **Step 1: 运行静态与公共函数回归**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/tests/common.Tests.ps1
Get-ChildItem scripts/android/*.ps1 | ForEach-Object { [void][scriptblock]::Create((Get-Content -Raw $_)) }
```

Expected: 测试和所有脚本语法检查通过。

- [ ] **Step 2: 验证环境、单元测试与构建**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/doctor.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Expected: 环境版本全部符合 Global Constraints；Gradle `BUILD SUCCESSFUL`；APK 包名 `com.rokid.glesse`、签名有效。

- [ ] **Step 3: 验证真机通路**

连接 Rokid Glass 并接受 USB 调试授权，然后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
$env:ROKID_SERIAL = ((& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices) | Select-String '\sdevice$' | Select-Object -First 1).Line.Split("`t")[0]
if (!$env:ROKID_SERIAL) { throw 'No authorized Rokid device found.' }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial $env:ROKID_SERIAL
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/start-activity.ps1 -Serial $env:ROKID_SERIAL -Activity '.MainMenuActivity'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/logcat.ps1 -Serial $env:ROKID_SERIAL -Clear -Tag 'HiddenRisk|AiInspection|Rokid'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/screenshot.ps1 -Serial $env:ROKID_SERIAL -OutputPath shots/windows-environment-smoke.png
```

Expected: 设备状态为 `device`；安装成功；应用前台启动；清空后能捕获当前运行日志；截图是可打开的 PNG。验证后删除 `shots/windows-environment-smoke.png`。

- [ ] **Step 4: 检查工作区和秘密泄漏**

```powershell
git status --short
git diff --cached --name-only
git diff -- . ':!local.properties'
rg -n "RELEASE_(KEYSTORE_PASSWORD|KEY_PASSWORD)=.+" .env.example scripts/android docs AGENTS.md CLAUDE.md
```

Expected: 无 staged 文件；扫描无密码值；用户原有业务改动保持不变；`.env`、`local.properties` 和产物未被提交。

- [ ] **Step 5: 如验收导致修复，运行完整回归后精确提交**

```powershell
git add scripts/android/common.ps1 scripts/android/doctor.ps1 scripts/android/gradle.ps1 scripts/android/build-debug.ps1 scripts/android/build-release.ps1 scripts/android/verify-apk.ps1 scripts/android/install-debug.ps1 scripts/android/start-activity.ps1 scripts/android/logcat.ps1 scripts/android/screenshot.ps1 scripts/android/package-release.ps1
git diff --cached --name-only
git commit -m "修复：修正 Windows Android 环境验收问题"
```

如果无需修复，不创建空提交。只有 Step 1–4 全部通过，才能声明 Windows 迁移完成；没有真机时必须明确标记 Task 7 Step 3 未完成。

## Plan Self-Review Result

- Spec coverage: 配置、环境诊断、Gradle、debug/release 构建、APK 校验、ADB 工具、签名打包、文档和真机验收均有对应任务。
- Scope: 不升级依赖、不改业务代码、不删除 Bash 工具、不引入 Python CLI。
- Interface consistency: 所有脚本统一使用 `-Serial`；公共函数名称在生产者和消费者任务中一致。
- Secret and dirty-tree safety: 每个提交均要求精确暂存，`.env`、`local.properties`、密钥和产物明确排除。
