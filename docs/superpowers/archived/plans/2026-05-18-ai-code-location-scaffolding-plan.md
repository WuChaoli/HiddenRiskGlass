# AI 代码定位脚手架 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立模块级 README.md（=代码地图+业务真相源）+ CLAUDE.md 总索引，让 AI 在 3 次工具调用内定位到目标代码。

**Architecture:** 每个 Kotlin 包目录下一个 README.md 作为单一真相源（业务逻辑+文件索引+调用链），CLAUDE.md 精简为总索引表指针，docs/ 中单模块业务文档迁移后改为链接。

**Tech Stack:** Markdown 文档，无代码变更。

---

## 文件结构

```
app/src/main/java/com/rokid/glass/
├── hiddenrisk/README.md          # P0 — 隐患识别推理模块（最大、最核心）
├── camera/README.md              # P1 — 相机管理
├── input/README.md               # P1 — 统一输入
├── workflow/README.md            # P1 — 巡检工作流
├── component/README.md           # P2 — UI 组件
├── config/README.md              # P2 — 配置系统

docs/功能模块/
├── 隐患识别.md → 改为指向 hiddenrisk/README.md 的链接
├── 隐患录入.md → 合并到 hiddenrisk/README.md（HazardRecordActivity 部分）
├── 设备指引.md → 合并到 hiddenrisk/README.md（DeviceGuideActivity 部分）
├── 主菜单.md → 保留（跨模块页面）
├── 结束巡查.md → 保留（跨模块页面）
├── 任务关联.md → 保留（跨模块页面）
└── WiFi连接.md → 保留（跨模块页面）

CLAUDE.md → 新增模块索引表
```

---

### Task 1: hiddenrisk/README.md — 业务概述 + 文件索引

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`

**Source docs to merge:**
- `docs/功能模块/隐患识别.md` — 主体业务逻辑（InspectionLoadingActivity + AiInspectionActivity）
- `docs/功能模块/隐患录入.md` — HazardRecordActivity 部分
- `docs/功能模块/设备指引.md` — DeviceGuideActivity 部分
- `docs/公共能力/隐患识别链路.md` — 链路基线

- [ ] **Step 1: Create hiddenrisk/README.md**

写入以下内容：

```markdown
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

### 页面 Activity（6 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AiInspectionActivity.kt` | **AI 巡检主页面**，管理自动检测+结果展示+手机同步 | `onCreate()`, `startDetectionImmediately()`, `buildInputActions()` |
| `InspectionLoadingActivity.kt` | **启动加载页**，SDK 初始化+相机预热+会话创建 | `onCreate()`, `startInitializationFlow()`, `onInitializationComplete()` |
| `HazardRecordActivity.kt` | **隐患拍照页**，拍照+分析+保存 | `onCreate()`, `captureAndAnalyze()`, `submitLocalHazard()` |
| `DeviceGuideActivity.kt` | **设备指引页**，检查品判定+详情展示 | `onCreate()`, `runDetectionLoop()`, `requestGuideDetails()` |
| `HiddenRiskProbeActivity.kt` | **探针/调试页**，NCNN 推理验证（非正式功能）| |
| `LightshotActivity.kt` | 历史调试页（非产品基线）| |
| `UnifiedInputDebugActivity.kt` | 统一输入调试页 | |

### 在线推理/SSE 服务（5 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AiArSseService.kt` | **SSE 通信核心**，封装 OkHttp SSE 请求 | `identifyItemHazard()`, `requestDeepAnalysis()`, `fetchInspectionGuide()`, `RequestHandle` |
| `OnlineHazardDetectionService.kt` | **在线检测调度**，管理检测请求队列+超时 | `submitDetection()`, `requestDeepAnalysis()`, `cancelAll()` |
| `AiArEventAggregator.kt` | 聚合 SSE 事件流 | |
| `AiArHazardDetailParser.kt` | 解析远端隐患详情 → `ResolvedHazardContent` | `parse()` |
| `OnlineHazardAdviceFormatter.kt` | 格式化在线建议文案 | |

