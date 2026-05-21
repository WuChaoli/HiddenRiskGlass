# Rokid Glass3 SDK API 速查

## 用途

这个文件是根据项目里的 Rokid Glass3 SDK 笔记整理出来的精简接口速查。实现或排查具体 service 调用时再读，不要默认整篇加载。

## 初始化流程

按这个顺序初始化 SDK：

1. Skip repeated work when `GlassSdk.isReady()` is already true.
2. Call `GlassSdk.bindSecurityService(context, callback)`.
3. In `onServiceConnected()`, call `GlassSdk.registerClient(clientId, clientCallback)`.
4. Use typed `GlassSdk.get...Service()` accessors after binding and registration complete.

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

如果这里只知道 accessor 名称，但没有详细接口说明，就先看本地 SDK stub 或 demo 源码，再决定怎么写。

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
