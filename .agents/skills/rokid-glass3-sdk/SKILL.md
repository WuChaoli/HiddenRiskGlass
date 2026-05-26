---
name: rokid-glass3-sdk
description: Single Rokid skill for this repo. Use when Codex needs to inspect, integrate, upgrade, or troubleshoot `com.rokid.security:glass3.open.sdk`, compare SDK and OTA compatibility, debug shared camera preview, or wire pages into the repo's unified Rokid input flow.
---

# Rokid Glass3 SDK

这是当前仓库唯一的 Rokid 主技能。
处理 Rokid SDK 接入、版本审计、OTA 兼容、共享相机预览、统一输入接入与设备侧排障时，统一从这里进入，不再切到其他 Rokid 子技能。

## 仓库基线

- 当前 Gradle 依赖基线：`app/build.gradle` 中为 `com.rokid.security:glass3.open.sdk:2.1.9-E`
- 当前推荐 OTA 基线：`1.17.e002-20260509-150201` 及以上
- 共享预览主链路：
  - `app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt`
  - `app/src/main/java/com/rokid/glass/component/RokidCameraPreviewView.kt`
  - `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`
- 统一输入主链路：
  - `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`
  - 业务页示例：`HomeActivity`、`AiInspectionActivity`、`DeviceGuideActivity`、`HazardRecordActivity`

## 先做什么

不要直接改 SDK 或页面代码。先按这个顺序做：

1. 读代码，确认当前仓库真实接法。
2. 读官方 changelog，确认目标 SDK / OTA 是否真的要求改动。
3. 用 changelog 条目映射本仓库 call site，再决定改依赖、改代码，还是只补排障信息。

按需加载 references：

- 改 Gradle / 接入顺序前，先读 [references/setup.md](references/setup.md)
- 看 SDK service / `CameraShareHelper` 能力面前，先读 [references/api.md](references/api.md)
- 做版本、OTA、共享预览兼容判断前，先读 [references/version-compatibility.md](references/version-compatibility.md)

## 主流程

### 1. 版本与 changelog 审计

先确认三件事，不要只看其中一项：

- 仓库当前依赖版本
- 官方 changelog 对应的目标 SDK 版本
- 该版本要求或推荐的 OTA 基线

然后再把变化映射到本仓库真实 call site。优先检查：

- `GlassSdk.bindSecurityService(...)`
- `GlassSdk.registerClient(...)`
- `GlassSdk.getGlass...Service()`
- `CameraShareHelper`
- `UnifiedInputSession`
- 任意 `Stub()` listener / callback 实现

如果 changelog 只是新增 getter 或回调，而仓库并未用到对应能力，不要为了“对齐最新”而扩大改动。

### 2. SDK 接入 / 升级

处理 SDK 接入或升级时：

- 先核对 Maven、依赖、`pickFirst 'libr2aud.so'` 等构建接线
- 再核对 `GlassSdk.isReady()`、`bindSecurityService()`、`registerClient()` 的生命周期顺序
- 最后才改具体 service 调用

默认优先复用已有接线，不要在仓库里再造第二套 Rokid 初始化流程。

### 3. 共享相机预览

本仓库的共享预览不是单一路径，而是同一个 `CameraShareHelper` 同时承担两条链路：

- NV21 帧流：
  - `RokidFrameSource.startFrameStream()` -> `initNv21ExportWithConfig(...)`
  - 关键回调：`onNv21Frame(...)`、`onNv21ExportResolutionChanged(...)`、`onNv21ExportRuntimeParamsChanged(...)`
- Surface 预览：
  - `RokidCameraPreviewView.startPreview()` -> `initSurfaceWithConfig(...)`
  - 关键回调：`onFrameAvailable()`、`onSurfaceShareConfigChanged(...)`、`onZoomLevelChanged(...)`

预览裁剪与方向判断依赖这些数据：

- `transformMatrix`
- `onSurfaceShareConfigChanged(...)` 返回的 `width/height/appliedPreviewFps/videoStabilizationEnabled`
- `onNv21ExportResolutionChanged(...)` 返回的 `width/height/appliedPreviewFps`

排查旧 OTA / 新 OTA 差异时，重点不是“有没有打开相机”，而是这些回调和矩阵语义是否与当前代码假设一致：

