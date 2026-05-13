## 场所代码接收与存储

### 需求

企业信息接口返回的 `placeCode`（场所代码）字段需要被正确接收和传递。

### 行为

1. `ObjectMessageData` 包含 `placeCode: String?` 字段，Gson 自动从 JSON 解析
2. `EnterpriseInfo` 包含 `placeCode: String` 字段，默认为空字符串
3. `updateEnterpriseObjectInfo` 接收 `placeCode` 参数并写入 `EnterpriseInfo`
4. `EnterpriseInfoActivity` 获取 `info.placeCode`，当前不展示

### 不在范围内的

- 本 spec 不涉及 UI 展示。展示需求见 `enterprise-info-display` spec

---

## 最近巡查时间的真实展示

### 需求

企业信息接口返回的 `lastInspectionDate`（最近巡查时间，格式 `YYYY年MM月DD日`）需要在企业信息页面上展示，替换当前硬编码的兜底文案。

### 行为

1. `ObjectMessageData` 包含 `lastInspectionDate: String?` 字段，Gson 自动从 JSON 解析
2. `EnterpriseInfo` 包含 `lastInspectionDate: String` 字段，默认为空字符串
3. `updateEnterpriseObjectInfo` 接收 `lastInspectionDate` 参数并写入 `EnterpriseInfo`
4. `EnterpriseInfoActivity.bindEnterpriseInfo()`：
   - 若 `info.lastInspectionDate` 非空，`tvRecentInspectionTime.text = "最近巡查时间：${info.lastInspectionDate}"`
   - 若为空，`tvRecentInspectionTime.text = RECENT_INSPECTION_TIME`（兜底文案）

### 回退规则

- API 返回 `lastInspectionDate` 为 `null` 或空字符串时，使用 `InspectionAppConfig.recentInspectionTimeFallbackText` 作为兜底
- 格式不正确的 `lastInspectionDate` 由后端保证格式正确，客户端直接拼接展示