# hiddenrisk/ — 隐患识别推理核心模块

## 业务概述

### 核心职责
本模块是 AI 巡检的完整闭环：从 SDK 初始化、相机帧流准备、在线/本地双链路推理、隐患结果展示、手机端同步到返回检测态。

**双轨推理架构：**
- **本地 NCNN 推理**（YOLOv8）— 始终可用，作为 fallback
- **在线 SSE 推理** — 通过 OkHttp SSE 连接远端 `/ai/auto`、`/ai/deep`、`/ai/device`、`/ai/general` 端点

`AutoHazardPipelineDecider` 负责调度：网络可用时优先远端，远端连续失败达阈值后切换本地 fallback。

### 页面状态流转

```
主菜单点击"实时分析"
  → InspectionSession 已初始化? → AiInspectionActivity (DETECTING)
  → 未初始化 → InspectionLoadingActivity (IDLE → SDK_INIT → CAMERA_INIT → COMPLETE)
       → 完成后 → AiInspectionActivity (DETECTING)

AiInspectionActivity:
  DETECTING → 自动/手动命中隐患 → STREAM_RESPONSE(DESCRIPTION)
    → 确认 → 手机端同步 → STREAM_RESPONSE(ADVICE)
    → 确认/取消 → DETECTING
  DETECTING → 返回/取消 → AiInspectionMenuActivity
  DETECTING → 设备指引 → DeviceGuideActivity
  DETECTING → 隐患拍照 → HazardRecordActivity
  DETECTING → 结束任务 → InspectionEndReportActivity

HazardRecordActivity:
  IDLE → 拍照 → COUNTDOWN → ANALYSIS → 保存 → IDLE

DeviceGuideActivity:
  DETECTING → 判定命中 → RESULT/PROMPT → RESULT/DETAIL
```

### 后端接口

| 接口 | 地址 | 用途 |
|------|------|------|
| `/ai/auto` | `http://183.147.142.133:10010/ai/auto` | 物品识别判定（是否"存在隐患"）|
| `/ai/deep` | SSE | 深度分析（流式返回描述文本）|
| `/ai/device` | SSE | 设备指引详情 / 在线建议 |
| `/ai/general` | | 环境隐患识别 |
| `pushHidDanger` | `<QR baseUrl>/pushHidDanger` | 手机端同步/隐患保存 |

### 会话

- `InspectionSession` — 全局单例，持有 NCNN 模型引用、帧流状态、初始化标记
- `InspectionWorkflowSession` — 跨页面业务上下文（企业信息、检测结果、截图、上传记录）

---

## 文件索引

### 页面 Activity（7 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AiInspectionActivity.kt` | **AI 巡检主页面**，管理自动检测+结果展示+手机同步 | `onCreate()`, `startDetectionImmediately()`, `buildInputActions()` |
| `InspectionLoadingActivity.kt` | **启动加载页**，SDK 初始化+相机预热+会话创建 | `onCreate()`, `startInitializationFlow()`, `onInitializationComplete()` |
| `HazardRecordActivity.kt` | **隐患拍照页**，拍照+分析+保存 | `onCreate()`, `captureAndAnalyze()`, `submitLocalHazard()` |
| `DeviceGuideActivity.kt` | **设备指引页**，检查品判定+详情展示 | `onCreate()`, `runDetectionLoop()`, `requestGuideDetails()` |
| `HiddenRiskProbeActivity.kt` | **探针/调试页**，NCNN 推理验证（非正式功能）| |
| `LightshotActivity.kt` | 历史调试页（非产品基线）| |
| `UnifiedInputDebugActivity.kt` | 统一输入调试页 | |
| `RawCameraPreviewDebugActivity.kt` | 原始相机预览调试页 | |

