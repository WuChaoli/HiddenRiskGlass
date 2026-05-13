## enterprise-info-last-inspection-date

### 需求

企业信息接口返回的 `lastInspectionDate`（最近巡查时间）需要在企业信息页面上真实展示，替换当前硬编码的兜底文案。

### 数据流

```
API → ObjectMessageData.lastInspectionDate (String?)
    → EnterpriseInfo.lastInspectionDate (String, default "")
    → EnterpriseInfoActivity.tvRecentInspectionTime
```

### 展示规则

- `lastInspectionDate` 有值：显示 `"最近巡查时间：<lastInspectionDate>"`
- `lastInspectionDate` 为空：回退到 `RECENT_INSPECTION_TIME` 兜底文案

### 格式

API 返回格式为 `YYYY年MM月DD日`，客户端不做格式转换，直接拼接前缀展示。