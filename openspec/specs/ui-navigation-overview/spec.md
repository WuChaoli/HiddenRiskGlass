## Purpose

描述当前正式巡检主链的页面跳转逻辑、入口分流、返回路径和关键会话依赖，并把调试/历史页面收敛到附录边界。

## Requirements

### Requirement: Define the formal inspection navigation chain
系统 MUST 维护一份正式巡检主链页面跳转总览，描述启动入口、分流条件、主要前进路径、结束路径和关键会话依赖。

#### Scenario: Overview includes a clear mainline diagram
- **WHEN** 开发者阅读页面跳转总览
- **THEN** 文档应提供清晰的主链图
- **AND** 主链图至少应表达以下关系：
- **AND** `InspectionLoadingActivity -> AiInspectionActivity`
- **AND** `InspectionLoadingActivity -> DeviceGuideActivity`
- **AND** `InspectionLoadingActivity -> WifiQrScanActivity -> EnterpriseQrScanActivity -> EnterpriseInfoActivity -> AiInspectionMenuActivity`
- **AND** `AiInspectionMenuActivity -> AiInspectionActivity`
- **AND** `AiInspectionMenuActivity -> DeviceGuideActivity`
- **AND** `AiInspectionMenuActivity -> HazardRecordActivity`
- **AND** `AiInspectionActivity -> InspectionEndReportActivity`
- **AND** `DeviceGuideActivity -> InspectionEndReportActivity`
- **AND** `HazardRecordActivity -> InspectionEndReportActivity`

#### Scenario: Launcher enters inspection loading
- **WHEN** 用户从桌面启动应用
- **THEN** 系统应先进入 `InspectionLoadingActivity`
- **AND** 该页面负责权限检查、SDK 初始化、相机预热和后续分流前置条件准备

#### Scenario: Loading page routes by enterprise flag, Wi-Fi state, and optional home target
- **WHEN** `InspectionLoadingActivity` 初始化完成并准备分流
- **THEN** 若企业巡检开关关闭，应进入默认首页或显式指定首页
- **AND** 当前支持默认进入 `AiInspectionActivity`
- **AND** 当前支持通过首页目标参数进入 `DeviceGuideActivity`
- **AND** 若企业巡检开关开启且当前已连接 Wi-Fi，应进入 `EnterpriseQrScanActivity`
- **AND** 若企业巡检开关开启但当前未连接 Wi-Fi，应进入 `WifiQrScanActivity`

#### Scenario: Enterprise context prepares the menu chain
- **WHEN** `EnterpriseQrScanActivity` 成功解析企业二维码并拉取对象信息
- **THEN** 应进入 `EnterpriseInfoActivity`
- **AND** `InspectionWorkflowSession` 应保存企业扫码上下文和企业详情

#### Scenario: Menu routes to three formal functions
- **WHEN** 用户在 `AiInspectionMenuActivity` 选择菜单项
- **THEN** “隐患分析”应进入 `AiInspectionActivity` 或在会话未初始化时回到 `InspectionLoadingActivity`
- **AND** “设备指引”应进入 `DeviceGuideActivity` 或在会话未初始化时回到 `InspectionLoadingActivity`
- **AND** “隐患录入”应进入 `HazardRecordActivity`

#### Scenario: Feature pages can cross-jump at homepage granularity
- **WHEN** 用户在正式功能页触发同级功能语音跳转
- **THEN** `AiInspectionActivity` 可以跳到 `DeviceGuideActivity` 与 `HazardRecordActivity`
- **AND** `DeviceGuideActivity` 可以跳到 `AiInspectionActivity` 与 `HazardRecordActivity`
- **AND** `HazardRecordActivity` 可以跳到 `DeviceGuideActivity`
- **AND** 这些跳转都只进入目标功能首页

#### Scenario: Shared end report returns by source
- **WHEN** 用户从 `AiInspectionActivity`、`DeviceGuideActivity` 或 `HazardRecordActivity` 进入 `InspectionEndReportActivity`
- **THEN** 结束页取消时应回到对应来源功能首页
- **AND** 确认时应提交结束巡检后台上报并退出应用任务

### Requirement: Expose session dependencies in the overview
页面总览 MUST 明确依赖的关键会话状态，避免把页面跳转理解为纯 UI 路由。

#### Scenario: Inspection session gates direct entry
- **WHEN** `AiInspectionActivity` 或 `DeviceGuideActivity` 被直接启动
- **THEN** 若 `InspectionSession.isInitialized` 为 false，应返回 `InspectionLoadingActivity`
- **AND** 不能把直启功能页视为稳定入口

#### Scenario: Workflow session carries cross-page state
- **WHEN** 页面总览描述扫码、菜单、巡检、结束页链路
- **THEN** 应明确 `InspectionWorkflowSession` 负责维护在线/离线模式、企业扫码上下文、分析结果和结束页汇总数据

### Requirement: Appendix pages are recorded without elevating them
页面总览文档 MUST 包含附录章节，收敛调试页和历史入口页的用途与边界。

#### Scenario: Appendix lists non-mainline pages
- **WHEN** 文档列出附录页面
- **THEN** 至少应包含 `UnifiedInputDebugActivity`、`HiddenRiskProbeActivity`、`LightshotActivity`、`HomeActivity`、`InspectionModeActivity`
- **AND** 每个附录条目应说明页面用途、是否属于正式主链、参考价值以及不建议作为产品基线的原因
