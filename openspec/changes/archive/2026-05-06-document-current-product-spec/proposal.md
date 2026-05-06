## Why

当前 `glassdemo` 已经形成一条可运行的 Rokid Glass 巡检主链，但产品行为主要散落在 Activity、Session、输入映射和少量专题文档里。后续无论是改巡检流程、补设备控制，还是清理历史页面，都缺少一套以“当前真实行为”为准的规格基线。

本次 change 的目标不是新增功能，而是把当前代码中已经存在的页面跳转逻辑、功能边界、输入控制逻辑和架构协作关系抽取成 OpenSpec 文档，降低后续修改时的误判和回归风险。

## What Changes

本次变更会新增一组 OpenSpec 能力规格与一份总架构设计文档，用于描述：

- 项目规格索引和文档导航
- 正式主链的 UI 页面跳转总览
- Wi-Fi 扫码功能
- AI 巡检菜单功能
- AI 隐患识别功能
- 设备指引占位功能
- 隐患录入功能

同时更新 `AGENTS.md`，增加这些规格文档的导航入口，并与现有专题经验文档形成互补关系。

## Capabilities

### New Capabilities
- `project-doc-index`: 维护当前产品规格索引、正式功能入口和附录页导航。
- `ui-navigation-overview`: 维护正式主链页面跳转、分流条件、返回路径和会话依赖。
- `wifi-qr-scan`: 维护 Wi-Fi 扫码配网、连接验证和成功后跳转行为。
- `ai-inspection-menu`: 维护 AI 巡检菜单的选项、跳转和输入映射。
- `hazard-analysis`: 维护加载页与 AI 巡检页的识别主流程、结果页和控制逻辑。
- `device-guide`: 维护设备指引当前占位状态及其入口边界。
- `hazard-record`: 维护隐患录入页的拍照、分析、保存和结束任务流程。

### Modified Capabilities
- `<existing-name>`: None.

## Impact

- 新增 `openspec/changes/document-current-product-spec/` 下的 proposal、design、tasks。
- 新增 `openspec/specs/` 下 7 个 capability 规格。
- 更新 `AGENTS.md` 的文档导航区块。
- 不修改任何业务代码、资源或配置行为。