### 本地推理/后处理（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `HiddenRiskNcnn.java` | **JNI 桥接**，Java 侧调用 NCNN 推理 | `detect()`, `loadModel()`, `setNumThread()` |
| `LocalHazardResultDeduper.kt` | 本地检测结果去重 | |
| `LocalHazardItemMatcher.kt` | 本地检测结果匹配隐患类型 | |
| `DetectionResult.java` | 检测结果数据模型 | |
| `NativeInferenceStats.java` | 原生推理统计 | |

### 自动链路决策（3 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AutoHazardPipelineDecider.kt` | **链路调度核心**，决定使用远端/本地链路 | `decideStart()`, `decideAfterRemoteFailure()`, `decideAfterLocalModelLoaded()` |
| `AutoInferenceLoopDecider.kt` | 自动推理循环决策 | |
| `OnlineHazardCompetitionDecider.kt` | 在线识别竞争决策 | |

### 会话与会话管理（4 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionSession.kt` | **全局巡检会话单例** | `getInstance()`, `createNcnnInstance()`, `loadModel()`, `initFrameStream()`, `markInitialized()`, `reset()`, `release()` |
| `InspectionCameraCoordinator.kt` | 相机帧流协调（多页面抢占） | `acquire()`, `release()` |
| `InspectionFrameCaptureService.kt` | 帧捕获服务 | |
| `InspectionBackendSessionId.kt` | 后端 session 管理 | |

### 隐患上传/保存（5 个）

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `LocalHazardPushService.kt` | **本地隐患上传** | `pushLocalHazard()` |
| `LocalHazardUploadItemBuilder.kt` | 组装上传项（跳过空 hidNum，按 hidNum 去重）| `build()` |
| `InspectionBackgroundUploadQueue.kt` | 后台上传队列 | |
| `InspectionBackgroundUploadService.kt` | 后台上传服务 | |
| `InspectionFinishService.kt` | 结束巡检提交 | |
| `HazardCaptureService.kt` | 隐患截图服务 | |

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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/README.md
git commit -m "docs: 新增 hiddenrisk/README.md 模块代码地图"
```

---

### Task 2: CLAUDE.md — 新增模块索引表

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 在 CLAUDE.md "架构" 章节后、"UI 层" 之前插入模块索引表**

在 `## 架构` 与 `### UI 层` 之间插入：

```markdown
## 模块代码地图（AI 快速索引入口）

收到代码定位任务时，先查此表找到对应模块 README.md，再进入具体文件。

| 模块 | 代码 README | 覆盖范围 |
|------|-------------|----------|
| 隐患识别/推理 | `app/src/.../hiddenrisk/README.md` | 巡检页面、在线/本地推理、自动链路、隐患上传、设备指引、拍照录入 |
| 相机/帧流 | `app/src/.../camera/README.md` | 相机管理、帧捕获、预览、恢复控制 |
| 统一输入 | `app/src/.../input/README.md` | 触控、语音、头部动作映射、自动休眠 |
| 巡检工作流 | `app/src/.../workflow/README.md` | 跨页面业务上下文、企业信息、QR 解析 |
| UI 组件 | `app/src/.../component/README.md` | 状态栏、取景器、菜单、弹窗、提示 |
| 配置系统 | `app/src/.../config/README.md` | 运行时配置、推理参数、API 端点、特性开关 |

## 跨模块文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 架构总览 | `docs/公共能力/架构总览.md` | 五层架构（页面/会话/输入/相机/识别链路）|
| 总体旅程图 | `docs/总体旅程图/总体旅程图.md` | 正式巡检主链全景 |
| 页面导航分层 | `docs/公共能力/页面导航分层.md` | 正式主链与附录/调试页边界 |
| 会话与生命周期 | `docs/公共能力/会话与生命周期.md` | 会话、初始化状态边界 |
| 隐患识别链路 | `docs/公共能力/隐患识别链路.md` | 双轨推理的跨文档真相源 |
| 隐患识别验证与排障 | `docs/公共能力/隐患识别验证与排障.md` | 推理验证与排障详细文档 |
| 统一输入设计与接入 | `docs/公共能力/统一输入设计与接入.md` | 统一输入层设计与接入 |
| 头部动作调参与验证 | `docs/公共能力/头部动作调参与验证.md` | 头部动作参数与验证 |
| 日志系统 | `docs/公共能力/日志系统.md` | 日志系统说明 |
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 新增模块代码地图索引表"
```

