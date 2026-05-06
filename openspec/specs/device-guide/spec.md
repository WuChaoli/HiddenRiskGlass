## Purpose

描述 `DeviceGuideActivity` 当前已接入的正式设备指引能力，包括入口、远端检测、检查重点确认、跨功能跳转和结束巡检返回边界。

## Requirements

### Requirement: Device guide is a standalone page in the formal chain
系统 MUST 将设备指引实现为独立 `Activity`，而不是菜单占位提示。

#### Scenario: Menu can enter device guide
- **WHEN** 用户在 `AiInspectionMenuActivity` 选择“设备指引”
- **THEN** 若 `InspectionSession.isInitialized` 为 true，应进入 `DeviceGuideActivity`
- **AND** 否则应先进入 `InspectionLoadingActivity`

#### Scenario: Hazard analysis and hazard record can branch to device guide
- **WHEN** 用户在 `AiInspectionActivity` 或 `HazardRecordActivity` 触发语音“设备指引”
- **THEN** 页面应跳转到 `DeviceGuideActivity`
- **AND** 跳转后只进入设备指引首页，不恢复来源页中间态

### Requirement: Device guide runs remote detection in detecting state
设备指引页在检测态 MUST 持续运行远端检查品判定链路。

#### Scenario: Detecting state shows dedicated menu
- **WHEN** `DeviceGuideActivity` 处于检测态
- **THEN** 右上角功能菜单应显示 `实时分析\n隐患录入\n结束任务`
- **AND** 结果态不应继续显示该菜单

#### Scenario: Remote detect uses temporary ctype=1 fallback
- **WHEN** 设备指引页执行检查品判定
- **THEN** 当前实现应使用 `network.deviceGuideDetectApi`
- **AND** 当前接口仍临时复用 `/ai/ar`
- **AND** 判定阶段当前临时复用 `ctype=1`

### Requirement: Positive detection requires user confirmation before detail fetch
设备指引页 MUST 在判定命中后先请求用户确认，再拉取检查重点详情。

#### Scenario: Positive detect opens confirmation prompt
- **WHEN** 远端判定返回“是”
- **THEN** 页面应切换到结果态
- **AND** 展示底部确认提醒
- **AND** 提醒文案必须为 `识别到此处有检查品，是否要提供检查重点？`

#### Scenario: Confirm fetches temporary detail stream
- **WHEN** 用户在确认节点执行单击、或语音“确认”“确定”“继续”
- **THEN** 页面应调用详情接口获取检查重点
- **AND** 当前详情链路临时复用 `/ai/ar` `ctype=0`

#### Scenario: Detail content uses card-only result presentation
- **WHEN** 详情流返回文本
- **THEN** 页面应在底部卡片展示检查重点内容
- **AND** 展示样式应接近 advice 结果卡片
- **AND** 不应显示底部确认/返回提示文案

### Requirement: Device guide input mapping follows unified input semantics
设备指引页 MUST 通过 `UnifiedInputSession` 注册当前正式输入映射。

#### Scenario: Confirm and cancel mapping
- **WHEN** 文档描述设备指引页输入动作
- **THEN** “确认”“确定”“继续”必须承接确认操作
- **AND** “取消”“返回”必须承接返回菜单操作
- **AND** 触控 `CLICK` 对应确认
- **AND** 触控 `BACK` 与 `DOUBLE_CLICK` 对应取消/返回

#### Scenario: Cross-feature voice routing is available
- **WHEN** 用户在设备指引页说出“实时分析”或“隐患录入”
- **THEN** 页面应分别跳转到 `AiInspectionActivity` 与 `HazardRecordActivity`
- **AND** 跳转后只进入目标功能首页

#### Scenario: Finish command enters shared end report
- **WHEN** 用户在设备指引页说出“结束”或“结束任务”
- **THEN** 页面应进入 `InspectionEndReportActivity`
- **AND** 返回来源应标记为 `DEVICE_GUIDE_HOME`
