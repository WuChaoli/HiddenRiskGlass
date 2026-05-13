# Tasks

## EnterpriseObjectMessageService.kt

- [x] `ObjectMessageData` 新增 `placeCode` 和 `lastInspectionDate` 字段
- [x] `logResponseShape` 新增两个字段的空值检查日志

## InspectionWorkflowSession.kt

- [x] `EnterpriseInfo` 新增 `placeCode` 和 `lastInspectionDate` 字段
- [x] `updateEnterpriseObjectInfo` 新增 `placeCode` 和 `lastInspectionDate` 参数并写入 EnterpriseInfo

## EnterpriseQrScanActivity.kt

- [x] `onSuccess` 回调中将 `placeCode` 和 `lastInspectionDate` 传给 `updateEnterpriseObjectInfo`

## EnterpriseInfoActivity.kt

- [x] `bindEnterpriseInfo` 中 `tvRecentInspectionTime` 使用真实 `lastInspectionDate`（有值时拼接前缀，空值时回退兜底文案）
- [x] `placeCode` 接收但不展示

## strings.xml

- [x] 新增 `enterprise_info_recent_inspection_time_prefix` 字符串资源

## 验证

- [ ] 编译通过
- [ ] debug 模式不受影响
- [ ] 真实 API 返回 `lastInspectionDate` 时正确展示
- [ ] API 无 `lastInspectionDate` 时兜底文案正常显示