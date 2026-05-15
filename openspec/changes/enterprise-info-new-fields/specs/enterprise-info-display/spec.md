## enterprise-info-display

### 需求

企业信息展示页的数据绑定逻辑变更，将真实数据（`lastInspectionDate`）替代 mock 兜底文案，`placeCode` 字段接收存储但不展示。

### 修改点

1. `EnterpriseInfoActivity.bindEnterpriseInfo()`：
   - `tvRecentInspectionTime` 从 `RECENT_INSPECTION_TIME`（硬编码）改为 `info.lastInspectionDate`
   - `info.placeCode` 接收但不展示

2. `EnterpriseInfoActivity.bindDebugEnterpriseInfo()`：
   - 保持不变，debug 模式仍使用 mock 数据

### UI 影响

- 无新增控件
- `tvRecentInspectionTime` 内容从静态文案变为动态数据