---

### Task 3: docs/ 迁移 — 单模块文档改为链接

**Files:**
- Modify: `docs/功能模块/隐患识别.md`
- Modify: `docs/功能模块/隐患录入.md`
- Modify: `docs/功能模块/设备指引.md`

- [ ] **Step 1: 修改 docs/功能模块/隐患识别.md**

用以下内容替换文件全部内容：

```markdown
# 隐患识别

> **本文档内容已迁移至代码模块 README.md。**
>
> 业务逻辑 + 代码地图已统一收敛到：`app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
>
> 该 README.md 包含：完整的业务概述、页面状态流转、接口链路、40+ 文件索引（含关键函数）、核心调用链和依赖关系。
```

- [ ] **Step 2: 修改 docs/功能模块/隐患录入.md**

用以下内容替换文件全部内容：

```markdown
# 隐患拍照

> **本文档内容已迁移至代码模块 README.md。**
>
> 业务逻辑 + 代码地图已统一收敛到：`app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
>
> 该 README.md 包含：HazardRecordActivity 的完整业务逻辑、文件索引、调用链。
```

- [ ] **Step 3: 修改 docs/功能模块/设备指引.md**

用以下内容替换文件全部内容：

```markdown
# 设备指引

> **本文档内容已迁移至代码模块 README.md。**
>
> 业务逻辑 + 代码地图已统一收敛到：`app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
>
> 该 README.md 包含：DeviceGuideActivity 的完整业务逻辑、文件索引、调用链。
```

- [ ] **Step 4: Commit**

```bash
git add docs/功能模块/隐患识别.md docs/功能模块/隐患录入.md docs/功能模块/设备指引.md
git commit -m "docs: 单模块业务逻辑迁移至 hiddenrisk/README.md，原文件改为链接"
```

---

### Task 4: camera/README.md

**Files:**
- Create: `app/src/main/java/com/rokid/glass/camera/README.md`

```markdown
# camera/ — 相机管理与帧捕获

## 业务概述

负责 AR 眼镜相机的打开、预览、拍照和帧流管理。通过 Camera2 API 实现 GPU 帧捕获（HardwareBuffer），支持多页面共享帧流，具备相机异常恢复能力。

### 核心能力
- Camera2 API 相机生命周期管理
- GPU 帧捕获（HardwareBuffer → GpuFrame）
- 预览缩放、偏移、取景模式
- 相机异常自动恢复（RokidCameraRecoveryController）
- 共享帧流协调（InspectionCameraCoordinator，在 hiddenrisk/ 中）

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `QuickCameraManager.kt` | **相机管理器**，打开相机、预览、拍照 | `initialize()`, `attachPreviewTexture()`, `takePicture()`, `GpuFrame` |
| `RokidCameraRecoveryController.kt` | **相机恢复控制器**，检测异常并自动重连 | `start()`, `onRecoveryStarted()`, `onRecoverySucceeded()` |
| `RokidFrameSource.kt` | **帧源抽象**，提供统一帧获取接口 | |

## 核心调用链

```
QuickCameraManager.initialize()
  → CameraManager.openCamera()
  → createCaptureSession()
    → attachPreviewTexture(surfaceTexture)

帧捕获:
  → ImageReader.OnImageAvailable
    → HardwareBuffer → GpuFrame

拍照:
  → takePicture(callback)
    → createCaptureSession (still capture)
    → 保存 JPEG File
```

