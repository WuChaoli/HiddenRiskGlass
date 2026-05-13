## Design: 企业信息新增字段

### 概述

在企业扫码获取对象信息的接口返回中，新增 `placeCode`（场所代码）和 `lastInspectionDate`（最近巡查时间）两个字段的接收与处理。`placeCode` 仅存储不展示，`lastInspectionDate` 替换现有的 mock 兜底文案并在 UI 上真实展示。

### 数据流

```
API Response (JSON)
  ├─ placeCode: String?           ← 新增，接收存储
  ├─ lastInspectionDate: String?  ← 新增，接收并展示
  └─ (其余字段不变)
       ↓
ObjectMessageData (数据层)
  ├─ placeCode                    ← 新增字段
  ├─ lastInspectionDate           ← 新增字段
  └─ (其余字段不变)
       ↓
updateEnterpriseObjectInfo() → EnterpriseInfo (会话层)
  ├─ placeCode                    ← 新增字段
  ├─ lastInspectionDate           ← 新增字段
  └─ (其余字段不变)
       ↓
EnterpriseInfoActivity UI
  ├─ tvRecentInspectionTime       ← 改为显示 lastInspectionDate（格式化后）
  └─ placeCode 不展示，预留存储
```

### 修改点详述

#### 1. `EnterpriseObjectMessageService.kt`

**`ObjectMessageData` 新增字段：**
```kotlin
data class ObjectMessageData(
    val objectName: String? = null,
    val areaName: String? = null,
    val domain: String? = null,
    val tags: String? = null,
    val riskLevel: String? = null,
    val hidDanger: List<ObjectMessageHazard>? = null,
    val placeCode: String? = null,              // 新增
    val lastInspectionDate: String? = null,      // 新增
)
```

**`logResponseShape` 新增空白检查：**
- 增加 `placeCode` 和 `lastInspectionDate` 的空值日志记录

#### 2. `InspectionWorkflowSession.kt`

**`EnterpriseInfo` 新增字段：**
```kotlin
data class EnterpriseInfo(
    val companyName: String,
    val siteName: String,
    val inspectorName: String,
    val qrContent: String,
    val region: String = "",
    val category: String = "",
    val riskTags: String = "",
    val riskLevel: String = "",
    val hazardHistory: List<String> = emptyList(),
    val placeCode: String = "",                 // 新增
    val lastInspectionDate: String = "",        // 新增
)
```

**`updateEnterpriseObjectInfo` 新增参数：**
```kotlin
fun updateEnterpriseObjectInfo(
    companyName: String?,
    region: String?,
    category: String?,
    riskTags: String?,
    riskLevel: String?,
    hazardHistory: List<String>,
    placeCode: String?,                         // 新增
    lastInspectionDate: String?,                // 新增
)
```

#### 3. `EnterpriseQrScanActivity.kt`

**`onSuccess` 回调传参新增：**
```kotlin
InspectionWorkflowSession.updateEnterpriseObjectInfo(
    ...
    placeCode = data.placeCode,
    lastInspectionDate = data.lastInspectionDate,
)
```

#### 4. `EnterpriseInfoActivity.kt`

**`bindEnterpriseInfo` 方法：**
- `tvRecentInspectionTime` 改为使用 `info.lastInspectionDate` 拼接前缀
- 若 `lastInspectionDate` 为空则回退到 `RECENT_INSPECTION_TIME`
- `placeCode` 由 `info.placeCode` 接收，不展示

#### 5. 日志增强

`logResponseShape` 中新增 `placeCode` 和 `lastInspectionDate` 的空值检查日志，方便排查接口数据问题。