### 在线推理/SSE 服务（6 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AiArSseService.kt` | **SSE 通信核心**，封装 OkHttp SSE 请求 | `identifyItemHazard()`, `requestDeepAnalysis()`, `fetchInspectionGuide()`, `RequestHandle` |
| `OnlineHazardDetectionService.kt` | **在线检测调度**，管理检测请求队列+超时 | `submitDetection()`, `requestDeepAnalysis()`, `cancelAll()` |
| `AiArEventAggregator.kt` | 聚合 SSE 事件流 | |
| `AiArHazardDetailParser.kt` | 解析远端隐患详情 → `ResolvedHazardContent` | `parse()` |
| `OnlineHazardAdviceFormatter.kt` | 格式化在线建议文案 | |
| `DualEndpointSubmitCoordinator.kt` | 双端点提交协调器，聚合主备双端点提交结果 | `record()` |

### 本地推理/后处理（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `HiddenRiskNcnn.java` | **JNI 桥接**，Java 侧调用 NCNN 推理 | `detect()`, `loadModel()`, `setNumThread()` |
| `LocalHazardResultDeduper.kt` | 本地检测结果去重 | |
| `LocalHazardItemMatcher.kt` | 本地检测结果匹配隐患类型 | |
| `DetectionResult.java` | 检测结果数据模型 | |
| `NativeInferenceStats.java` | 原生推理统计 | |

### 自动链路决策（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AutoHazardPipelineDecider.kt` | **链路调度核心**，决定使用远端/本地链路 | `decideStart()`, `decideAfterRemoteFailure()`, `decideAfterLocalModelLoaded()` |
| `AutoInferenceLoopDecider.kt` | 自动推理循环决策 | |
| `OnlineHazardCompetitionDecider.kt` | 在线识别竞争决策 | |
| `SharedInferenceFrameDecider.kt` | 共享推理帧决策器，判断在线链路是否复用本地推理缓存 | `decide()` |

### 会话与会话管理（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionSession.kt` | **全局巡检会话单例** | `getInstance()`, `createNcnnInstance()`, `loadModel()`, `initFrameStream()`, `markInitialized()`, `reset()`, `release()` |
| `InspectionCameraCoordinator.kt` | 相机帧流协调（多页面抢占） | `acquire()`, `release()` |
| `InspectionFrameCaptureService.kt` | 帧捕获服务 | |
| `InspectionBackendSessionId.kt` | 后端 session 管理 | |

### 隐患上传/保存（6 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `LocalHazardPushService.kt` | **本地隐患上传** | `pushLocalHazard()` |
| `LocalHazardUploadItemBuilder.kt` | 组装上传项（跳过空 hidNum，按 hidNum 去重）| `build()` |
| `InspectionBackgroundUploadQueue.kt` | 后台上传队列 | |
| `InspectionBackgroundUploadService.kt` | 后台上传服务 | |
| `InspectionFinishService.kt` | 结束巡检提交 | |
| `HazardCaptureService.kt` | 隐患截图服务 | |
| `HazardRecordUploadService.kt` | 隐患录入上传服务，当前使用 mock 结果打通 UI | `uploadHazardRecord()` |

### 深度分析/验证（3 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `HazardDeepAnalysisService.kt` | 深度分析服务 | |
| `MayHazardDeepVerifyProtocol.kt` | 疑似隐患深度验证协议 | |
| `MayHazardDeepVerifyService.kt` | 疑似隐患深度验证服务 | |
| `SuggestionChecksProtocol.kt` | 整改建议检查协议 | |

### UI/辅助组件（9 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AutoHazardPresentationCoordinator.kt` | 自动隐患展示协调 | |
| `HiddenRiskMultiOverlayRenderer.kt` | 多框渲染 | |
| `HazardRecordFrameOverlay.kt` | 拍照帧叠加层 | |
| `HazardStreamService.kt` | 隐患流式服务 | |
| `InferencePressureMonitor.kt` | 推理压力监控 | |
| `SimulatedStreamTextChunker.kt` | 模拟流式文本分块 | |
| `SquareViewfinderOverlay.kt` | 取景器叠加层 | |
| `ResolvedHazardContent.kt` | 解析后隐患内容数据类 | |
| `InspectionRetryExecutor.kt` | 重试执行器（最多 4 次，1s/2s/3s 递增延迟）| |