- 如果 `shared surface first frame available` 已出现，但没有 `first preview draw`，优先怀疑 GL / Surface 绑定层
- 如果 `preview crop updated ... swapped=... matrix=...` 明显不合理，优先怀疑系统版本差异导致的方向 / 裁剪语义变化
- 如果 NV21 与 Surface 的 `width/height`、`appliedPreviewFps` 长期不更新，优先怀疑 SDK / OTA 兼容矩阵
- `2.1.9-E` 正式恢复优先通过 `restartNv21ExportWithConfig(...)` 复用 NV21 helper/session，并记录 `isNv21Active()` / `isSurfaceActive()` 与支持分辨率；Surface restart 暂不接入业务页面生命周期

### 4. 共享预览恢复边界

正常黑屏恢复、息屏唤醒恢复、页面重入恢复，默认边界如下：

- 不要轻易 `releaseAppCamera`
- 不要把问题默认升级成 `InspectionCameraCoordinator.restart(...)`
- 不要把 `restartFrameStream...` 当成预览恢复首选

默认先走软恢复：

- `InspectionCameraCoordinator.pause(...)`
- `InspectionCameraCoordinator.acquire(...)`
- `InspectionCameraCoordinator.updatePreview(...)`
- 必要时仅重建 `RokidCameraPreviewView`

只有在日志证明底层帧流或相机已死，而不是仅仅预览 View / Surface 没画出来时，才讨论 release / restart。

### 5. 统一输入接入

页面层默认入口是 `UnifiedInputSession`，不是页面自己散落注册：

- `VoiceAction`
- `GlassSdk.getGlassOfflineCmdService().add/remove(...)`
- `HeadGestureManager.addListener/removeListener(...)`

页面层做法固定为：

- 生命周期里 `attach()` / `detach()` / `release()`
- 状态切换时 `updateActions()`
- `onGlassKeyEvent()` 只做 `dispatchTouch(...)`

确认 / 取消语义默认保持：

- `NOD` = 主确认
- `SHAKE` = 次动作或取消

补充说明：

- 头部动作能力保留在统一输入模型里，但当前 `UnifiedInputSession` 代码默认 `HEAD_GESTURE_LISTENING_ENABLED = false`
- 当前语音词表由 `UnifiedInputSession` 的前台 owner 按 `getLanguage()` 返回语言键通过 `setOfflineCmdWords(...)` 原子覆盖；不可用时回退旧 `add/remove` 路径
- `LeqiInterceptor` 本次明确不接入
- 因此页面接入时应先沿用统一输入骨架和动作语义，再按任务需要判断是否真的要打开头部动作监听

### 6. 设备侧排障顺序

排查设备问题时，先看矩阵，再看日志：

1. 当前 app 依赖的 SDK 版本
2. 目标 / 实机 OTA 版本是否满足 changelog 推荐基线
3. 当前页面是否走了共享预览主链路还是其他相机链路
4. 再抓日志，不要先改代码

优先关注：

- `RokidCameraPreview`
- `RokidFrameSource`
- `InspectionCameraCoordinator`

关键字：

- `shared surface first frame available`
- `shared surface texture id still 0 after frame update`
- `first preview draw`
- `surface share config changed`
- `nv21 export resolution changed`
- `preview crop updated`
- `pause owner=`
- `acquire owner=`
- `updatePreview owner=`
- `preview_ready owner=`
- `auto_sleep_warning`
- `resumeFromAutoSleep`

## 常见任务路由

### 调 SDK 版本 / 对 changelog

先读 [references/version-compatibility.md](references/version-compatibility.md)，确认当前基线和历史切片，再去代码里查 call site。

### 接新 service 或补 listener

先读 [references/api.md](references/api.md)，再定位本仓库已有 `GlassSdk.getGlass...Service()` 调用，避免凭印象补接口。

### 排查黑屏 / 裁剪错误 / 方向不对

先看共享预览日志，区分：

- 帧流没起来
- Surface 收到帧但没画出来
- 画出来了，但 `transformMatrix` / crop 语义变了

### 接业务页输入

先看 `UnifiedInputSession` 和现有页面示例，默认走统一输入，不要直接在页面里堆 Rokid 语音或头部动作注册。

## 约束

- 先看代码与 changelog，再决定是否改 SDK / 代码
- 优先最小修改，不把一次 Rokid 任务扩成整仓库改造
- 不要同时维护多套 Rokid 基线说法
- 不再引用已删除的旧 Rokid 技能作为前置入口
