# Rokid SDK Changelog Extract: `v2.1.2 (2025-12-15)`

Source:
- Official Rokid changelog: `https://x-docs.rokid.com/docs/版本变更日志.html#眼镜端接口变更-3`
- The linked anchor `#眼镜端接口变更-3` lands in the `v2.1.2 (2025-12-15)` section.

## Version Baseline

- Phone SDK: `com.rokid.security:phone.sdk:2.1.2-E`
- Glass SDK: `com.rokid.security:glass3.open.sdk:2.1.2-E`
- Recommended OTA: `1.10.e001-20251215-150202` and above
- Minimum SDK requirement: `2.1.0-E`

## Glass-Side API Additions

### `GlassSdk` new service getters

- `getGlassCollectService(): ICollectService?`
- `getGlassOfflineTtsService(): IOfflineTtsService?`

Interpretation:
- `v2.1.2` introduces direct service accessors for image collection and offline TTS.
- Upgrade tasks should check whether feature code should switch from indirect access patterns to these getters.

### New `ICollectService`

Methods documented in the changelog:
- `startCollect(mode: Int)`
- `stopCollect()`
- `setCollectListener(listener: IGlassCollectListener)`
- `removeCollectListener(listener: IGlassCollectListener)`
- `sendFacePicture(identifier: String, param: CollectParam, callback: ICollectCallback)`
- `sendLPRPicture(identifier: String, platNo: String, callback: ICollectCallback)`
- `changeMode(mode: Int)`
- `getFaceSmallBitmap(frameId: Long, trackId: Long): Bitmap?`
- `getLprSmallBitmap(plateNo: String): Bitmap?`

Interpretation:
- This version formalizes a glass-side collection pipeline for face and plate assets.
- Any task around “采集”, “人脸小图”, “车牌小图”, or sending recognition images should inspect this service first.

### New `IOfflineTtsService`

- `playTtsMsg(ttsMsg: String)`

Interpretation:
- For offline voice broadcast requirements, prefer this service over ad hoc media playback when the SDK version supports it.

### `IMediaServer` listener management

- `setMediaStateLister(listener: IMediaStateLister)`
- `removeMediaStateLister(listener: IMediaStateLister)`

Interpretation:
- Media listeners are now explicitly add/remove managed.
- Audit activity lifecycle and cleanup when adding media-state observers.

### `IOfflineRecServer` new reporting method

- `submitRecognizedFaceInfo(info: RecognizeFaceSubmitInfo, face: Bitmap)`

Interpretation:
- Face recognition results can be submitted with the corresponding bitmap payload.
- Recognition workflows should check whether this reporting path is required instead of custom message transport.

## Phone-Side Additions In The Same Version

These are secondary for this repo unless the task spans a companion app.

### `IWifiP2PClientOperate`

- `getGroupInfo(action: (group: WifiP2pGroup?) -> Unit)`
- `getIP2PConnectControl(): IP2PConnectControl`

### `IAIChat`

- `removeAiChatListener()`
- `getRunTimeChatList(): MutableList`

## Repo Mapping

Current repo observations:

- `app/build.gradle` pins `com.rokid.security:glass3.open.sdk:2.1.5-E`.
- `app/src/main/java/com/rokid/glass/utils/GlassSdkUtils.kt` is the main glass service bootstrap and already handles `ICommonInfoListener.onConfig(...)`, which comes from a later changelog slice.
- `app/src/main/java/com/rokid/glass/TestMediaActivity.kt` already calls `setMediaStateLister(...)`, so media listener lifecycle is a live integration concern here.
- `app/src/main/java/com/rokid/glass/GlassLprTrackActivity.kt` uses `PreviewResolution.ResolutionInfo_1080P_Land`, also from a later changelog slice.

## Audit Checklist

When asked to upgrade or reconcile Rokid SDK code in this repo:

1. Confirm whether the task is pegged to `v2.1.2` or to the latest docs.
2. Confirm the current dependency in `app/build.gradle`.
3. Search for local use of:
   - `GlassSdk.getGlass...Service`
   - `IMediaStateLister`
   - `ICommonInfoListener`
   - media / recognition / collection service imports under `com.rokid.security`
4. If new listeners or AIDL methods are introduced, update every local `Stub()` implementation.
5. Build after edits; for runtime-sensitive features, verify on-device behavior against the matching OTA baseline.
