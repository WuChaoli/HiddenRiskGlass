# 强制本地隐患详情分析开关设计

## 目标

为隐患识别页增加运行时配置开关 `forceLocalHazardDetailAnalysis`。开关默认开启；开启后，本地 NCNN 标签组合规则命中隐患时，无论当前是否联网，都使用本地 `info.json` 生成隐患详情并按现有模拟流式格式展示，不调用 `/ai/deep`。

## 作用范围

- 仅影响隐患识别页中 `LOCAL_TRIGGER` 本地标签组合规则命中后的详情分析。
- 不改变本地 NCNN 四类隐患组合规则及标签匹配逻辑。
- 不影响手动深度分析、隐患拍照和设备指引链路，因为这些链路没有本地标签组合输入。
- 不改变开关关闭时的现有在线优先、离线本地降级策略。

## 配置设计

在 `AiInspectionConfig` 和 `AiInspectionConfigOverride` 中增加布尔字段：

```text
forceLocalHazardDetailAnalysis = true
```

基础配置 `inspection_config.base.jsonc` 显式配置为 `true`，并由 `InspectionConfigRepository` 的现有覆盖合并机制支持 flavor 级覆盖。代码默认值同样为 `true`，确保配置缺失或解析失败时仍保持默认开启。

## 路由逻辑

`LocalHazardDetailRouteDecider` 接收开关状态、网络状态和本地知识解析结果是否可用，输出详情路由：

| 开关 | 网络 | 本地详情可用 | 路由 |
|---|---|---|---|
| 开启 | 任意 | 是 | 本地 |
| 开启 | 任意 | 否 | 不可用，不调用 `/ai/deep` |
| 关闭 | 有网 | 任意 | `/ai/deep` |
| 关闭 | 无网 | 是 | 本地 |
| 关闭 | 无网 | 否 | 不可用 |

开关开启时，本地详情不可用也不回退 `/ai/deep`，从而保证“强制本地”的语义和网络隔离测试的确定性。

## 展示与上传

本地详情继续复用 `LocalHazardDetailResolver`、`ResolvedHazardContent` 和现有模拟流式展示链路，使前端效果与在线详情一致。

强制本地得到的详情沿用本地来源标记，因此即使设备联网，也不调用 `pushHidDanger` 保存接口。结束接口仍遵守现有独立的网络策略，本次不修改。

## 测试与验收

1. 配置测试验证代码默认值为开启、JSONC 可显式关闭、overlay 可覆盖。
2. 路由单元测试验证开关开启时有网和无网均走本地，并验证本地知识缺失时不调用远端。
3. 路由单元测试验证开关关闭时恢复有网远端、无网本地行为。
4. 运行 `:app:testStandardDebugUnitTest`，确认相关测试和完整 standard 单元测试通过。
5. 运行 standard debug 构建，确认 Kotlin、资源和配置编译通过。

## 非目标

- 不新增设置页面或用户交互控件。
- 不修改 `/ai/deep` 服务实现。
- 不修改四类本地隐患规则、`info.json` 内容或流式文本格式。
- 不重构其他在线/本地推理调度代码。
