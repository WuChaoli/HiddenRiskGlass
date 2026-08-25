# V2 隐患结果链路统一设计

## 1. 背景

自动物品识别已经使用 `/ai/deep/v2`，并完成冻结底图、bbox、hazard 分页、焦点切换、
确认保存和 `/ai/sug_checks` 的结构化结果闭环。以下旧链路仍使用 SSE 文本协议和旧结果页：

- 检测页手动深度分析；
- `HazardRecordActivity` 隐患拍照；
- 环境检测命中后的深度分析；
- 无 `placeCode` 时降级到 `/ai/gm` 的分析请求。

本次将这些入口迁移到对应 V2 同步 JSON 接口，并共享当前结构化结果 UI。接口升级不改变
各入口原有的保存、后续建议和返回目标。

## 2. 目标

1. 手动深度分析和隐患拍照按场景路由到 `/ai/deep/v2` 或 `/ai/gm/v2`。
2. 环境检测详情迁移到 `/ai/general_deep/v2`。
3. 四类入口共享冻结底图、bbox、hazard 文字框、前后翻页、确认保存交互。
4. 无 detection 的响应不绘制或伪造 bbox，翻页时也不执行 bbox 动画。
5. 保持原业务后续：自动、手动、环境执行保存和 `/ai/sug_checks`；隐患拍照只保存。
6. 统一协议解析、导航和结果组件，避免在两个 Activity 中复制实现。
7. 保持 `localTriger` 完全离线边界，不因本次迁移新增网络请求。

## 3. 非目标

- 不修改 `/ai/auto`、`/ai/general` 的检测协议。
- 不修改 `/ai/sug_checks` 的请求和响应协议。
- 不展示、上传或以 `check_items` 替代 `/ai/sug_checks`。
- 不改变隐患上传协议和企业鉴权协议。
- 不删除旧 `/deep`、`/general_deep`、`/gm` 配置字段；本次只让目标业务入口停止使用它们。
- 不为无 detection 的接口生成全画幅或其他虚拟 bbox。
- 不改变 `DeviceGuideActivity` 的设备指引协议。

## 4. Apifox 契约依据

2026-08-25 使用已登录的 `apifox-cli` 从项目 `8556866`（“智能眼镜-4090-AI服务”）主分支
读取已发布接口，确认以下资源：

| Apifox endpoint ID | 名称 | 方法与路径 | 响应 type |
|---|---|---|---|
| `505838494` | 隐患分析 V2 | `POST /ai/deep/v2` | `deep_v2` |
| `505838497` | 环境隐患分析 V2 | `POST /ai/general_deep/v2` | `general_deep_v2` |
| `506177375` | 隐患分析-工贸 V2 | `POST /ai/gm/v2` | `gm_v2` |

服务端正确拼写为 `general_deep`，不是 `gerenal_deep`。

三者均接收 JSON，`image` 必填，`task_id` 和 `temp` 可选。`deep/v2` 与
`general_deep/v2` 支持 `scene`；`gm/v2` 不定义 `scene`。

三者返回共同顶层字段：

```text
code, msg, task_id, type, detections, hazards, check_items, time
```

共同 hazard 字段为：

```text
label_id, 隐患描述, 隐患等级, 主要依据, 整改建议, 隐患编号
```

协议差异：

- `/deep/v2` 经过 YOLO，返回 detection 的 `label`、`label_id`、`bbox`、`score`、`inter`。
- `/general_deep/v2` 不经过 YOLO，`detections` 固定为空，hazard 的 `label_id` 固定为 `others`。
- `/gm/v2` 不经过 YOLO，`detections` 固定为空，hazard 的 `label_id` 固定为 `others`。
- `check_items` 由客户端完整解析和保留，但本次不参与展示或保存。

## 5. 架构

采用“共享 V2 结果组件，入口保留各自业务策略”。

### 5.1 统一客户端

扩展当前结构化客户端，使其接收显式 endpoint 类型：

```text
DEEP_V2
GENERAL_DEEP_V2
GM_V2
```

客户端负责：

- 从 `InspectionConfigRepository` 获取对应 URL；
- 根据 endpoint 类型构造允许的请求字段；
- 执行同步 JSON 请求、取消和错误分类；
- 校验响应 `type` 与所选 endpoint 匹配；
- 输出共同的结构化响应模型。

禁止通过替换字符串临时拼接 V2 URL。

### 5.2 统一结果会话

每次成功响应归一化为 `StructuredHazardResultSession`。概念字段包括：

