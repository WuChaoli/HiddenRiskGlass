# 自动 `/deep/v2` 结构化隐患结果设计

## 1. 背景

当前 `AiInspectionActivity` 的自动在线链路已经完成以下闭环：

```text
1200 × 1600 完整画幅
  → /ai/auto
  → bbox 投影到 480 × 640
  → 任一可见 bbox 面积达到屏幕 1/8
  → 按固定 1m 标定窗口裁成严格 3:4 图片
  → /ai/deep SSE
  → 解析为 ResolvedHazardContent
  → STREAM_RESPONSE 文字结果页
```

旧 `/ai/deep` 返回流式文本，页面通过 `AiArSseService`、`AiArEventAggregator` 和
`AiArHazardDetailParser` 累积并解析结果。结果页当前显示缩略图和模拟流式文字；用户确认后，
页面上传全部有效隐患，并用第一条有效隐患编号请求 `/ai/sug_checks`。

服务端新增 `/ai/deep/v2`。该接口同步返回 detection、bbox、hazard、check item 等结构化字段，
普通 hazard 通过 `label_id` 与 detection 关联，`label_id = "others"` 表示与任何 detection
无关的全局隐患。本次需要让自动面积门禁触发的新链路使用 V2 协议，并以冻结的 3:4 图片、
bbox 和结构化详情替换原自动流式文字呈现。

## 2. 目标

本次改造实现：

1. 仅将 `/auto` 面积门禁自动触发的深度分析迁移到 `/ai/deep/v2`。
2. 同步解析 V2 结构化响应，并稳定关联 detection 与多条 hazard。
3. 使用实际发送给 V2 的 3:4 图片作为结果页全屏底图。
4. 只绘制存在关联 hazard 的 bbox，并在框内左上角显示 detection label 和最高隐患等级。
5. 提供失焦、bbox、详情分页、`others` 和保存弹窗组成的确定性状态机。
6. 复用现有隐患上传与 `/ai/sug_checks` 后续链路。
7. 保持 `/deep/v2` 请求期间 `/auto` 持续运行和刷新实时 bbox，但禁止并发触发第二个 V2 请求。
8. 对协议异常、无有效隐患、页面生命周期和迟到回调提供明确保护。

## 3. 非目标

本次不修改：

- 检测态手动单击触发的 `/ai/deep`。
- `HazardRecordActivity` 隐患拍照录入使用的旧深度分析协议。
- `DeviceGuideActivity` 及环境检测相关旧接口。
- `AiArSseService` 的 SSE 聚合、旧详情解析和旧结果页面契约。
- `/ai/auto` 的请求、返回协议、完整画幅尺寸和面积 `1/8` 门禁。
- 3:4 对齐裁切和固定 1m 标定算法。
- `/ai/sug_checks` 请求协议。
- `check_items` 的业务展示、上传或替代 `/ai/sug_checks` 的行为。
- `localTriger` 完全离线模式的网络边界和本地结果呈现。

## 4. 已确认的产品决策

### 4.1 接口范围

- 新增独立 `aiDeepV2Api` 配置。
- 自动面积门禁调用 `/ai/deep/v2`。
- 旧 `aiDeepApi` 和所有旧 SSE 调用点保持原样。
- V2 失败时不回退旧 `/ai/deep`。

### 4.2 hazard 关联与展示

- 一个 `label_id` 可以关联多条 hazard。
- bbox 标签第二行显示关联 hazards 中的最高隐患等级。
- 普通 `label_id` 没有关联 hazard 时，不显示 bbox 和标签。
- 普通 hazard 找不到对应 detection 时不展示。
- `others` 固定排在所有 bbox 后，不选中任何 bbox。
- `check_items` 完整解析并保留，但本次不展示、不上传。

### 4.3 输入与循环

- 眼镜统一输入只有前滑 `FRONT` 和后滑 `BEHIND`，没有独立上下滑事件。
- 前滑、语音“下一个”执行正向导航。
- 后滑、语音“上一个”执行反向导航。
- bbox 或 `others` 聚焦时，单击、语音“确认”和“继续”等同前滑。
- 每次进入新的 bbox 或 `others`，无论进入方向，文字框都从第 1 页打开。
- 只有停留在同一目标内时，前后滑才按当前页码翻页。
- 失焦状态下单击、语音“确认”或“继续”打开保存确认弹窗。
- 保存弹窗默认选中“确认”。

