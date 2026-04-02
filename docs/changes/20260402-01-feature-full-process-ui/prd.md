# AI识患全流程UI对接PRD

## 一、功能概述

将UI设计图中的跳转逻辑与后端实际处理逻辑对接，实现从"AI识患"入口到隐患检测、深度分析、同步保存的完整流程。

---

## 二、UI流程图节点与代码对应关系

| UI节点 | 对应Activity/状态 | 实现状态 |
|-------|------------------|---------|
| 01-初始界面 | `HomeActivity` | ✅ 已有 |
| 02-加载界面 | `HiddenRiskProbeActivity` - LOADING | ✅ 已有 |
| 02-1-加载异常 | `HiddenRiskProbeActivity` - FAILED | ✅ 已有 |
| 02-2-镜头遮挡 | 待实现（暂不优先） | ⏸️ 暂缓 |
| 06-确认开始指引 | 待实现（暂不优先） | ⏸️ 暂缓 |
| 06-2-检查过程中 | `HiddenRiskProbeActivity` - CAPTURING | ✅ 已有（需优化） |
| 07-无隐患界面 | `HiddenRiskProbeActivity` - SAFE | ⚠️ 需修改 |
| 07-1-发现隐患界面 | `HiddenRiskProbeActivity` - ALERT | ⚠️ 需修改 |
| 07-2-显示隐患界面 | `HiddenRiskDetailActivity` (新) | ❌ 待实现 |
| 07-3-隐患同步界面 | `HiddenRiskSyncActivity` 或状态 | ❌ 待实现 |

---

## 三、详细功能需求

### 3.1 AI识患菜单入口

**位置**: `HomeActivity` - `initIconData()`

**修改内容**:
- 在 `MenuConfigType.MenuInfoType` 中添加 `MENU_HIDDEN_RISK`
- 在 `HomeActivity` 菜单列表中添加"AI识患"选项
- 点击后跳转到 `HiddenRiskProbeActivity`

---

### 3.2 无隐患流程 (07-无隐患界面)

**触发条件**: 本地推理完成，未检测到隐患

**当前问题**: `showSafeUi()` 只显示2秒就自动消失，没有用户交互

**修改方案**:
- 显示"此区域无隐患，是否继续巡检其他区域？"
- **手势交互**:
  - 单击 → 继续巡检（返回自动拍照循环）
  - 双击 → 结束巡检（退出Activity，返回Home）

---

### 3.3 发现隐患后的深度分析流程

#### 3.3.1 07-1-发现隐患界面

**触发条件**: 本地推理检测到隐患

**UI显示**:
- 警告图标 + AR绿色检测框
- "发现安全隐患"
- "单击查看详情，双击跳过"

**手势交互**:
- 单击 → 进入深度分析流程（调用API上传图片）
- 双击 → 视为误检，返回巡检循环

**API调用**:
- 调用后端API上传图片
- 接收SSE流式响应

---

#### 3.3.2 07-2-显示隐患界面（新Activity）

**Activity**: `HiddenRiskDetailActivity`

**UI组件**:
- 流式文字显示区域（逐字/逐句显示分析结果）
- "正在深度分析..."进度提示
- 分析完成后显示:
  - 隐患类型
  - 隐患描述
  - "隐患信息是否同步手机端？"

**手势交互**:
- 单击 → 确认保存（进入07-3）
- 双击 → 取消保存，返回巡检

---

#### 3.3.3 07-3-隐患同步界面

**触发条件**: 用户在07-2界面单击确认保存

**API调用**:
- 调用API传递确认保存信息

**UI显示**:
- "隐患已同步，前往手机查看详情"
- "是否继续巡检其他区域？"

**手势交互**:
- 单击 → 继续巡检（返回 `HiddenRiskProbeActivity`）
- 双击 → 结束巡检（退出到 `HomeActivity`）

---

## 四、数据结构

### 4.1 隐患结果数据模型

```kotlin
data class HazardResult(
    val type: String,        // 隐患类型，如"消防通道堵塞"
    val description: String  // 隐患描述
)
```

### 4.2 推理快照数据（已有）

```java
// NativeInferenceStats.java (已有)
// 包含: detectionCount, inferenceTimeMs, errorMessage等
```

---

## 五、API接口定义（模拟接口）

### 5.1 图片上传+流式分析接口

**端点**: `POST /api/hazard/analyze`

**传输格式**: SSE (Server-Sent Events)

**请求参数**:
- 图片文件（multipart/form-data）或 Base64编码的图片数据

**响应格式** (SSE流):
```
data: {"text": "检测到消防通道存在"}
data: {"text": "杂物堆积，可能影响"}
data: {"text": "紧急疏散。"}
data: {"type": "消防通道堵塞"}
data: {"description": "消防通道存在杂物堆积，可能影响紧急疏散。"}
data: [DONE]
```

**结束标记**: `data: [DONE]`

---

### 5.2 确认保存接口

**端点**: `POST /api/hazard/confirm`

**请求参数**:
```json
{
    "type": "消防通道堵塞",
    "description": "消防通道存在杂物堆积，可能影响紧急疏散。",
    "imageUrl": "已上传的图片URL",
    "timestamp": "2026-04-02T10:30:00Z"
}
```

**响应**:
```json
{
    "success": true,
    "message": "隐患已同步"
}
```

---

## 六、手势交互规范

| 手势 | 含义 |
|-----|------|
| 单击（KEYCODE_CLICK） | 确认/继续/选择"是" |
| 双击（KEYCODE_DOUBLE_CLICK） | 返回/退出/选择"否" |
| 前滑（KEYCODE_FRONT） | 上一页/上一项（如需要） |
| 后滑（KEYCODE_BEHIND） | 下一页/下一项（如需要） |

---

## 七、实现计划

### Phase 1: 基础对接
1. 添加AI识患菜单入口
2. 修改无隐患界面交互逻辑
3. 修改发现隐患界面交互逻辑

### Phase 2: 深度分析流程
1. 创建 `HiddenRiskDetailActivity`
2. 实现SSE流式接收逻辑
3. 实现流式文字显示
4. 添加手势交互

### Phase 3: 同步流程
1. 创建 `HiddenRiskSyncActivity` 或复用状态
2. 实现确认保存API调用
3. 完善返回/继续逻辑

### Phase 4: 模拟接口
1. 创建模拟SSE服务端（用于测试）
2. 测试完整流程

---

## 八、关键文件清单

### 需要修改的文件
- `app/src/main/java/com/rokid/glass/HomeActivity.kt`
- `app/src/main/java/com/rokid/glass/annotation/MenuConfigType.java`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskProbeActivity.kt`
- `app/src/main/res/layout/activity_hidden_risk_probe.xml`

### 需要新建的文件
- `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskDetailActivity.kt`
- `app/src/main/res/layout/activity_hidden_risk_detail.xml`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HazardResult.kt`
- `app/src/main/java/com/rokid/glass/network/HazardApiService.kt` (网络请求)
- `app/src/main/java/com/rokid/glass/network/SseClient.kt` (SSE客户端)

---

## 九、注意事项

1. **流式显示**: SSE响应需要逐字/逐句显示，避免阻塞UI线程
2. **网络超时**: 设置合理的超时时间（建议30秒）
3. **错误处理**: API调用失败时显示错误提示，允许重试
4. **内存管理**: 图片上传后及时释放内存，避免OOM
5. **降级处理**: 网络不可用时，允许跳过深度分析，仅显示本地推理结果
