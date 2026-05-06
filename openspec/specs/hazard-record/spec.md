## Purpose

描述 `HazardRecordActivity` 当前的独立隐患录入流程，包括拍照、倒计时、实时分析、保存、结束任务以及输入控制边界。

## Requirements

### Requirement: Support standalone hazard record workflow
`HazardRecordActivity` MUST 作为正式链路中的独立隐患录入功能，支持拍照、实时分析、保存与结束任务。

#### Scenario: Idle state prepares capture workflow
- **WHEN** 页面首次进入
- **THEN** 应先确保权限、SDK 和共享帧流就绪
- **AND** 在空闲态显示拍照提示

#### Scenario: End task goes to report page
- **WHEN** 用户在空闲态触发“结束任务”
- **THEN** 页面应进入 `InspectionEndReportActivity`

### Requirement: Maintain idle, countdown, and analysis states
隐患录入页 MUST 维护当前代码中定义的三种页面状态。

#### Scenario: Idle state waits for capture or realtime analysis
- **WHEN** 页面处于 `IDLE`
- **THEN** 用户可以发起拍照倒计时、实时分析、设备指引占位或结束任务

#### Scenario: Countdown state precedes capture
- **WHEN** 用户触发拍照
- **THEN** 页面应进入 `COUNTDOWN`
- **AND** 完成倒计时后再执行截帧和分析

#### Scenario: Analysis state shows result and allows confirm/return
- **WHEN** 页面完成分析并切到 `ANALYSIS`
- **THEN** 页面应展示分析文本和缩略图
- **AND** 用户可通过确认执行保存，通过返回回到空闲态

### Requirement: Provide current touch and voice controls
隐患录入页 MUST 按当前代码提供触控与语音控制逻辑。

#### Scenario: Capture uses touch and voice in idle state
- **WHEN** 页面处于 `IDLE`
- **THEN** 单击和语音“拍照”应触发拍照倒计时

#### Scenario: Realtime analysis uses voice in idle state
- **WHEN** 页面处于 `IDLE`
- **THEN** 语音“实时分析”应触发实时分析

#### Scenario: Device guide placeholder uses voice in idle state
- **WHEN** 页面处于 `IDLE`
- **THEN** 语音“设备指引”应只展示开发中提示

#### Scenario: Finish task uses voice in idle state
- **WHEN** 页面处于 `IDLE`
- **THEN** 语音“结束任务”或“结束”应进入结束页

#### Scenario: Analysis state confirm and return controls
- **WHEN** 页面处于 `ANALYSIS`
- **THEN** 确认触发器应为 `CLICK`、语音“确认”“确定”
- **AND** 返回触发器应为 `BACK`、`DOUBLE_CLICK`、语音“返回”“取消”

#### Scenario: Idle state exit reuses return triggers
- **WHEN** 页面处于 `IDLE`
- **THEN** 返回触发器应作为退出动作直接结束当前页面

#### Scenario: Head gesture is not active in hazard record
- **WHEN** 文档描述陀螺仪/头部动作
- **THEN** 必须说明该页面未显式启用头部动作触发器
- **AND** 当前正式控制仅依赖触控和语音

### Requirement: Record failure and downgrade boundaries
隐患录入页 MUST 记录异常和降级路径，避免把页面理解成总能分析成功。

#### Scenario: Missing permission blocks frame readiness
- **WHEN** 相机或媒体权限未授予
- **THEN** 页面应提示权限或帧流不可用
- **AND** 不应进入分析流程

#### Scenario: Frame stream failure blocks idle capture
- **WHEN** 共享帧流准备失败
- **THEN** 页面应提示 `hazard_record_frame_stream_failed`
- **AND** 用户不能把页面视为可正常拍照分析状态