## 依赖关系

- **依赖：** Android Camera2 API, Rokid Glass SDK
- **被依赖：** `hiddenrisk/`（帧流消费）、`component/`（预览渲染）
```

- [ ] **Step 1: Create camera/README.md** and **Step 2: commit**

```bash
git add app/src/main/java/com/rokid/glass/camera/README.md
git commit -m "docs: 新增 camera/README.md 模块代码地图"
```

---

### Task 5: input/README.md

**Files:**
- Create: `app/src/main/java/com/rokid/glass/input/README.md`

```markdown
# input/ — 统一输入层

## 业务概述

将触控（单击/双击/返回）、语音识别、头部手势统一抽象为 `UnifiedInput`，各页面通过 `buildInputActions()` 注册动作映射表，输入层根据当前页面态动态分发。

### 配套系统
- `AutoSleepStateMachine` — 检测眼镜摘下，自动进入休眠提示
- `HeadMotionStabilityTracker` — 陀螺仪跟踪头部稳定性
- `GlassesWearMonitor` — 佩戴状态广播监听

### 当前约束
- `HEAD_GESTURE_LISTENING_ENABLED = false` — 头部动作全局关闭

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `UnifiedInput.kt` | **统一输入核心**，注册动作、分发触控/语音/头部手势 | `UnifiedInputSession.attach()`, `updateActions()`, `dispatchTouch()`, `InputActionSpec`, `InputTrigger` |
| `AutoSleepStateMachine.kt` | **自动休眠状态机**，摘镜检测+休眠提示 | `Config`, `Snapshot`, `tick()`, `onGlassesRemoved()`, `onGlassesWorn()` |
| `AutoSleepController.kt` | 自动休眠控制器，协调传感器+状态机+UI | `attach()`, `detach()`, `setEnabled()`, `markSleepHandled()` |
| `HeadMotionStabilityTracker.kt` | **头部稳定性跟踪**，陀螺仪数据→稳定性判断 | `start()`, `stop()`, `onStabilityChanged()` |
| `GlassesWearMonitor.kt` | 眼镜佩戴状态广播监听 | `attach()`, `detach()`, `onWearStateChanged()` |

## 核心调用链

```
页面注册:
  Activity.buildInputActions() → List<InputActionSpec>
    → UnifiedInputSession.updateActions(specs)
      → syncAdapters() (触控/语音适配器)

触控分发:
  BaseGlassActivity.onGlassKeyEvent(keyEvent)
    → UnifiedInputSession.dispatchTouch(key)
      → dispatchTrigger(touchTrigger)
        → 匹配当前页面态 → 执行 Action

语音分发:
  VoiceRecognition → "分析" / "取消"
    → UnifiedInputSession.dispatchTrigger(Voice(text, pinyin))
      → 匹配当前页面态 → 执行 Action

自动休眠:
  GlassesWearMonitor.onWearStateChanged(false)
    → AutoSleepStateMachine.onGlassesRemoved()
      → tick() 倒计时
        → SLEEP_WARNING → AutoSleepController 通知 UI
```

## 依赖关系

- **依赖：** Android Sensor API、Rokid Glass SDK
- **被依赖：** `hiddenrisk/`（所有页面通过 UnifiedInputSession 注册动作）
```

- [ ] **Step 1: Create input/README.md** and **Step 2: commit**

```bash
git add app/src/main/java/com/rokid/glass/input/README.md
git commit -m "docs: 新增 input/README.md 模块代码地图"
```

---

### Task 6: workflow/README.md

**Files:**
- Create: `app/src/main/java/com/rokid/glass/workflow/README.md`

```markdown
# workflow/ — 巡检工作流会话

## 业务概述

`InspectionWorkflowSession` 是跨页面的巡检业务上下文单例，保存从企业扫码开始到结束巡检的完整状态。