### 4.4 页面布局

- V2 请求图片铺满 `480 × 640` 结果页底层。
- 结果页隐藏右上角检查指引和原底部提示文字。
- Glass 状态栏保留，并覆盖在图片最底部。
- hazard 详情卡采用固定底部方案，不动态避让 bbox。
- 详情卡允许覆盖底图和下方 bbox。

## 5. `/ai/deep/v2` 协议

### 5.1 请求

Apifox 项目“智能眼镜-4090-AI服务”中的已发布接口为：

```text
POST /ai/deep/v2
Content-Type: application/json
```

请求字段：

```json
{
  "task_id": "运行时唯一任务 ID",
  "scene": "当前 placeCode",
  "temp": 0.3,
  "image": "3:4 JPEG Base64"
}
```

实现不发送旧接口的 `stream` 和 `text` 字段。`scene` 沿用当前在线 item lane 的
`placeCode`；自动 `/auto` 本身已经要求有效 `placeCode`，V2 不走无场景 `/ai/gm` 降级。

### 5.2 响应

原始模型保留：

```text
DeepV2Response
  code
  msg
  taskId
  type
  detections
  hazards
  checkItems
  time

DeepV2Detection
  label
  labelId
  bbox[left, top, right, bottom]
  score
  inter

DeepV2Hazard
  labelId
  description
  level
  lawBasis
  advice
  hazardCode
```

字段映射：

| JSON 字段 | Kotlin 语义 |
|---|---|
| `label_id` | `labelId` |
| `隐患描述` | `description` |
| `隐患等级` | `level` |
| `主要依据` | `lawBasis` |
| `整改建议` | `advice` |
| `隐患编号` | `hazardCode` |
| `check_items` | `checkItems` |

Apifox Schema 将 `detections[].inter` 定义为 Boolean，但示例响应使用数值 `0`。
`DeepV2Protocol` 必须兼容 Boolean 和 Number，不得让该未使用字段导致整批结果解析失败。

协议成功条件为：

- HTTP 2xx；
- JSON 合法；
- `code == 0`；
- `type == "deep_v2"`。

## 6. 组件设计

采用协议、归一化、状态机、渲染和 Activity 编排分层。

### 6.1 `DeepV2Protocol`

纯 Kotlin 协议模块，职责：

- 构建不含 `stream/text` 的请求 JSON。
- 解析同步响应。
- 校验 `code`、`type` 和数组基本形态。
- 兼容 `inter` Boolean/Number。
- 保留 `check_items` 原始结构。
- 输出原始协议模型，不执行 UI 排序和业务过滤。

### 6.2 `DeepV2Client`

职责：

- 从 `InspectionConfigRepository.get().network.aiDeepV2Api` 读取端点和超时。
- 使用共享 `HttpClientProvider` 客户端，继续受 `InspectionNetworkAccessPolicy` 约束。
- 对冻结的 JPEG 做 Base64 编码并发起普通 OkHttp JSON 请求。
- 同一 Client 请求返回可取消 Handle。
- 将成功原始模型或标准化失败信息回调到主线程。
- 不做自动重试，不回退旧 SSE。

### 6.3 `DeepV2ResultNormalizer`

纯 Kotlin 业务模块，输入原始响应和冻结图片元信息，输出可直接驱动页面的
`DeepV2Presentation`。

```text
DeepV2Presentation
  imageBytes
  imageWidth / imageHeight
  groups: List<DeepV2HazardGroup>
  others: DeepV2GlobalHazards?
  uploadHazards
  suggestionHazardCode
  checkItems
```

```text
DeepV2HazardGroup
  labelId
  label
  bbox
  score
  hazards
  highestLevel
  sourceOrder
```

该模块是 label 关联、异常去重、等级计算和稳定排序的唯一权威。Activity 和 View
不得重新实现这些规则。

### 6.4 `DeepV2PresentationStateMachine`

纯 Kotlin 状态机，职责：

- 管理失焦、目标索引、文字页码和保存弹窗选择。
- 接收 `forward/backward/confirm/cancel` 语义动作。
- 保证跨目标总是从第 1 页开始。
- 输出不可变 Snapshot，供 View 渲染和 Activity 决定保存动作。
- 不持有 Android View、Bitmap、网络 Client 或 Activity 引用。

