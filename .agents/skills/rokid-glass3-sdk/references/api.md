# Rokid Glass3 SDK API 速查

## 用途

实现或排查具体 Rokid service、`CameraShareHelper` 或离线指令能力时再读本文件。

## 初始化顺序

1. `GlassSdk.isReady()`
2. `GlassSdk.bindSecurityService(context, callback)`
3. `onServiceConnected()` 中执行 `GlassSdk.registerClient(clientId, clientCallback)`
4. 再获取类型化 service

SDK 日志默认可在以下路径查找：

- `Downloads/glass3Log/<clientId>.txt`

## `GlassSdk` 常用 service accessor

- `getGlassMediaService()`
- `getGlassMessageService()`
- `getGlassDeviceService()`
- `getGlassOfflineCmdService()`
- `getGlassOnlineRecService()`
- `getGlassOfflineFeatureRecService()`
- `getGlassCollectService()`
- `getGlassOfflineTtsService()`
- `getGlassTrackService()`
- `getGlassNotificationService()`
- `getGlassFileSystemService()`

如果任务提到的 service 在这里没有展开，先查本地 SDK stub、现有 call site 或官方 changelog，再决定怎么写。

## `CameraShareHelper` 共享预览接口

本仓库实际同时使用两套入口：

### NV21 导出

- `initNv21ExportWithConfig(enableFrontCamera, CameraShareConfig(), Nv21Callback)`
- `releaseNv21Export()`
- `updateTexture()`
- `getTextureId()`
- `getTransformMatrix()`

当前仓库依赖的关键回调：

- `onCameraOpened(width, height)`
- `onNv21Frame(nv21, width, height, timestamp)`
- `onNv21ExportResolutionChanged(width, height, appliedPreviewFps)`
- `onNv21ExportRuntimeParamsChanged(appliedPreviewFps, videoStabilizationEnabled)`
- `onZoomLevelChanged(zoomLevel)`
- `onError(code, msg)`

### Surface 共享预览

- `initSurfaceWithConfig(CameraShareConfig(), SurfaceCallback)`
- `releaseSurface()`

当前仓库依赖的关键回调：

- `onCameraOpened(width, height)`
- `onFrameAvailable()`
- `onSurfaceShareConfigChanged(width, height, appliedPreviewFps, videoStabilizationEnabled)`
- `onZoomLevelChanged(zoomLevel)`
- `onError(code, msg)`

补充说明：

- `RokidCameraPreviewView` 用 `transformMatrix` 判断是否发生横竖轴交换，再决定 crop 方向
- `RokidFrameSource` 统一维护 NV21 / Surface 的 `width`、`height`、`appliedPreviewFps`、`videoStabilizationEnabled`

## 离线语音指令

页面层默认不要直接操作这个 service，统一输入基础设施才是默认入口。

涉及底层接入或排查时，关注：

- `init()`
- `restore()`
- `add(VoiceAction)`
- `addAll(List<VoiceAction>)`
- `remove(VoiceAction)`
- `removeAll()`
- `release()`

典型底层调用形态：

```kotlin
val action = VoiceAction("确认", "que ren", object : IVoiceCallback.Stub() {
    override fun onVoiceTriggered() {
        // 回调到统一输入或宿主逻辑
    }
})

GlassSdk.getGlassOfflineCmdService()?.add(action)
```

## 媒体服务

`GlassSdk.getGlassMediaService()` 常见能力：

- `takePhoto(...)`
- `startRecord(...)`
- `stopRecord()`
- `startAudioRecord(...)`
- `stopAudioRecord(...)`
- `zoomCamera(level)`
- `getZoomLevel()`
- `getMaxZoomLevel()`
- `setMediaStateLister(listener)`
- `removeMediaStateLister(listener)`

如果任务改到 zoom、录制或预览联动，注意同时核对共享预览链路。

## 消息 / 文件服务

`GlassSdk.getGlassMessageService()` 常见能力：

- `setMessageListener(listener)`
- `removeMessageListener(listener)`
- `sendTextMessageByP2P(...)`
- `sendTextMessageByClassicBT(...)`
- `sendStreamData(...)`
- `getGlassFileOperater()`
- `getGlassBtFileOperater()`

`IGlassFileOperate` 常见能力：

- `sendFile(...)`
- `stopSendFile()`
- `isSendingFile()`
- `setFileReceiveListener(listener)`
- `removeFileReceiveListener(listener)`

文件路径要放在 SDK 可访问的公有目录。

## 在线检测 / 离线识别

在线检测：

- `GlassSdk.getGlassOnlineRecService()`
- `startDetection(mode)`
- `stopDetection()`
- `setGlassOnlineRecListener(listener)`
- `removeGlassOnlineRecListener(listener)`

离线特征识别：

- `GlassSdk.getGlassOfflineFeatureRecService()`
- `addFaceFeatureFile(...)`
- `startRecognition(...)`
- `stopRecognition(...)`

如果任务要求“采集”或“离线播报”，顺手核对：

- `getGlassCollectService()`
- `getGlassOfflineTtsService()`

## 设备服务

`GlassSdk.getGlassDeviceService()` 常见能力：

- `getSystemVersion()`
- `getDeviceStatusInfo()`
- `switchMicScene(state)`
- `setVolume(value)`
- `setBrighing(value)`
- `reboot()`

设备侧问题先读系统版本，再判断是不是 SDK / OTA 兼容问题。