- 实际上传并用于展示的冻结 JPEG；
- 归一化 detections；
- 按确定顺序排列的 hazards；
- 当前页和当前关联 detection；
- 业务来源 `AUTO_ITEM`、`MANUAL`、`SCENE` 或 `HAZARD_RECORD`；
- 保存策略；
- 请求 ID、epoch 或生命周期代际。

结果会话不直接上传数据。入口 Activity 在确认时读取会话和保存策略，调用现有上传组件。

### 5.3 共享展示组件

复用现有 `DeepV2ResultOverlayView`、`HazardDetailOverlayView`、导航状态机和展示模型，
将其从“自动链路专用”提升为“结构化 V2 结果通用”。

- `AiInspectionActivity` 使用该组件呈现自动、手动和环境结果。
- `HazardRecordActivity` 使用同一组件呈现隐患拍照结果。
- 两个 Activity 保留自己的相机、返回目标和保存编排，不互相跳转。

## 6. 接口路由

| 入口 | 条件 | endpoint | scene |
|---|---|---|---|
| 自动物品识别 | `/auto` 面积门禁命中 | `/ai/deep/v2` | `placeCode` |
| 手动深度分析 | 有 `placeCode` | `/ai/deep/v2` | `placeCode` |
| 手动深度分析 | 无 `placeCode` | `/ai/gm/v2` | 不传 |
| 隐患拍照 | 有 `placeCode` | `/ai/deep/v2` | `placeCode` |
| 隐患拍照 | 无 `placeCode` | `/ai/gm/v2` | 不传 |
| 环境检测 | 有 `placeCode` | `/ai/general_deep/v2` | `placeCode` |
| 环境检测 | 无 `placeCode` | 不启动 | 不适用 |

配置层新增独立的 `aiGeneralDeepV2Api` 与 `aiGmV2Api`。既有 `aiDeepV2Api` 继续使用。
三个配置均可被变体覆盖，并沿用当前网络鉴权注入和网络访问策略。

`localTriger` 模式不得调用任一 V2 或 `/ai/sug_checks` 网络接口。

## 7. 图片与坐标

每次 V2 请求必须满足“请求图即结果底图”：

1. 从当前有效相机帧生成严格 3:4 对齐 JPEG。
2. 将该 JPEG 发送给选定 V2 endpoint。
3. 冻结同一字节内容作为结果页底图。
4. `/deep/v2` bbox 仅相对该上传图做投影。

手动分析与隐患拍照复用自动 `/deep/v2` 已验证的对齐编码和坐标映射，不能继续使用与结果页
裁切规则不同的缩略图路径。`general_deep/v2` 和 `gm/v2` 虽无 bbox，也展示其实际上传图片。

## 8. 归一化与展示顺序

继续沿用现有 V2 归一化规则：

- 清理空白和重复隐患编号；
- 将普通 hazard 按 `label_id` 关联 detection；
- `others` 作为无关联 hazard；
- 空编号 hazard 可展示，但不是有效上传项；
- `check_items` 保留但不进入页面或上传模型；
- 非法单条数据被跳过，不影响其他有效条目；
- 整体协议无法解析时请求失败。

每条 hazard 固定一页。一个 detection 关联多条 hazard 时，每页仍独立展示，但共享同一 bbox。

## 9. 页面与交互

结果页统一包含：

- 全屏冻结底图；
- 可选 bbox 图层；
- 居中黑色半透明 hazard 文字框；
- 保存确认交互。

### 9.1 有 bbox

- 仅绘制至少关联一个有效展示 hazard 的 detection。
- bbox 左上角显示 label 和当前 hazard 的隐患等级。
- 当前 bbox 通过整体放大表示选中，线宽不变化。
- hazard 背景覆盖其下方 bbox，避免视觉重叠。
- 翻到关联不同 detection 的 hazard 时执行 bbox 焦点切换动画。
- 同一 detection 内翻页只更新 hazard 内容和等级，不做无意义的 bbox 动画。

### 9.2 无 bbox

当 `detections` 为空或当前 hazard 无关联 detection 时：

- 完全隐藏 bbox，不生成虚拟框；
- 保留冻结底图；
- 前后滑只切换 hazard 文字框和页码；
- 不执行 bbox 放大、缩小或焦点动画。

### 9.3 hazard 文字框

- 每个 hazard 固定一页。
- 文字框随内容增加高度，达到最大高度后截断文字。
- 不把同一 hazard 拆到第二页。
- 只有一页时不显示页码；多页显示当前页和总页数。
- 前滑进入下一页，后滑进入上一页。
- 单击或语音确认打开保存确认。

### 9.4 返回目标