### 6.5 结果页 View 层

`activity_ai_inspection.xml` 增加独立 `layoutDeepV2Result`，叠层顺序为：

```text
ImageView：冻结的 3:4 请求图片
  → DeepV2ResultOverlayView：bbox 与两行标签
  → 固定底部 hazard 详情卡
  → 保存确认弹窗层
  → GlassStatusBar
```

`DeepV2ResultOverlayView` 只绘制 bbox、标签和选择动画，不做协议解析、关联、分页或保存。
详情卡使用普通 View/TextView，由状态机 Snapshot 驱动，便于自动换行和可访问性测试。

### 6.6 `AiInspectionActivity`

Activity 继续负责编排：

- 从现有面积门禁创建唯一 V2 请求。
- 保存请求 ID、epoch 和冻结 JPEG。
- 保持 `/auto` 循环运行，但在 V2 请求活跃时禁止第二次触发。
- 接受成功结果并调用 Normalizer。
- 有有效结果时停止自动推理并进入结构化结果页。
- 无结果或失败时释放 V2 触发锁，保持 `/auto` 运行。
- 将眼镜输入转换为状态机语义动作。
- 将确认结果转换为现有 `ResolvedHazardContent`，复用上传和 `/ai/sug_checks`。
- 在生命周期结束时取消请求、释放 Bitmap 和清空页面状态。

## 7. 数据归一化规则

规则按以下固定顺序执行。

### 7.1 detection 校验

- `label` 和 `label_id` 去除首尾空白。
- bbox 必须恰好包含四个有限数值。
- 坐标按 V2 请求图片范围裁剪。
- 裁剪后 `right <= left` 或 `bottom <= top` 的 detection 丢弃。
- `score` 非有限数值时按最低优先级处理，不让整批解析失败。

### 7.2 `label_id` 关联

- 普通 hazard 只关联相同 `label_id` 的 detection。
- detection 没有任何关联 hazard 时，整个框不进入页面模型。
- 普通 hazard 找不到 detection 时忽略。
- `label_id == "others"` 的 hazards 单独收集，不要求 detection。
- 异常出现重复 `label_id` detection 时，保留 score 更高者；score 相同保留 bbox 面积更大者；
  仍相同时保留接口顺序更前者。

### 7.3 隐患编号异常去重

正常契约下，一个隐患编号只对应一条 hazard。若异常出现相同非空隐患编号多次：

1. 优先保留关联 detection score 更高者；
2. score 相同时保留 bbox 面积更大者；
3. 仍相同时保留接口顺序更前者；
4. `others` 没有 score 和 bbox，在与普通 detection 竞争时排后。

隐患编号为空的 hazard 可以展示，但不进入上传项，也不能成为 `/ai/sug_checks` 参数。

### 7.4 等级优先级

最高等级按以下顺序计算：

```text
重大隐患 > 重点问题 > 一般隐患 > 未知或空值
```

兼容现有代码：

| 代码 | 展示值 |
|---|---|
| `2` | `重大隐患` |
| `3` | `重点问题` |
| `1` | `一般隐患` |

未知新值保留原文展示，但排序低于所有已知等级。

### 7.5 页面与保存顺序

- bbox groups 按 `top → left → detection 原始顺序` 排列。
- 同一 group 内 hazards 保持接口原始顺序。
- `others` 固定排在所有 bbox groups 后。
- 上传顺序与页面目标顺序一致。
- `/ai/sug_checks` 使用上述顺序中的第一条有效隐患编号。
- groups 和 `others` 均为空时，结果语义为“未发现隐患”。

## 8. 坐标与渲染

### 8.1 坐标映射

V2 bbox 直接位于实际发送的 3:4 图片坐标系。该图片与 `480 × 640` 结果页宽高比相同，
不再应用 `/auto` 完整画幅标定矩阵：

```text
screenLeft   = bboxLeft   / imageWidth  * overlayWidth
screenTop    = bboxTop    / imageHeight * overlayHeight
screenRight  = bboxRight  / imageWidth  * overlayWidth
screenBottom = bboxBottom / imageHeight * overlayHeight
```

映射结果裁剪到 Overlay 边界。结果页图片使用全屏铺满；由于输入已经是严格 3:4，禁止额外
中心裁剪或二次标定。

### 8.2 失焦状态

