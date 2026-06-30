---
name: wsl-windows-android-build
description: WSL环境下使用Windows原生工具构建Android APK和执行ADB操作。解决WSL与Windows Gradle缓存不兼容问题，通过cmd.exe调用Windows端的gradlew.bat和adb实现无缝构建和部署。
---

# WSL + Windows Android 构建技能

## 问题背景

在 WSL (Windows Subsystem for Linux) 环境中直接运行 `./gradlew` 构建 Android 项目会导致与 Windows 端的 Gradle 缓存不兼容，产生以下错误：

```
Cannot access output property 'destinationDirectory' of task ':app:compileDebugKotlin'
Accessing unreadable inputs or outputs is not supported
Failed to create MD5 hash for file '...kotlin-classes/...class' as it does not exist
```

## 解决方案

使用 Windows 原生的 `cmd.exe` 调用 `gradlew.bat` 和 `adb.exe`，确保构建和部署都在 Windows 环境中执行。

## 核心命令模式

### 1. 清理构建缓存

```bash
cmd.exe /c "rmdir /s /q app\build 2>nul"
```

### 2. 编译 Debug APK

```bash
cmd.exe /c ".\gradlew.bat :app:assembleDebug 2>&1"
```

### 3. 直接编译并安装

```bash
cmd.exe /c "rmdir /s /q app\build 2>nul & .\gradlew.bat :app:assembleDebug 2>&1"
```

### 4. 安装 APK

```bash
cmd.exe /c "adb install -r app\build\outputs\apk\debug\app-debug.apk 2>&1"
```

### 5. 查看日志

```bash
cmd.exe /c "adb logcat -s TAG_NAME:D 2>&1"
```

## 完整工作流示例

### 清理构建并安装

```bash
cmd.exe /c "rmdir /s /q app\build 2>nul & .\gradlew.bat :app:assembleDebug 2>&1" && \
cmd.exe /c "adb install -r app\build\outputs\apk\debug\app-debug.apk 2>&1"
```

### 仅编译不安装

```bash
cmd.exe /c ".\gradlew.bat :app:assembleDebug 2>&1"
```

## 常用 Gradle 任务

| 任务 | 命令 |
|------|------|
| 编译 Debug | `cmd.exe /c ".\gradlew.bat :app:assembleDebug 2>&1"` |
| 编译 Release | `cmd.exe /c ".\gradlew.bat :app:assembleRelease 2>&1"` |
| 清理项目 | `cmd.exe /c ".\gradlew.bat clean 2>&1"` |
| 安装 Debug | `cmd.exe /c ".\gradlew.bat :app:installDebug 2>&1"` |
| 运行测试 | `cmd.exe /c ".\gradlew.bat test 2>&1"` |

## 注意事项

1. **路径分隔符**: Windows 使用反斜杠 `\`，在 cmd.exe 中需要转义或直接使用
2. **错误重定向**: 使用 `2>&1` 将 stderr 重定向到 stdout，确保能看到所有输出
3. **清理优先**: 如果之前用 WSL 构建过，务必先清理 `app\build` 目录
4. **权限**: 确保 Windows 端的 Android SDK 路径已添加到系统 PATH

## 故障排查

### 错误: 'gradlew.bat' 不是内部或外部命令
- 确保在项目根目录执行
- 检查 `gradlew.bat` 文件存在

### 错误: 'adb' 不是内部或外部命令
- 确保 Android SDK 的 `platform-tools` 目录在 Windows PATH 中
- 或使用完整路径: `C:\Users\<用户名>\AppData\Local\Android\Sdk\platform-tools\adb`

### 构建成功但安装失败
- 检查设备是否连接: `cmd.exe /c "adb devices"`
- 检查 APK 路径是否正确
