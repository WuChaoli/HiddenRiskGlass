# Rokid Glass3 SDK API 速查

## 用途

这个文件根据 Rokid Glass3 眼镜端官方 API 文档和当前项目经验整理。实现或排查具体 service 调用时再读，不要默认整篇加载。

官方文档来源：

- `https://x-docs.rokid.com/docs/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.html`
- 本速查按 2026-04-30 可访问页面整理。

## 初始化流程

按这个顺序初始化 SDK：

1. 用 `GlassSdk.isReady()` 跳过重复初始化。
2. 调用 `GlassSdk.bindSecurityService(context, callback)`。
3. 在 `onServiceConnected()` 中调用 `GlassSdk.registerClient(clientId, clientCallback)`。
4. 等注册完成后，再使用 typed `GlassSdk.get...Service()` accessor。
5. 宿主结束时，根据所有权调用 `GlassSdk.unbindSecurityService()` 或 `GlassSdk.release()`。

官方示例里的关键约束：

- 眼镜端 `clientId` 与手机端注册的 `clientId` 要一致，便于手机端路由消息到正确眼镜端应用。
- `registerClient()` 会初始化 SDK 日志配置。

SDK 日志会落在：

- `Downloads/glass3Log/<clientId>.txt`

## `GlassSdk` 暴露的 service accessor

源文档里提到的常见类型化 accessor：

- `getClassicBluetoothService()`
- `getP2PGoService()`
- `getGlassMessageService()`
- `getGlassCommonService()`
- `getGlassMediaService()`
- `getGlassOfflineFeatureRecService()`
- `getGlassOfflineRecService()`
- `getGlassOnlineRecService()`
- `getGlassCollectService()`
- `getGlassTrackService()`
- `getGlassDeviceService()`
- `getGlassBluetoothRingService()`
- `getGlassNotificationService()`
- `getGlassAiChatService()`
- `getGlassFileSystemService()`
- `getGlassTranslateService()`
- `getGlassTtsService()`
- `getGlassOfflineTtsService()`
- `getGlassAsrService()`
- `getGlassOfflineCmdService()`
- `getGlassLiveKitRtcService()`

如果这里只知道 accessor 名称，但没有详细接口说明，就先看本地 SDK stub 或 demo 源码，再决定怎么写。不要凭 accessor 名称臆造方法签名。

## 官方文档未展开的服务

官方眼镜端 API 页面列出了这些入口，但当前速查不记录详细方法。实现前必须查本地 SDK stub、AIDL、反编译声明或官方 demo：

- `getGlassNotificationService()`
- `getGlassAiChatService()`
- `getGlassFileSystemService()`
- `getGlassTranslateService()`
- `getGlassTtsService()`
- `getGlassOfflineTtsService()`
- `getGlassAsrService()`
- `getGlassLiveKitRtcService()`

处理这些服务时只先确认三件事：初始化是否完成、service 是否为 `null`、是否存在成对的 listener/stop/release API。

## 离线语音指令

离线指令词使用 `GlassSdk.getGlassOfflineCmdService()`。

关键方法：

- `init()`
- `restore()`
- `add(VoiceAction)`
- `addAll(List<VoiceAction>)`
- `remove(VoiceAction)`
- `removeAll()`
- `release()`

示例：

```kotlin
val action = VoiceAction("下雪了", "xia xue le", object : IVoiceCallback.Stub() {
    override fun onVoiceTriggered() {
        Log.e(TAG, "下雪了")
    }
})

GlassSdk.getGlassOfflineCmdService()?.add(action)
```

## 媒体服务

相机、录音、录像相关能力走 `GlassSdk.getGlassMediaService()`。

`IMediaServer` 常用方法：

- `startRecord(callback, recordConfig)`
- `stopRecord()`
- `takePhoto(photoResolution, path)`
- `addPhotoCallback(photoFileCallback)`
- `removePhotoCallback(photoFileCallback)`
- `getMaxZoomLevel()`
- `getZoomLevel()`
- `zoomCamera(level)`
- `startAudioRecord(callback)`
- `stopAudioRecord(callback)`
- `setMediaStateLister(listener)`
- `removeMediaStateLister(listener)`

## 消息服务

文本消息、音视频流、二进制传输走 `GlassSdk.getGlassMessageService()`。

`IMessageServer` 常用方法：

- `setMessageListener(listener)`
- `removeMessageListener(listener)`
- `sendTextMessageByP2P(message)`
- `sendTextMessageByP2PWithClient(message, clientId)`
- `sendTextMessageByClassicBT(message)`
- `sendTextMessageByClassicBTWithClient(message, clientId)`
- `sendAudioStreamData()`
- `stopAudioStreamData()`
- `sendVideoStreamData()`
- `stopVideoStreamData()`
- `sendStreamData(tag, data, clientId, callback)`
- `getGlassFileOperater()`
- `getGlassBtFileOperater()`

`IMessageListener` 关键回调：

