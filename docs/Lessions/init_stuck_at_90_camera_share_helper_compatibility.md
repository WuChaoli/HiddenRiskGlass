# App 初始化卡在 90%：CameraShareHelper 系统版本兼容性问题

日期：2026-06-01

## 现象

App 启动后进入 `InspectionLoadingActivity` 系统初始化界面，进度条走到 **90%** 后永久卡住，没有任何错误提示，也不会崩溃。

## 排查链路

### 1. 确认前台页面

```bash
adb shell dumpsys activity activities | grep topResumedActivity
# 结果：com.rokid.glass.hiddenrisk.InspectionLoadingActivity
```

### 2. 读取应用文件日志

日志路径：`/sdcard/Android/data/com.rokid.glesse/files/logs/app-YYYY-MM-DD.log`

```text
2026-06-01 10:49:48.527 I/InspectionLoading SDK state=READY
2026-06-01 10:49:48.804 I/RokidFrameSource startFrameStream create helper
2026-06-01 10:49:48.819 I/RokidFrameSource supported preview sizes=
# 此后没有任何日志输出
```

### 3. 检查相机设备状态

```bash
adb shell dumpsys media.camera | grep "Device 0"
# 结果：Device 0 is closed, no client instance
```

相机设备存在，但没有任何客户端持有。

## 根因

90% 进度对应代码中的 **相机初始化阶段**（`LoadingStage.CAMERA_INIT`）：

```kotlin
// InspectionLoadingActivity.kt:375-418
private fun startCameraInit() {
    loadingStage = LoadingStage.CAMERA_INIT
    animateProgressTo(90)
    InspectionCameraCoordinator.acquire(
        owner = CameraOwner.LOADING,
        needPreview = false,
    ) { success ->
        // 此回调永远不会被触发
        if (success) {
            preloadLocalModelIfNeeded()
        }
    }
}
```

调用链路：

```
InspectionCameraCoordinator.acquire()
  └─ RokidFrameSource.startFrameStream()
      └─ CameraShareHelper.initNv21ExportWithConfig()
          └─ 等待 onCameraOpened() 回调（永远不会触发）
```

`CameraShareHelper` 向系统相机服务请求打开 NV21 导出流，但**系统侧没有响应**，既不触发 `onCameraOpened` 也不触发 `onError`，导致应用层无限等待。

## 系统版本关联

| 项目 | 版本 |
|------|------|
| 问题发生时的 OTA | `1.14e008-20260401-150201` |
| CLAUDE.md 推荐的最低 OTA | `1.17.e002-20260509-150201` |
| SDK 版本 | `2.1.9-E` |

升级系统到 `1.17.e002-20260509-150201` 后问题消失，初始化流程正常完成。

## 结论

**Rokid Glass 系统 OTA 版本 `1.14e008` 与 SDK `2.1.9-E` 的 `CameraShareHelper` 存在兼容性问题**，相机共享服务在旧系统上无法正常初始化 NV21 导出流。

## 后续建议

1. **设备准入检查**：App 启动时检查系统 OTA 版本，低于推荐版本时提示用户升级
2. **超时保护**：在 `startCameraInit()` 中添加超时机制（如 15 秒），避免永远卡住
3. **日志增强**：在 `RokidFrameSource.startFrameStream()` 中增加更多关键节点日志，便于未来排查

## 相关文件

- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`
- `app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt`
