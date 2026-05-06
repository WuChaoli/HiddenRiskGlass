## Context

`glassdemo` 是一个运行在 Rokid Glass 设备上的 Android 应用，当前正式主链围绕“巡检加载 -> 网络/企业上下文准备 -> 巡检菜单 -> AI 识别/隐患录入 -> 结束巡检”展开。

代码层面已经存在若干稳定事实：

- `InspectionLoadingActivity` 是当前 launcher 入口。
- `InspectionWorkflowSession` 负责跨页面携带 Wi-Fi / 企业 / 巡检结果上下文。
- `InspectionSession` 负责巡检页初始化状态、模型实例和帧流缓存。
- `UnifiedInputSession` 已经成为正式页面的统一输入注册层。
- 现有 `docs/` 中已经有模型验证、头部动作调参、统一输入接入等专题经验文档。

本次设计要做的是把这些事实整理成规格文档体系，而不是重新设计信息架构或实现新功能。

## Goals / Non-Goals

**Goals:**

- 从当前代码提炼稳定、可复用的产品规格基线。
- 明确正式主链页面跳转、入口分流和返回路径。
- 明确每个核心功能的触控、语音、陀螺仪控制逻辑。
- 为后续功能改动、代码清理和真实设备验证提供统一文档入口。

**Non-Goals:**

- 不重构 Activity、Session、输入层或相机链路。
- 不补写当前未实现的设备指引业务闭环。
- 不把调试页、探针页和历史 demo 提升为正式能力。
- 不覆盖模型调参、日志排障等专题细节，这些继续留在现有 `docs/`。

## Decisions

1. 正式功能按 capability 分拆，每个 capability 聚焦单一产品能力，而不是按所有 Activity 平铺。
2. UI 总体页面跳转单列一个 `ui-navigation-overview` 规格，避免每个功能 spec 重复描述全链路。
3. 架构说明放在本 change 的 `design.md`，只说明模块边界和协作方式，不嵌入每个 spec。
4. 调试页、探针页、历史入口页只进入索引或附录，不与正式能力等权展开。
5. “设备指引”按当前真实行为记录为占位能力，不补未来方案。
6. 头部动作当前按正式页面未启用处理，因为 `UnifiedInputSession` 中 `HEAD_GESTURE_LISTENING_ENABLED = false`。

## Risks / Trade-offs

- 以当前代码为准的文档会忠实反映现状，因此会包含一些“暂未完成”“占位中”的功能边界；好处是避免误把愿景当成现状。
- 正式主链和附录页面分开写能提升可读性，但需要在索引文档中维护好引用关系。
- `AiInspectionActivity` 逻辑较重，本次只提炼产品行为，不细拆内部推理实现细节，避免文档变成代码逐行抄录。

## Architecture

### 页面层

- `InspectionLoadingActivity`
  - 启动入口。
  - 负责权限检查、SDK 初始化、相机预热、Wi-Fi/企业流程分流。
- `WifiQrScanActivity`
  - 离线场景下的 Wi-Fi 扫码配网页。
  - 成功后通过 `EXTRA_NEXT_AFTER_SUCCESS` 跳到企业扫码页。
- `EnterpriseQrScanActivity`
  - 企业二维码扫描页。
  - 负责企业二维码解析与对象信息拉取。
- `EnterpriseInfoActivity`
  - 展示企业信息与历史隐患摘要。
  - 确认后进入巡检菜单。
- `AiInspectionMenuActivity`
  - 主菜单页。
  - 当前提供隐患分析、设备指引、隐患录入三项入口。
- `AiInspectionActivity`
  - AI 隐患识别核心页。
  - 包含 `DETECTING` 与 `STREAM_RESPONSE` 两个主要状态。
- `HazardRecordActivity`
  - 独立隐患录入页。
  - 负责拍照、实时分析、保存与结束任务入口。
- `InspectionEndReportActivity`
  - 巡检结束页。
  - 展示已保存隐患缩略图与结束确认。

### 会话与状态层

- `InspectionSession`
  - 保存巡检初始化状态。
  - 复用 HiddenRisk 模型实例与巡检帧流状态。
  - 控制是否允许直接进入 `AiInspectionActivity`。
- `InspectionWorkflowSession`
  - 维护 Wi-Fi 在线/离线模式、企业二维码上下文、企业详情、最新检测结果、结束页汇总数据。
  - 是正式主链跨页面数据的真相源。

### 输入层

- `UnifiedInputSession`
  - 页面统一声明动作及触发源，不直接分散管理语音、触控和头部动作监听。
- `VoiceInputAdapter`
  - 统一注册/注销离线语音动作。
- `HeadGestureInputAdapter`
  - 统一封装头部动作监听接入。
- 当前约束
  - `HEAD_GESTURE_LISTENING_ENABLED = false`
  - 正式页面以触控和语音为主
  - 文档中仍需记录头部动作触发器设计入口和禁用事实

### 相机 / 图像链路

- `InspectionCameraCoordinator`
  - 统一管理不同页面对相机帧流与预览的占用。
- `RokidFrameSource`
  - 提供最新扫描帧和裁剪帧。
- `InspectionFrameCaptureService`
  - 从共享帧流中选取适合分析/上传的图片负载。

### 隐患识别链路

- 本地识别
  - `HiddenRiskNcnn`
  - 按需加载的本地 NCNN 模型用于本地识别或本地 fallback。
- 在线识别
  - `AiArSseService`
  - `OnlineHazardDetectionService`
  - 在线 `/ai/ar` 详情链路与流式文本返回。
- 保存与结束上报
  - `LocalHazardPushService`
  - `InspectionBackgroundUploadQueue`
  - `InspectionBackgroundUploadService`

### 边界说明

- `UnifiedInputDebugActivity` 用于统一输入验证，不是正式业务功能。
- `HiddenRiskProbeActivity` 是探针页，不是正式识别结果页面。
- `LightshotActivity`、`HomeActivity`、`InspectionModeActivity` 具有参考和入口价值，但不作为当前正式巡检主链真相源。
- `device-guide` 当前只有入口和“功能开发中，后续接入”提示，没有完整闭环。