### 核心数据
- **企业上下文：** `EnterpriseQrPayload`（authCode, objectId, userId, apiBaseUrl）
- **巡检结果：** 检测标题/消息、分析文本、截图、保存记录
- **工作流模式：** `WorkflowMode`（WIFI / NORMAL）

### QR 码解析
支持三种格式：legacy（空格分隔）、query（URL 参数）、JSON，自动检测并解析。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionWorkflowSession.kt` | **巡检工作流会话单例** | `getInstance()`, `beginInspection()`, `updateEnterpriseFromQr()`, `recordDetection()`, `recordAnalysis()`, `recordCapture()`, `recordSavedHazardAttempt()`, `buildEndReportRecords()`, `clearForNewInspection()` |

### 关键方法

| 方法 | 用途 |
|------|------|
| `updateEnterpriseFromQr(qrContent)` | 解析 QR 码，写入企业上下文 |
| `beginInspection(sessionId)` | 开始新巡检，生成 sessionId |
| `recordDetection(title, message)` | 记录检测结果 |
| `recordAnalysis(text)` | 记录分析文本 |
| `recordCapture(jpegBytes)` | 记录截图 |
| `recordSavedHazardAttempt(key, itemCount)` | 记录上传尝试 |
| `updateSavedHazardAttemptOutcome(key, outcome, hints)` | 更新上传结果 (SUCCESS/FAILED) |
| `buildEndReportRecords()` | 构建结束巡检报告 |
| `clearForNewInspection()` | 清理累计数据，保留企业上下文 |

## 依赖关系

- **依赖：** 无（纯内存状态管理）
- **被依赖：** `hiddenrisk/`（所有页面读写工作流状态）
```

- [ ] **Step 1: Create workflow/README.md** and **Step 2: commit**

```bash
git add app/src/main/java/com/rokid/glass/workflow/README.md
git commit -m "docs: 新增 workflow/README.md 模块代码地图"
```

---

### Task 7: component/README.md

**Files:**
- Create: `app/src/main/java/com/rokid/glass/component/README.md`

```markdown
# component/ — 可复用 UI 组件

## 业务概述

提供眼镜端通用 UI 组件，包括状态栏（时间/电量）、取景预览、功能菜单、操作指引、状态弹窗。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `GlassStatusBar.kt` | **顶部状态栏**，显示时间+电量 | `updateTime()`, `updateBattery()`, `setBatteryPercent()` |
| `RokidCameraPreviewView.kt` | **相机预览视图**，渲染帧流+健康监控 | `startPreview()`, `stopPreview()`, `PreviewHealthListener` |
| `FunctionMenuView.kt` | **右上功能菜单**，显示菜单标题+内容 | `setMenu(title, content)` |
| `BottomPromptView.kt` | **底部提示栏**，显示操作提示文案 | `setPrompt(title, subtitle)` |
| `OperationGuideView.kt` | **操作指引卡片**，显示引导标题+内容 | `setGuide(title, content)` |
| `StatusAlertOverlayView.kt` | **状态弹窗叠层**，倒计时+动画+自动消失 | `render(model)`, `reset()`, `AlertBehavior` |
| `StatusAlertStateMachine.kt` | 弹窗状态机，控制显示/隐藏决策 | `render()`, `RenderDecision.Show/Hide` |
| `StatusAlertModels.kt` | 弹窗数据模型 | `StatusAlertModel`, `AlertBehavior`, `AlertStyle` |

## 依赖关系

- **依赖：** Android View 体系
- **被依赖：** `hiddenrisk/`（各页面使用这些组件构建 UI）
```

- [ ] **Step 1: Create component/README.md** and **Step 2: commit**

```bash
git add app/src/main/java/com/rokid/glass/component/README.md
git commit -m "docs: 新增 component/README.md 模块代码地图"
```

---

### Task 8: config/README.md

**Files:**
- Create: `app/src/main/java/com/rokid/glass/config/README.md`

