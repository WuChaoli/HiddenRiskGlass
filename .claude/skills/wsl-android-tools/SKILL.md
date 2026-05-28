---
name: wsl-android-tools
description: Use when the task requires Android real-device validation or Android toolchain work from WSL via Windows-side tools, including checking connected devices with adb.exe, building or installing through gradlew.bat plus a Windows JDK/JBR, launching activities, collecting logcat, capturing screenshots, verifying the foreground activity, or reproducing app flows on a Rokid Glass device. This skill is for terminal-driven smoke tests, runtime debugging, and WSL-to-Windows Android tooling when direct UI automation is unavailable or some pages are not exported.
---

# WSL Android Tools

## Overview

Use this skill for adb-driven 真机验证、安装、日志抓取、WSL 调 Windows JDK 构建和运行态排查。默认先确认 Windows 工具路径，再执行构建、安装、启动、日志和截图，避免把问题误判成代码逻辑故障。

## Quick Rules

- 优先使用本机已验证可用的 adb 路径：`/mnt/c/Users/wuchaoli/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- 在这个环境里，Gradle 安装与构建优先使用 `cmd.exe /C gradlew.bat ...`，不要默认用 `./gradlew`
- 需要 JDK 时，优先走 Windows 侧 Android Studio JBR：`C:\Program Files\Android\Android Studio\jbr`
- 在 `cmd.exe` 中设置 `JAVA_HOME` 时使用 `set "JAVA_HOME=..."`，避免路径尾部空格导致 Gradle 误判目录无效
- 优先复用 `scripts/` 下的包装脚本，不要每次手写完整 Windows 路径
- 先清 `logcat` 再复现问题，否则旧日志会污染判断
- 先用 `dumpsys activity` 确认前台页面，再看截图和日志
- 某些 Activity 若 `exported=false`，不能直接 `adb shell am start`，这类流程必须通过应用内导航进入

## Bundled Scripts

- `scripts/win-android-env.sh`
  - 输出当前解析到的 Windows Android 工具路径，必要时用 `--exports` 生成可 `source` 的环境变量
- `scripts/win-adb.sh`
  - 从 WSL 直接调用 Windows 侧 `adb.exe`
- `scripts/win-gradle.sh`
  - 在当前项目目录或指定目录中调用 `gradlew.bat`，并自动注入 Windows 侧 `JAVA_HOME`

如果当前 shell 直接执行脚本被拦，统一用 `bash scripts/<name>.sh ...` 调用。
如果只是执行常规构建、安装、设备检查，优先直接调用这些脚本。

## Workflow

### 1. 确认设备

先执行：

```bash
bash scripts/win-adb.sh devices -l
```

- 如果没有设备，先排查 USB / 无线连接、授权弹窗、`adb kill-server` / `adb start-server`
- 如果有多个设备，后续命令加 `-s <serial>`

### 2. 安装或重装 APK

构建/安装优先用：

```bash
bash scripts/win-gradle.sh :app:installDebug
```

如果只想构建 APK：

```bash
bash scripts/win-gradle.sh :app:assembleDebug
```

如果只想做 Kotlin 编译校验：

```bash
bash scripts/win-gradle.sh :app:compileDebugKotlin
```

### 3. 清日志并开始复现

```bash
bash scripts/win-adb.sh logcat -c
```

复现时按需抓日志：

```bash
bash scripts/win-adb.sh logcat
```

如果已经知道 tag，优先按 tag 过滤，减少噪音。

### 4. 启动页面

直接启动已导出的 Activity：

```bash
bash scripts/win-adb.sh shell am start -n <package>/<activity>
```

注意：

- `exported=false` 的页面不能直接这样启动
- 对未导出页面，先启动入口页，再通过实体按键、扫码、点击或应用内流程进入

### 5. 校验当前前台页面

```bash
bash scripts/win-adb.sh shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"
```

这个结果比单纯看截图更可靠，适合确认跳转是否真的发生。

### 6. 截图取证

保存当前屏幕：

```bash
bash scripts/win-adb.sh exec-out screencap -p > /absolute/path/screen.png
```

截图适合确认：

- 页面是否停在预期状态
- 文案、按钮、错误提示是否正确
- 某个定时状态是否更新

### 7. 常见排查方式

- 页面没跳转：先看 `dumpsys activity`，再看页面跳转日志
- 页面跳了但无业务动作：看关键 tag 是否有入口日志、网络日志、SSE 日志、传感器日志
- 怀疑超时/定时器没触发：日志里补齐“开始计时”“触发执行”“超时关闭”“收到首包”等关键节点
- 怀疑前台页面正确但 UI 没更新：同时抓截图和 `logcat`

## Rokid Glass Notes

这个环境下已验证：

- 可用设备型号：`RG_glasses`
- 已稳定可用的 adb 路径固定为上面的 Windows SDK 路径
- 已稳定可用的 Windows JDK/JBR 路径：`C:\Program Files\Android\Android Studio\jbr`
- 直接 adb 启动可用的页面通常是公开入口页；业务中间页可能需要应用内导航

若需要现成命令模板，读取 `references/commands.md`。
