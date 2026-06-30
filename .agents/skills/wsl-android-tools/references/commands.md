# ADB Command Snippets

## 脚本入口

```bash
bash scripts/win-android-env.sh
```

```bash
bash scripts/win-adb.sh devices -l
```

```bash
bash scripts/win-gradle.sh :app:compileDebugKotlin
```

## 基础命令

```bash
bash scripts/win-adb.sh devices -l
```

```bash
bash scripts/win-adb.sh -s <serial> devices -l
```

## Gradle

```bash
bash scripts/win-gradle.sh :app:assembleDebug
```

```bash
bash scripts/win-gradle.sh :app:installDebug
```

```bash
bash scripts/win-gradle.sh :app:compileDebugKotlin
```

```bash
bash scripts/win-gradle.sh --project /abs/path/to/project :app:assembleDebug
```

## 日志

```bash
bash scripts/win-adb.sh logcat -c
```

```bash
bash scripts/win-adb.sh logcat
```

```bash
bash scripts/win-adb.sh logcat | grep -E "AiInspection|OnlineHazardDetect|SSE|MotionStability"
```

## 页面启动

```bash
bash scripts/win-adb.sh shell am start -n com.rokid.glesse/com.rokid.glass.InspectionModeActivity
```

```bash
bash scripts/win-adb.sh shell am start -n com.rokid.glesse/com.rokid.glass.hiddenrisk.AiInspectionActivity
```

## 前台页面确认

```bash
bash scripts/win-adb.sh shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"
```

## 截图

```bash
bash scripts/win-adb.sh exec-out screencap -p > /absolute/path/screen.png
```

## 进程与网络

```bash
bash scripts/win-adb.sh shell pidof com.rokid.glesse
```

```bash
bash scripts/win-adb.sh shell netstat -an | grep -E "7443|8006"
```

## 已知限制

- `EnterpriseInfoActivity` 不能直接通过 `adb shell am start` 启动
- `AiInspectionMenuActivity` 不能直接通过 `adb shell am start` 启动
- 这类页面必须从公开入口页进入