```markdown
# config/ — 运行时配置系统

## 业务概述

从 `app/src/main/assets/inspection_config.base.jsonc` 加载巡检运行时配置，支持不同风味（flavor）覆盖。控制推理参数、API 端点、特性开关。

### 配置加载流程

```
InspectionConfigRepository.init(context, flavor)
  → loadFromAssets("inspection_config.base.jsonc")
  → overlayAssetName(flavor) → 加载风味覆盖
  → merge(base, overlay) → InspectionAppConfig
  → currentConfig = 合并结果
```

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionAppConfig.kt` | **配置数据类**，定义全部配置项结构 | `AiInspectionConfig`, `NetworkConfig`, `FeatureFlagsConfig`, `EnterpriseScanConfig`, `AutoInferenceMode`, `InferenceBackend`, `GpuProfile` |
| `InspectionConfigRepository.kt` | **配置加载器**，从 assets 读取并合并配置 | `init()`, `get()`, `reloadForTest()`, `buildConfig()`, `merge()` |

### 关键配置项（AiInspectionConfig）

| 字段 | 用途 |
|------|------|
| `targetInputSize` | NCNN 输入尺寸（当前 640）|
| `backend` | 推理后端（System Vulkan）|
| `gpuProfile` | GPU 配置（Balanced FP16）|
| `autoInferenceMode` | 自动推理模式 |
| `remoteFailureFallbackThreshold` | 远端失败 fallback 阈值 |
| `enableOnlineSceneHazardDetection` | 场景识别开关 |
| `enableOnlineAdvicePage` | 在线建议页开关 |
| `onlineDetectIntervalMs` | 在线检测间隔（默认 500ms）|
| `onlineDetectConcurrencyLimit` | 在线检测并发上限（默认 5）|

### 关键配置项（NetworkConfig）

| 字段 | 用途 |
|------|------|
| `aiAutoApi` | `/ai/auto` 端点 |
| `aiArApi` | `/ai/deep` + `/ai/device` 端点 |
| `saveResultApi` | 隐患保存端点 |

## 依赖关系

- **依赖：** Android AssetManager, Gson
- **被依赖：** `hiddenrisk/`（所有推理/接口参数从配置读取）
```

- [ ] **Step 1: Create config/README.md** and **Step 2: commit**

```bash
git add app/src/main/java/com/rokid/glass/config/README.md
git commit -m "docs: 新增 config/README.md 模块代码地图"
```

---

### Task 9: LSP 配置（可选）

**Files:**
- Modify: `.claude/settings.local.json`

- [ ] **Step 1: 调研 kotlin-language-server 安装方式**

```bash
# 检查是否已安装
which kotlin-language-server
# 如未安装，通过 npm 安装
npm install -g kotlin-language-server
```

- [ ] **Step 2: 在 settings.local.json 中添加 LSP 配置**

```json
{
  "permissions": { ... },
  "lsp": {
    "kotlin": {
      "command": "kotlin-language-server",
      "args": []
    }
  }
}
```

- [ ] **Step 3: 验证 LSP 可用**

重启 Claude Code 后，使用 LSP 工具验证 `goToDefinition` 是否可用。

- [ ] **Step 4: Commit**

```bash
git add .claude/settings.local.json
git commit -m "feat: 添加 Kotlin LSP 配置，启用代码引用搜索"
```

---

## Spec 覆盖检查

| 需求 | 任务 |
|------|------|
| 模块 README.md 包含业务概述 | Task 1, 4, 5, 6, 7, 8 |
| 模块 README.md 包含文件索引（含关键函数）| Task 1, 4, 5, 6, 7, 8 |
| 模块 README.md 包含调用链 | Task 1, 4, 5 |
| 模块 README.md 包含依赖关系 | 全部 Task |
| CLAUDE.md 精简为总索引 | Task 2 |
| docs/ 单模块文档迁移为链接 | Task 3 |
| 覆盖所有主要模块 | Tasks 1, 4, 5, 6, 7, 8 |
| LSP 配置 | Task 9 |
| AI 定位 ≤ 3 次工具调用 | 全量完成后的验收指标 |