- 只显示存在关联 hazard 的 bbox。
- bbox 使用绿色 `1dp` 细边。
- 不显示 score、`label_id`。
- 框内左上角标签分两行：第一行 detection `label`，第二行最高隐患等级。
- hazard 详情卡隐藏。

### 8.3 bbox 选中状态

- 选中框沿原中心将宽高各扩大到 `110%`。
- 扩大后的坐标裁剪到屏幕边界。
- 边线从 `1dp` 变为 `3dp`。
- 其他 bbox 保持细边和原尺寸。
- 标签跟随扩大后的框内左上角，不显示 score 和 `label_id`。

### 8.4 固定底部详情卡

- 水平边距初始为 `8dp`。
- 固定高度初始为 `132dp`，位于 Glass 状态栏上方。
- 具体尺寸集中在 dimen resource；真机若只需调整尺寸，不改变状态机和分页语义。
- 文本自动换行，页码显示在卡片右下角。
- 每条 hazard 展示：隐患描述、隐患等级、主要依据、整改建议。
- 不展示隐患编号。
- 同一 bbox 的多条 hazards 按归一化后的顺序组成连续内容，条目之间使用明确分隔。
- 页面按实际 TextView/StaticLayout 可见行数切分，不在 View 中维护像素滚动位置。

### 8.5 `others`

- 所有 bbox 回到失焦细边状态，无框被选中。
- 固定底部详情卡标题显示“全局隐患”和 `others` 的最高隐患等级。
- `others` 多条 hazards 使用与普通 bbox 相同的字段、换行和分页规则。

### 8.6 动画

跨目标切换顺序：

1. 旧详情卡淡出；
2. 旧 bbox 缩小变细，同时新 bbox 沿中心放大变粗，建议持续约 `220ms`；
3. bbox 动画结束后，新详情卡淡入。

从或切到 `others` 时，只有相关 bbox 取消/建立选中动画；`others` 本身不创建虚拟框。
同一目标内翻文字页时不重复 bbox 动画，只替换文本并更新页码。

## 9. 焦点与分页状态机

目标序列：

```text
DEFOCUSED
  ↔ BBOX_0
  ↔ BBOX_1
  ↔ ...
  ↔ BBOX_LAST
  ↔ OTHERS（存在时）
  ↔ DEFOCUSED
```

页面状态至少包括：

```text
DeepV2PresentationState
  Defocused
  Focused(targetIndex, pageIndex)
  SaveDialog(selectedAction)
  Submitting
```

### 9.1 正向动作

正向触发：前滑、语音“下一个”，以及聚焦状态下的单击/“确认”/“继续”。

- `Defocused`：进入第一个目标第 1 页。
- `Focused` 且未到当前目标末页：进入下一文字页。
- `Focused` 且已到末页：进入下一个目标第 1 页。
- 最后目标末页继续正向：回到 `Defocused`。

### 9.2 反向动作

反向触发：后滑、语音“上一个”。

- `Defocused`：进入最后一个目标第 1 页。
- `Focused` 且当前页大于第 1 页：进入上一文字页。
- `Focused` 且位于第 1 页：进入上一个目标第 1 页。
- 第一个目标第 1 页继续反向：回到 `Defocused`。

跨目标不继承旧页码，也不因反向进入而打开末页。

### 9.3 保存弹窗

`Defocused` 状态下单击或语音“确认/继续”打开弹窗：

- 文案：“是否保存本次隐患？”
- 按钮：“确认”“取消”。
- 默认选择：“确认”。
- 前滑/后滑切换按钮。
- 单击执行当前选择。
- 语音“确认”直接确认，语音“取消”直接取消。

确认后进入 `Submitting`，交给现有上传和 `/ai/sug_checks`；取消后清空本轮 V2 结果并恢复
`/auto` 检测。

## 10. 保存与 `/ai/sug_checks`

`DeepV2Presentation` 转换为现有 `ResolvedHazardContent`：

- `jpegBytes` 使用结果页展示的冻结 3:4 图片。
- 普通 bbox hazard 的 `displayTitle` 使用 detection `label`。
- `others` 的 `displayTitle` 使用“全局隐患”。
- V2 `整改建议` 同时映射到现有可上传建议字段。
- `remoteSaveAllowed` 沿用在线结果权限。

确认保存后：

