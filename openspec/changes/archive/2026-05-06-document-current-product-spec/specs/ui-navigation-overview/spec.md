## ADDED Requirements

### Requirement: Define the formal inspection navigation chain
系统 MUST 维护一份正式巡检主链页面跳转总览，描述启动入口、分流条件、主要前进路径、结束路径和关键会话依赖。

#### Scenario: Overview includes a clear mainline diagram
- **WHEN** 开发者阅读页面跳转总览
- **THEN** 文档应提供清晰的主链图
- **AND** 主链图至少应表达 `InspectionLoadingActivity -> AiInspectionActivity`
- **AND** 主链图至少应表达 `InspectionLoadingActivity -> WifiQrScanActivity -> EnterpriseQrScanActivity -> EnterpriseInfoActivity -> AiInspectionMenuActivity`
- **AND** 主链图至少应表达 `AiInspectionMenuActivity -> AiInspectionActivity`
- **AND** 主链图至少应表达 `AiInspectionMenuActivity -> HazardRecordActivity`
- **AND** 主链图至少应表达 `AiInspectionActivity -> InspectionEndReportActivity`
- **AND** 主链图至少应表达 `HazardRecordActivity -> InspectionEndReportActivity`

#### Scenario: Launcher enters inspection loading
- **WHEN** 用户从桌面启动应用
- **THEN** 系统应先进入 `InspectionLoadingActivity`
- **AND** 该页面负责权限检查、SDK 初始化、相机预热和后续分流前置条件准备

#### Scenario: Loading page routes by enterprise flag and Wi-Fi state
- **WHEN** `InspectionLoadingActivity` 初始化完成并准备分流
- **THEN** 若企业巡检开关关闭，应直接进入 `AiInspectionActivity`
- **AND** 若企业巡检开关开启且当前已连接 Wi-Fi，应进入 `EnterpriseQrScanActivity`
- **AND** 若企业巡检开关开启但当前未连接 Wi-Fi，应进入 `WifiQrScanActivity`

#### Scenario: Wi-Fi scan hands off to enterprise scan
- **WHEN** `WifiQrScanActivity` 配网成功
- **THEN** 页面应依据 `EXTRA_NEXT_AFTER_SUCCESS` 跳转到 `EnterpriseQrScanActivity`
- **AND** `InspectionWorkflowSession.updateMode(connected = true)` 应反映在线模式

#### Scenario: Enterprise context prepares the menu chain
- **WHEN** `EnterpriseQrScanActivity` 成功解析企业二维码并拉取对象信息
- **THEN** 应进入 `EnterpriseInfoActivity`
- **AND** `InspectionWorkflowSession` 应保存企业扫码上下文和企业详情

#### Scenario: Enterprise info leads to menu
- **WHEN** 用户在 `EnterpriseInfoActivity` 执行确认动作
- **THEN** 应进入 `AiInspectionMenuActivity`

#### Scenario: Menu routes to analysis, guide placeholder, or record
- **WHEN** 用户在 `AiInspectionMenuActivity` 选择菜单项
- **THEN** “隐患分析”应进入 `AiInspectionActivity` 或在会话未初始化时回到 `InspectionLoadingActivity`
- **AND** “设备指引”当前只显示开发中提示
- **AND** “隐患录入”应进入 `HazardRecordActivity`

#### Scenario: Inspection page can end or branch to record
- **WHEN** 用户在 `AiInspectionActivity` 的检测态执行结束巡检
- **THEN** 应进入 `InspectionEndReportActivity`
- **AND** 某些分支可以从巡检页跳到 `HazardRecordActivity`

#### Scenario: Hazard record reaches end report
- **WHEN** 用户在 `HazardRecordActivity` 执行“结束任务”
- **THEN** 应进入 `InspectionEndReportActivity`

#### Scenario: End report exits or returns to menu
- **WHEN** 用户在 `InspectionEndReportActivity` 执行确认
- **THEN** 系统应提交结束巡检后台上报并退出应用任务
- **AND** 当用户执行返回动作时，应回到 `AiInspectionMenuActivity`

### Requirement: Expose session dependencies in the overview
页面总览 MUST 明确依赖的关键会话状态，避免把页面跳转理解为纯 UI 路由。

#### Scenario: Inspection session gates direct entry
- **WHEN** `AiInspectionActivity` 被直接启动
- **THEN** 若 `InspectionSession.isInitialized` 为 false，应返回 `InspectionLoadingActivity`
- **AND** 不能把直启 `AiInspectionActivity` 视为稳定入口

#### Scenario: Workflow session carries cross-page state
- **WHEN** 页面总览描述扫码、菜单、巡检、结束页链路
- **THEN** 应明确 `InspectionWorkflowSession` 负责维护在线/离线模式、企业扫码上下文、分析结果和结束页汇总数据

### Requirement: Appendix pages are recorded without elevating them
页面总览文档 MUST 包含附录章节，收敛调试页和历史入口页的用途与边界。

#### Scenario: Appendix lists non-mainline pages
- **WHEN** 文档列出附录页面
- **THEN** 至少应包含 `UnifiedInputDebugActivity`、`HiddenRiskProbeActivity`、`LightshotActivity`、`HomeActivity`、`InspectionModeActivity`
- **AND** 每个附录条目应说明页面用途、是否属于正式主链、参考价值以及不建议作为产品基线的原因
