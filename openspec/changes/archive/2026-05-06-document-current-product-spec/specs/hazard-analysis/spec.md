## ADDED Requirements

### Requirement: Initialize and route inspection analysis flow
`InspectionLoadingActivity` 与 `AiInspectionActivity` MUST 共同组成 AI 隐患识别正式功能，负责从初始化分流到检测与结果处理的完整链路。

#### Scenario: Loading initializes before analysis
- **WHEN** 用户进入 `InspectionLoadingActivity`
- **THEN** 页面必须完成权限检查、SDK 初始化和相机预热
- **AND** 初始化成功后应调用 `InspectionWorkflowSession.beginInspection(...)`
- **AND** 应依据企业流程开关和 Wi-Fi 连接状态分流到下一页

#### Scenario: Direct analysis entry requires initialized inspection session
- **WHEN** `AiInspectionActivity` 被创建
- **THEN** 若 `InspectionSession.isInitialized` 为 false，应返回 `InspectionLoadingActivity`
- **AND** 不能继续当前分析页逻辑

### Requirement: Maintain two primary page states in AI inspection
`AiInspectionActivity` MUST 维护检测态与流式结果态两类主要页面状态。

#### Scenario: Detecting state is the default active state
- **WHEN** 分析页首次进入或从结果页返回检测
- **THEN** 页面应处于 `PageState.DETECTING`
- **AND** 自动推理管线应在帧流准备后启动
- **AND** 检测态应显示检测页功能菜单

#### Scenario: Stream response state shows analysis result
- **WHEN** 手动流式分析、自动隐患结果呈现或本地/在线详情页被触发
- **THEN** 页面应切换到 `PageState.STREAM_RESPONSE`
- **AND** 展示文本结果、缩略图和保存/返回相关提示

#### Scenario: Function menu is hidden outside detecting state
- **WHEN** 页面处于 `PageState.STREAM_RESPONSE`
- **THEN** `updateFunctionMenuVisibility()` 应隐藏巡检功能菜单

### Requirement: Support current detecting-state controls
分析页在检测态 MUST 支持当前代码定义的触控与语音动作。

#### Scenario: Detecting state manual analysis
- **WHEN** 页面处于 `PageState.DETECTING`
- **THEN** 单击应触发“分析”
- **AND** 语音“分析”“深度分析”应触发手动流式分析

#### Scenario: Detecting state return to menu
- **WHEN** 页面处于 `PageState.DETECTING`
- **THEN** 触控 `BACK` 与 `DOUBLE_CLICK` 应返回菜单
- **AND** 语音“返回”“取消”应返回菜单

#### Scenario: Detecting state finish inspection
- **WHEN** 页面处于 `PageState.DETECTING`
- **THEN** 语音“结束”“结束巡查”“结束识患”等结束指令应触发结束巡检
- **AND** 页面应进入 `InspectionEndReportActivity`

#### Scenario: Detecting state can branch to hazard record
- **WHEN** 页面处于 `PageState.DETECTING`
- **THEN** 页面可以进入 `HazardRecordActivity`
- **AND** 该跳转属于正式链路中的补充分支

### Requirement: Support current stream-response controls
分析页在结果态 MUST 支持当前代码定义的保存、确认和返回控制。

#### Scenario: Stream response supports confirm and return actions
- **WHEN** 页面处于 `PageState.STREAM_RESPONSE`
- **THEN** 确认触发器应由 `CLICK`、语音“确认”“确定”“继续”构成
- **AND** 返回触发器应由 `BACK`、`DOUBLE_CLICK`、语音“返回”“取消”构成
- **AND** 这些动作应根据当前本地描述页、建议页或在线流式结果页状态执行保存、继续或返回检测等逻辑

### Requirement: Document current head-gesture boundary
分析页规格 MUST 明确记录头部动作当前在正式页面中未启用。

#### Scenario: Head gesture remains documented but inactive
- **WHEN** 文档描述控制逻辑
- **THEN** 必须说明 `UnifiedInputSession` 仍保留头部动作触发器设计入口
- **AND** 由于 `HEAD_GESTURE_LISTENING_ENABLED = false`，正式分析页当前仅启用触控与语音控制

### Requirement: Capture failure and fallback boundaries
分析页规格 MUST 记录关键异常和降级边界，避免把理想流程误当作必达路径。

#### Scenario: Loading failure stays on loading page
- **WHEN** 权限、SDK 初始化或相机帧流准备失败
- **THEN** `InspectionLoadingActivity` 应停留在当前页并进入错误态
- **AND** 只允许通过重试动作重新开始初始化

#### Scenario: Stream detail failure returns to detecting
- **WHEN** 在线详情链路失败或图片编码失败
- **THEN** 页面应返回检测态
- **AND** 以 toast 或提示形式告知用户失败
