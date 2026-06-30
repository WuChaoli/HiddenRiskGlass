# workflow/ — 巡检工作流会话

## 业务概述

`InspectionWorkflowSession` 是跨页面的巡检业务上下文单例，保存从企业扫码开始到结束巡检的完整状态。

### 核心数据
- **企业上下文：** `EnterpriseQrPayload`（authCode, objectId, userId, apiBaseUrl）
- **巡检结果：** 检测标题/消息、分析文本、截图、保存记录
- **工作流模式：** `WorkflowMode`（WIFI / NORMAL）

### QR 码解析
支持三种格式：legacy（空格分隔）、query（URL 参数）、JSON，自动检测并解析。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionWorkflowSession.kt` | **巡检工作流会话单例** | `getInstance()`, `beginInspection()`, `updateEnterpriseFromQr()`, `recordDetection()`, `recordAnalysis()`, `recordCapture()`, `recordSavedHazardAttempt()`, `buildEndReportRecords()`, `clearForNewInspection()`, `clearEnterpriseData()` |

### 关键方法

| 方法 | 用途 |
|------|------|
| `updateEnterpriseFromQr(qrContent)` | 解析 QR 码，写入企业上下文 |
| `beginInspection(sessionId)` | 开始新巡检，生成 sessionId |
| `recordDetection(title, message)` | 记录检测结果 |
| `recordAnalysis(text)` | 记录分析文本 |
| `recordCapture(jpegBytes)` | 记录截图 |
| `recordSavedHazardAttempt(key, itemCount)` | 记录上传尝试 |
| `updateSavedHazardAttemptOutcome(key, outcome, hints)` | 更新上传结果 (SUCCESS/FAILED) |
| `buildEndReportRecords()` | 构建结束巡检报告 |
| `clearForNewInspection()` | 清理累计数据，保留企业上下文 |
| `clearEnterpriseData()` | 清除企业扫码信息（QR + 企业详情），在结束巡检确认和应用退出时调用 |

## 依赖关系

- **依赖：** 无（纯内存状态管理）
- **被依赖：** `hiddenrisk/`（所有页面读写工作流状态）
