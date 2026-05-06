## Purpose

维护当前 `glassdemo` 产品规格索引，明确正式功能文档、总览文档、架构文档和附录文档之间的关系，并为 `AGENTS.md` 提供稳定导航入口。

## Requirements

### Requirement: Maintain product documentation index
项目 MUST 维护一份面向后续开发和排障的规格索引，明确当前正式功能、总览文档、架构文档和附录文档之间的关系。

#### Scenario: OpenSpec documents are used as current product baseline
- **WHEN** 开发者需要了解当前巡检产品的页面、功能或输入行为
- **THEN** 应优先查阅 `openspec/specs/` 下的正式规格文档
- **AND** 这些规格文档应覆盖正式主链功能与页面跳转逻辑

#### Scenario: Topic-specific experience remains in docs
- **WHEN** 开发者需要查看 HiddenRisk 模型验证、HeadGesture 调参或 UnifiedInput 接入细节
- **THEN** 应跳转到现有 `docs/` 下的专题文档
- **AND** OpenSpec 索引文档应只保留专题用途说明与路径，不重复正文

### Requirement: Distinguish formal chain from appendix pages
索引文档 MUST 区分正式主链页面与调试/历史页面，避免把所有注册页面都当成等价产品功能。

#### Scenario: Formal chain pages are surfaced first
- **WHEN** 索引文档列出页面相关文档
- **THEN** 应优先列出正式主链相关能力和总览文档
- **AND** `InspectionLoadingActivity`、`WifiQrScanActivity`、`EnterpriseQrScanActivity`、`EnterpriseInfoActivity`、`AiInspectionMenuActivity`、`AiInspectionActivity`、`HazardRecordActivity`、`InspectionEndReportActivity` 应按正式主链处理

#### Scenario: Debug and historical pages are appendix only
- **WHEN** 索引文档提到 `UnifiedInputDebugActivity`、`HiddenRiskProbeActivity`、`LightshotActivity`、`HomeActivity`、`InspectionModeActivity`
- **THEN** 应标记它们属于附录或参考页
- **AND** 应说明这些页面不应作为当前正式产品流程真相源

### Requirement: AGENTS.md links to the specification set
`AGENTS.md` MUST 提供 OpenSpec 文档入口，作为项目治理与导航层的单一入口之一。

#### Scenario: AGENTS.md exposes OpenSpec navigation
- **WHEN** 开发者阅读 `AGENTS.md`
- **THEN** 应能看到 OpenSpec 文档导航区块
- **AND** 该区块至少包含 `project-doc-index`、`ui-navigation-overview`、`wifi-qr-scan`、`ai-inspection-menu`、`hazard-analysis`、`device-guide`、`hazard-record` 以及本 change 的 `design.md`