- `onTextMessage(msg)`
- `onAudioStream(buffer)`
- `onStreamDataReceived(tag, data)`

## 文件传输

文件传输通过 `glassFileOperater` 或 `glassBtFileOperater` 返回的 `IGlassFileOperate` 完成。

关键方法：

- `sendFile(dir, filePath, listener, resultCallback)`
- `stopSendFile()`
- `isSendingFile()`
- `setFileReceiveListener(listener)`
- `removeFileReceiveListener(listener)`

源文档明确给出的约束：

- `filePath` must point to a file in public external storage that the SDK can access
- `dir` 是对端接收目录，例如 `"custom"` 或 `"custom/file/"`

`FileReceiveListener` 里常用的回调：

- `onStart()`
- `onProgressChanged(progress)`
- `onComplete(filePath)`
- `onFail()`
- `onCancel()`

## 在线检测与识别

在线人脸 / 车牌检测使用 `GlassSdk.getGlassOnlineRecService()`。

`IOnlineRecService` 常用方法：

- `startDetection(mode)`
- `stopDetection()`
- `setGlassOnlineRecListener(listener)`
- `removeGlassOnlineRecListener(listener)`
- `recognizeFace(param, callback)`
- `getFaceSamllBitmap(trackId)`
- `getFaceRoundCornerSamllBitmap(trackId)`
- `getLprSamllBitmap(plateNo)`

文档里给出的模式：

- `MODE_NONE = 0`
- `MODE_FACE = 1`
- `MODE_LPR = 2`
- `MODE_MIX = 3`
- `MODE_MOTOR_LPR = 4`

`IGlassDetectionListener` 关键回调：

- `onModeChange(mode)`
- `onFaceTrack(faceModels)`
- `onProcessedFaceModels(processedFaceModels)`
- `onLPRTrack(lprModel)`

## 离线特征识别

需要本地人脸特征库时，使用 `GlassSdk.getGlassOfflineFeatureRecService()`。

`IOfflineFeatureRecService` 常用方法：

- `addFaceFeatureFile(featureName, featurePath)`
- `removeFaceFeature(featureName)`
- `startRecognition(mode, listener)`
- `stopRecognition(listener)`
- `getFaceSamllBitmap(frameId)`
- `getFaceRoundCornerSamllBitmap(frameId)`

`IGlassRecListener` 关键回调：

- `onModeChange(mode)`
- `onFaceTrack(faceModels)`
- `onLPRTrack(lprModel)`
- `onFaceRecognize(result)`
- `onFaceRecognizeNotInLib(result)`

源文档明确说明：这个能力依赖对应的离线库包。

## 设备服务

设备信息和系统控制走 `GlassSdk.getGlassDeviceService()`。

`IDeviceService` 常用方法：

- `getDeviceName()`
- `getSerialNumber()`
- `getSystemVersion()`
- `getDeviceStatusInfo()`
- `switchMicScene(state)`
- `setVolume(value)`
- `setBrighing(value)`
- `reboot()`
- `sendCusEvent(eventCode, extra)`
- `setDeviceEventListener(listener)`
- `setBatteryUpdateListener(listener)`

源文档给出的麦克风场景值：

- `0`: near-field directional
- `1`: far-field directional
- `3`: omnidirectional

实现时要考虑大约 3 秒的切换延迟。

## 通用信息、采集与 Track 服务

官方文档列出了以下入口，但没有在同页展开完整方法：

- `getGlassCommonService()`
- `getGlassCollectService()`
- `getGlassTrackService()`

使用前先查本地 SDK stub 或 demo。不要把在线识别 `IOnlineRecService` 的方法直接套到这些服务上。

## 蓝牙相关服务

经典蓝牙 `IBTService`：

- `getConnectDevices()`
- `makeDeviceDiscoverable()`
- `setClassicBTListener(listener)`
- `removeBlueToothServerListener(listener)`
- `isConnect()`

蓝牙指环 `IBluetoothRingService`：

- `setBluetoothRingState(listener)`
- `isRingConnect()`
- `getRingConnectDevice()`
- `release()`

## 清理

如果宿主组件持有 SDK 生命周期，最终要补齐这些清理动作：

- listener removal methods for every registered listener
- stop methods for recording, streaming, or detection
- `GlassSdk.unbindSecurityService()` or `GlassSdk.release()` where appropriate
- `IOfflineCmdService.release()` when the host owns offline command registration
- `IBluetoothRingService.release()` when the host owns ring state callbacks

## 排错顺序

1. 看 `GlassSdk.isReady()` 和当前 bind/register 日志。
2. 确认 `registerClient(clientId, callback)` 已在 `onServiceConnected()` 后执行。
3. 确认 typed accessor 返回的 service 不为 `null`。
4. 看 `Downloads/glass3Log/<clientId>.txt`。
5. 对照当前项目封装和官方 demo 的生命周期顺序。
6. 检查是否缺少功能前置条件：公有目录文件、离线库包、蓝牙连接、相机/录音权限、网络状态。