### 基础/系统（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `BaseGlassActivity.kt` | Activity 基类 | |
| `RokidSdkManager.kt` | Rokid SDK 管理器 | `ensureInitialized()` |
| `HeadGestureManager.kt` | 头部手势管理 | |
| `MotionStabilityTracker.kt` | 头部稳定性跟踪 | |
| `GlassKeyEvent.kt` | 眼镜按键事件 | |

---

## 核心调用链

### 链路 1：初始化 → 检测
```
AiInspectionMenuActivity (点击"实时分析")
  → InspectionSession.isInitialized?
    → YES: 直接进入 AiInspectionActivity
    → NO: InspectionLoadingActivity
        → RokidSdkManager.ensureInitialized()
        → InspectionSession.initFrameStream()
        → InspectionSession.markInitialized()
        → 创建 sessionId (InspectionWorkflowSession.beginInspection())
        → 导航到 AiInspectionActivity
```

### 链路 2：在线自动识别
```
AiInspectionActivity (DETECTING)
  → OnlineHazardDetectionService.submitDetection()
    → AiArSseService.identifyItemHazard() → POST /ai/auto
    → 返回 hasHazard=true?
      → YES: 停止在线检测
      → AiArSseService.requestDeepAnalysis() → SSE /ai/deep
        → AiArHazardDetailParser.parse() → ResolvedHazardContent
        → 进入 STREAM_RESPONSE / DESCRIPTION
```

### 链路 3：本地 fallback
```
AutoHazardPipelineDecider.decideAfterRemoteFailure()
  → 远端连续失败达阈值
  → InspectionSession.loadModel()
    → HiddenRiskNcnn.loadModel() → JNI ncnn
  → 切换为本地 NCNN 推理
```

### 链路 4：手机端同步
```
AiInspectionActivity (DESCRIPTION, 确认)
  → submitLocalHazardAndShowAdvice()
    → LocalHazardUploadItemBuilder.build(ResolvedHazardContent)
    → LocalHazardPushService.pushLocalHazard()
      → POST <QR baseUrl>/pushHidDanger
      → InspectionRetryExecutor (最多 4 次重试)
    → 成功: InspectionWorkflowSession.updateSavedHazardAttemptOutcome(SUCCESS)
      → 进入 STREAM_RESPONSE / ADVICE
    → 失败: 留在 DESCRIPTION，显示错误
```

### 链路 5：隐患拍照
```
HazardRecordActivity (IDLE)
  → 单击/语音"拍照"
  → COUNTDOWN → 截帧
  → ANALYSIS → AiArSseService.requestDeepAnalysis() → SSE /ai/deep
    → AiArHazardDetailParser.parse()
  → 确认 → LocalHazardPushService.pushLocalHazard()
  → 成功 → 返回 IDLE
```

### 链路 6：设备指引
```
DeviceGuideActivity (DETECTING)
  → runDetectionLoop() → AiArSseService.identifyItemHazard() → /ai/auto
  → 命中 → RESULT/PROMPT (约2秒后自动)
  → AiArSseService.requestDeepAnalysis() → /ai/deep
  → RESULT/DETAIL
```

---

## 依赖关系

- **依赖（我依赖谁）：**
  - `camera/` — 帧流获取（QuickCameraManager, RokidFrameSource）
  - `input/` — 统一输入（触控、语音、头部动作映射）
  - `workflow/` — 巡检业务上下文（InspectionWorkflowSession）
  - `config/` — 运行时配置（推理参数、API 端点）
  - `component/` — UI 组件（状态栏、取景器、菜单、弹窗）
  - `utils/` — 工具类与扩展函数

- **被依赖（谁依赖我）：**
  - 无 — 本模块是业务顶层，其他模块不依赖此包