- 自动、手动和环境结果取消或处理完成后回到自动检测。
- 隐患拍照结果取消或保存完成后回到隐患拍照待机页。

## 10. 保存与 `/ai/sug_checks`

保持入口原有业务逻辑：

| 来源 | 上传有效 hazards | `/ai/sug_checks` |
|---|---|---|
| 自动物品识别 | 是 | 是 |
| 手动深度分析 | 是 | 是 |
| 环境检测 | 是 | 是 |
| 隐患拍照 | 是 | 否 |

- 上传所有编号非空的有效 hazards。
- `/ai/sug_checks` 使用归一化展示顺序中的第一条有效隐患编号。
- 上传和 `/ai/sug_checks` 沿用当前并行发起的行为。
- 第一条有效编号不存在时跳过 `/ai/sug_checks`。
- 没有任何可上传 hazard 时提示“无可保存隐患”，不发上传或 `/ai/sug_checks`。
- `/ai/sug_checks` 失败不撤销已经发起或完成的隐患上传。
- 隐患拍照不因迁移到 V2 而新增 `/ai/sug_checks`。

`/gm/v2` 不是独立业务入口：手动分析经它返回时仍执行保存和 `/ai/sug_checks`；隐患拍照
经它返回时仍只保存。

## 11. 并发与生命周期

- 自动 `/deep/v2` 保持单飞；请求期间 `/auto` 可继续刷新检测态实时框。
- 手动分析启动后暂停自动检测，结果退出后开启新 epoch 恢复检测。
- 自动物品详情和环境详情共享展示门禁，同一时刻最多呈现一个结构化结果。
- 隐患拍照维护独立请求代际；重新拍摄会取消并废弃上一请求。
- Activity 暂停、销毁或退出结果页时取消对应请求。
- 请求 ID、epoch 和生命周期代际不匹配的迟到响应必须丢弃。
- 保存提交期间禁用重复确认。

## 12. 异常处理

- 自动或环境请求失败：显示短提示并继续自动检测，不回退旧 SSE。
- 手动请求失败：显示短提示并恢复自动检测，不回退 `/deep` 或 `/gm`。
- 隐患拍照请求失败：留在拍照入口并提示失败，允许重新拍摄。
- 成功响应但没有可展示 hazard：按“未发现隐患”处理，不进入空结果页。
- 单条 hazard 异常：跳过该条并继续展示其他有效条目。
- 整体响应无效：按请求失败处理。
- 保存失败：保留当前结果并允许再次确认。
- `/ai/sug_checks` 失败：不取消隐患上传，按入口原有返回逻辑收尾。

## 13. 测试与验收

### 13.1 单元测试

- 三种 endpoint 的 URL、请求字段、`scene` 和响应 `type` 校验。
- 配置基础值、变体覆盖和合并测试。
- 有 bbox、空 bbox、多 hazard、`others`、空编号、重复编号和异常条目的归一化测试。
- 有 bbox 切换动画、同 bbox 翻页和无 bbox 无动画的导航状态测试。
- 四种来源的保存与 `/ai/sug_checks` 策略测试。
- 单飞、请求代际、取消、迟到响应和重复确认测试。
- `localTriger` 网络访问策略回归测试。

### 13.2 构建与真机

1. 运行 `:app:testStandardDebugUnitTest`。
2. 构建 standard debug APK。
3. 在 Rokid Glass 真机验证：
   - 自动 `/deep/v2` 有 bbox 结果；
   - 手动 `/deep/v2` 有 bbox 结果及 `/ai/sug_checks`；
   - 环境 `/general_deep/v2` 无 bbox 多页结果；
   - 隐患拍照 `/deep/v2` 只保存；
   - 无 `placeCode` 手动 `/gm/v2` 保存及 `/ai/sug_checks`；
   - 无 `placeCode` 隐患拍照 `/gm/v2` 只保存；
   - 取消、失败、空结果和重复确认。

验收日志应能区分 endpoint 类型、请求 ID、来源入口、是否有 bbox、hazard 数量、保存策略和
`/ai/sug_checks` 是否发起，但不得记录 Base64 图片、授权令牌或完整敏感响应。

## 14. 迁移完成条件

- 四类目标入口均不再调用旧 `/deep`、`/general_deep` 或 `/gm`。
- 三个 V2 endpoint 都从配置读取并通过协议测试。
- 所有结构化结果共享同一套展示和导航行为。
- 无 bbox 接口不显示 bbox 且不触发 bbox 动画。
- 各入口保存和 `/ai/sug_checks` 行为与迁移前一致。
- standard 单元测试、debug 构建和真机关键路径验证完成。
