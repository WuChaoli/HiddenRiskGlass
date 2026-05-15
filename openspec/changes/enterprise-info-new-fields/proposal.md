## Why

企业扫码接口新增了 `placeCode`（场所代码）和 `lastInspectionDate`（最近巡查时间）两个返回字段。当前 App 端既未接收这两个字段，也未在 UI 上展示最近巡查时间的真实数据（使用了硬编码兜底文案）。需要补齐数据接收和展示链路，使企业信息页面能显示真实的最近巡查时间。

## What Changes

- **`ObjectMessageData`**：新增 `placeCode`（String?）和 `lastInspectionDate`（String?）字段，用于接收 API 返回
- **`EnterpriseInfo`**（会话层）：新增 `placeCode` 和 `lastInspectionDate` 字段，用于跨页面传递
- **`updateEnterpriseObjectInfo`**：新增 `placeCode` 和 `lastInspectionDate` 参数并写入 `EnterpriseInfo`
- **`EnterpriseQrScanActivity.onSuccess`**：将 `placeCode` 和 `lastInspectionDate` 传给 `updateEnterpriseObjectInfo`
- **`EnterpriseInfoActivity`**：
  - `lastInspectionDate`：使用接口返回的真实巡查时间替换当前的 `RECENT_INSPECTION_TIME` 兜底文案
  - `placeCode`：接收但不展示（为后续扩展预留）
- **`logResponseShape`**：增加对 `placeCode` 和 `lastInspectionDate` 的空白检查日志

## Capabilities

### New Capabilities
- `enterprise-info-place-code`: 场所代码字段的数据接收与存储能力
- `enterprise-info-last-inspection-date`: 最近巡查时间字段的数据接收与真实展示能力

### Modified Capabilities
- `enterprise-info-display`: 企业信息展示页的数据绑定逻辑变更（替换 mock 兜底文案为真实数据）

## Impact

- `EnterpriseObjectMessageService.kt`：`ObjectMessageData` 类新增字段，`logResponseShape` 新增检查项
- `InspectionWorkflowSession.kt`：`EnterpriseInfo` 数据类新增字段，`updateEnterpriseObjectInfo` 新增参数
- `EnterpriseQrScanActivity.kt`：`onSuccess` 回调传参新增两个字段
- `EnterpriseInfoActivity.kt`：`bindEnterpriseInfo` 中使用真实巡查时间
- `activity_enterprise_info.xml`：无需新增控件（`placeCode` 不展示，`lastInspectionDate` 复用已有 `tvRecentInspectionTime`）