1. 使用现有 `LocalHazardUploadItemBuilder` 跳过空编号并再次执行上传边界去重。
2. 上传全部有效编号的 hazards。
3. 使用归一化顺序中第一条有效隐患编号请求 `/ai/sug_checks`。
4. 沿用当前上传与 `/ai/sug_checks` 并行执行、建议页展示和失败处理。

V2 `check_items` 不参与上述流程。

## 11. 并发与生命周期

### 11.1 请求期间继续 `/auto`

面积门禁触发 V2 后：

- 设置唯一 `activeDeepV2Request`。
- `/auto` 继续按原节奏请求并刷新检测态 bbox。
- 只要 `activeDeepV2Request` 非空，任何后续 `/auto` 响应都不得再次触发 V2。
- V2 请求保存自身冻结 JPEG，不使用返回时最新的 `/auto` 帧。

### 11.2 V2 终止行为

- 成功且存在有效隐患：校验 request ID 和 epoch，停止自动推理，进入 V2 结果页。
- 成功但无有效隐患：记录“未发现隐患”，释放 V2 锁；`/auto` 继续运行。
- 失败：提示“深度分析失败，继续检测”，释放 V2 锁；`/auto` 继续运行。

### 11.3 迟到回调

每个请求同时绑定：

- 单调递增 `deepRequestId`；
- 当前 `autoInferenceEpoch`；
- 当前 Activity 生命周期状态。

任一不匹配时丢弃回调。`onPause/onDestroy`、离开页面、重新启动检测 epoch 时取消 Handle，
清空冻结图片引用，禁止迟到结果重新打开页面。

结果页 Bitmap 只在有效结果到达后解码；离开结果页立即从 ImageView 移除并回收，避免长期持有
`1200 × 1600` ARGB Bitmap。

## 12. 错误处理

以下错误不进入结果页：

- 网络失败或超时；
- HTTP 非 2xx；
- `code != 0`；
- `type != "deep_v2"`；
- 非法 JSON；
- 归一化后无 bbox groups 且无 `others`。

处理原则：

- 应用不崩溃；
- 不调用旧 `/ai/deep`；
- 释放当前 V2 触发锁；
- 保持正在运行的 `/auto`；
- 写入 endpoint、task ID、request ID、epoch、耗时和失败分类日志；
- 页面使用非阻塞短暂提示，不切换到错误页。

单条 detection 或 hazard 的局部字段异常优先丢弃或降级该条，不让一条坏数据否定整个合法响应。

## 13. 配置与预期文件影响

预计涉及：

- `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
  - 新增 `NetworkConfig.aiDeepV2Api` 和 override 字段。
- `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
  - 合并 V2 配置。
- `app/src/main/assets/inspection_config.base.jsonc`
  - 默认 V2 URL 和同步请求超时。
- 各实际覆盖 `aiDeepApi` 的 flavor 配置
  - 仅在其自动在线链路需要不同 V2 URL 时显式覆盖。
- `DeepV2Protocol.kt`
- `DeepV2Client.kt`
- `DeepV2ResultNormalizer.kt`
- `DeepV2PresentationStateMachine.kt`
- `DeepV2ResultOverlayView.kt`
- `AiInspectionActivity.kt`
- `activity_ai_inspection.xml`
- 相关 string、dimen、drawable 与单元测试。

旧 `AiArSseService.requestDeepAnalysis()`、`DetectionRouteContext.deepAnalysisEndpoint()` 和其他旧
`/deep` 调用点不改为 V2。

## 14. 测试设计

### 14.1 协议测试

- 正常完整响应。
- `inter` 为 Boolean、`0/1` 和缺失时的兼容行为。
- `code != 0`、错误 type、非法 JSON。
- bbox 数量不是 4、NaN/Infinity、反向坐标和越界坐标。
- 请求 JSON 包含 `task_id/scene/temp/image`，不包含 `stream/text`。

### 14.2 归一化测试

- 一个 `label_id` 关联多条 hazards。
- bbox 标签选择最高隐患等级。
- detection 无 hazard 时不显示。
- 普通 hazard 无 detection 时忽略。
- `others` 无 detection 仍展示且排末尾。
- 重复 `label_id` detection 按 score、面积、原始顺序选择。
- 重复隐患编号按 score、bbox 面积、原始顺序选择。
- `others` 在重复编号竞争中排普通 detection 后。
- 空隐患编号可展示但不可上传和请求 `/ai/sug_checks`。
- groups 按 top、left 排序。
- 归一化为空时返回“未发现隐患”。

