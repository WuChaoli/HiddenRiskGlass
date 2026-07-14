## Why

隐患识别页的本地 NCNN 目前只要返回任意标签就会触发在线详情，无法表达“主对象存在但配套保护装置缺失”这类组合隐患；断网时 `/ai/deep` 失败后也没有可展示的本地详情。需要增加可离线工作的组合规则判断，并让在线与离线结果复用同一套流式展示体验。

## What Changes

- 本地 NCNN 结果按四类“主对象存在、保护装置缺失”规则判断隐患，而不是用非空标签直接判定。
- 有网时，本地规则只负责触发，最终隐患详情继续由 `/ai/deep` 返回。
- 无网或 `/ai/deep` 发生明确网络连接失败时，从本地 `info.json` 解析隐患知识并生成与 `/ai/deep` 一致的结构化详情文本。
- 离线详情复用现有 `ResolvedHazardContent` 和模拟流式展示，不新增独立页面。
- 离线产生的隐患不调用 `pushHidDanger`，网络恢复后也不补传该条隐患。
- 网络恢复后允许正常调用 `pushHidDangerEnd` 结束巡检。
- “接口装置”固定映射为 NCNN 的“栓口（hydrant_nozzle）”标签。

## Capabilities

### New Capabilities

- `offline-local-hazard-judgment`: 定义本地组合规则、在线/离线详情分流、本地详情格式、流式展示和离线上传边界。

### Modified Capabilities

无。

## Impact

- 隐患识别：`LocalTriggerDetectionService`、`OnlineHazardDetectionService`、`AiInspectionActivity`。
- 本地知识与详情：`info.json`、`ResolvedHazardContent`、`AiArHazardDetailParser` 兼容格式、`SimulatedStreamTextChunker`。
- 网络状态：复用 `SystemStateUtils.isNetworkAvailable()`。
- 上传边界：`submitLocalHazardAndShowAdvice()` 必须识别离线结果来源；结束巡检现有后台上传链路保持可用。
- 测试：增加纯 JVM 组合规则、详情解析、分流和上传门禁测试，并执行对应 flavor 单测、构建与真机验证。
