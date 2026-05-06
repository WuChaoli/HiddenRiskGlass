## 1. Build OpenSpec Documentation Baseline

- [x] 1.1 新建 `document-current-product-spec` change，并补齐 proposal、design、tasks。
- [x] 1.2 新增 `project-doc-index` 与 `ui-navigation-overview` 两份总览规格。
- [x] 1.3 新增 `wifi-qr-scan`、`ai-inspection-menu`、`hazard-analysis`、`device-guide`、`hazard-record` 五份功能规格。

## 2. Capture Current Product Behavior

- [x] 2.1 从现有代码提炼正式主链页面跳转、入口分流、返回路径和会话依赖。
- [x] 2.2 从现有代码提炼每个核心功能的触控、语音、陀螺仪控制逻辑。
- [x] 2.3 把调试页、探针页、历史入口页收敛为附录或索引，不混入正式功能正文。

## 3. Update Navigation and Validate

- [x] 3.1 更新 `AGENTS.md`，新增 OpenSpec 文档导航入口。
- [x] 3.2 运行 `openspec list --specs` 和 `openspec validate`，确认规格结构可用。
- [x] 3.3 回顾所有规格是否满足“页面跳转 + 控制逻辑 + 边界说明”的最小完整性要求。