### 14.3 状态机测试

- 正向和反向从失焦进入时均为目标第 1 页。
- 同目标内前后分页。
- 首末页跨目标后重置为第 1 页。
- bbox、`others`、失焦完整循环。
- 无 `others` 时直接从末尾 bbox 回失焦。
- 聚焦时单击/“确认/继续”等同正向。
- 失焦时确认打开弹窗。
- 弹窗默认选择确认，按钮切换和语音直达行为正确。

### 14.4 几何与 View 测试

- V2 图片 bbox 映射到 `480 × 640`。
- 越界裁剪。
- 选中框中心不变，宽高扩大 `10%`。
- 放大后屏幕边界裁剪。
- label 两行内容不包含 score 和 `label_id`。
- 固定底部卡片分页不切断布局状态，页码准确。

### 14.5 Activity 集成测试

- V2 活跃时 `/auto` 继续运行和刷新框。
- V2 活跃时第二次面积命中不会发起新 V2。
- V2 成功有隐患后停止 `/auto` 并使用请求冻结图片。
- V2 无隐患或失败后 `/auto` 保持运行，触发锁释放。
- epoch 变化和 Activity 销毁后迟到回调被丢弃。
- 确认上传全部有效 hazards，`/ai/sug_checks` 只使用第一编号。
- 取消不保存并恢复检测。

### 14.6 回归与真机验证

至少执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
```

Rokid 真机验证：

1. `/deep/v2` 等待期间 `/auto` 框持续更新，但日志中只有一个活跃 V2。
2. 结果页底图与 V2 bbox 坐标一致。
3. 右上角检查指引和原底部提示隐藏，状态栏保留。
4. 多 bbox、多 hazard、最高等级、`others` 正确。
5. 正反向导航、新目标第 1 页、单击正向、语音绑定正确。
6. bbox 缩放/粗细和详情卡先隐藏再显示的动画顺序正确。
7. 保存弹窗默认确认；确认和取消分支正确。
8. 手动 `/deep`、隐患拍照和设备指引仍使用旧 SSE。

若当前 flavor 的 `businessMock` 禁止真实上传，真机以请求门禁和日志验证保存分支；真实上传只在
明确授权且具备有效企业上下文的环境验收。

## 15. 风险与控制

| 风险 | 控制 |
|---|---|
| V2 延迟期间 `/auto` 继续返回导致画面变化 | V2 结果绑定自己的冻结 JPEG；只允许一个 V2 活跃请求 |
| V2 bbox 与页面图片坐标不一致 | 只使用实际发送图片；禁止二次标定和中心裁剪；几何单测 + 真机 |
| `inter` Schema 与示例类型冲突 | 自定义容错解析 Boolean/Number |
| `AiInspectionActivity` 继续膨胀 | 协议、Client、Normalizer、状态机和 Overlay 独立 |
| 多 hazard 导致详情卡内容不可读 | 固定卡片、按实际行分页、新目标从第 1 页开始 |
| 重复编号造成重复上传 | Normalizer 异常去重 + 现有上传 Builder 二次保护 |
| 迟到回调重新打开旧结果 | request ID、epoch、生命周期三重校验 |
| 新端点误影响手动流程 | 独立 `aiDeepV2Api` 和独立 Client；旧 SSE 回归测试 |

## 16. 完成定义

只有同时满足以下条件，才能认为改造完成：

- 自动面积门禁只调用 `/ai/deep/v2`，且同一时刻最多一个请求。
- V2 活跃期间 `/auto` 继续打框，不再触发第二个 V2。
- 结构化关联、过滤、异常去重、等级和排序测试通过。
- 页面满足全屏冻结图片、有效 bbox、两行标签、固定底部详情卡、`others` 和状态栏要求。
- 状态机满足正反向进入新目标均从第 1 页、失焦保存弹窗和默认确认要求。
- 确认复用现有上传和 `/ai/sug_checks`，取消恢复 `/auto`。
- V2 失败、无隐患和生命周期竞态不会崩溃或错误跳页。
- 所有旧 `/deep` 调用点和 `localTriger` 离线边界保持不变。
- standard 单元测试、Debug 构建和 Rokid 真机验收完成，并留下可核验日志。
