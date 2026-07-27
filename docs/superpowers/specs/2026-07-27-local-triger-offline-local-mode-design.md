# localTriger 完全离线本地模式设计

## 背景

`localTriger` 当前只通过 `autoDetectProvider=LOCAL_TRIGGER` 将 ITEM 自动检测替换为本地 NCNN 触发器。企业扫码、Wi-Fi 入口门禁、自动更新、手动深度分析、设备指引和若干上传链路仍可能联网；同时 `LocalTriggerDetectionService` 会在 `placeCode` 为空时直接返回无隐患，与跳过企业扫码的目标冲突。

## 目标

`localTriger` 必须在设备无网络时完整进入巡查、执行本地 NCNN 识别、按四组规则生成 `info.json` 本地详情并结束巡查。即使设备实际连接 Wi-Fi，该变体也不得发送巡查业务 HTTP/SSE 请求。

## 非目标

- 不改变 `standard`、`demo`、`shengting` 等其他变体行为。
- 不替换 NCNN param/bin，不修改本地四组规则和 `info.json` 映射。
- 不承诺阻断 Rokid SDK 内部不可控流量；验收范围是应用自有业务网络请求。
- 不为 `/ai/device`、手动 `/ai/deep` 或隐患录入在线分析新增本地模型替代品。

## 配置与策略

在 `FeatureFlagsConfig` 增加 `networkAccessMode`，枚举值为：

- `ONLINE`：保持现有业务联网行为。
- `OFFLINE_LOCAL`：跳过企业巡检链路和 Wi-Fi 门禁，关闭自动更新，禁止应用业务 HTTP/SSE 请求。

`inspection_config.localTriger.jsonc` 显式设置：

```jsonc
{
  "featureFlags": {
    "enableEnterpriseInspectionFlow": false,
    "networkAccessMode": "OFFLINE_LOCAL"
  },
  "aiInspection": {
    "autoInferenceMode": "LOCAL_ONLY",
    "autoHazardRoutingMode": "LOCAL_ONLY",
    "autoDetectProvider": "LOCAL_TRIGGER",
    "enableOnlineSceneHazardDetection": false,
    "forceOnlineDetailForLocalHazard": false,
    "forceLocalHazardDetailAnalysis": true
  }
}
```

`InspectionFeatureFlags` 作为业务层唯一策略入口，提供企业链路、业务联网、Wi-Fi 入口门禁和本地模式判断。变体判断不得散落使用 `BuildConfig.FLAVOR`。

## 页面流

离线模式入口流为：

```text
MainMenuActivity
  -> InspectionLoadingActivity
  -> AiInspectionMenuActivity
  -> AiInspectionActivity
```

- `EntryGuardCoordinator` 将 Wi-Fi 阶段直接标记完成，并跳过自动更新。
- `AiInspectionMenuActivity` 不检查企业 QR/企业详情，但仍检查 `InspectionModelLoadPolicy`。
- `EnterpriseQrScanActivity` 的离线兜底跳转改为加载页，避免绕过生产模型加载不变量。
- “检查扫码”语音入口不注册。

## 本地识别

- `LocalTriggerDetectionService` 不再用 `placeCode` 决定是否运行；空企业上下文仍解码图片、运行 NCNN、执行 `LocalHazardRuleEvaluator`。
- `autoHazardRoutingMode=LOCAL_ONLY` 使自动链路不根据真实网络状态启动远端主链路，也不运行网络恢复探针。
- `enableOnlineSceneHazardDetection=false` 显式关闭 scene lane。
- `forceLocalHazardDetailAnalysis=true` 和 `forceOnlineDetailForLocalHazard=false` 确保详情只来自 `info.json`。

## 网络阻断

增加集中式 `InspectionNetworkAccessPolicy`：

- `isBusinessNetworkAllowed()` 从 `InspectionConfigRepository` 读取 `networkAccessMode`。
- `requireBusinessNetworkAllowed(operation)` 在禁用时返回稳定错误 `offline_local_blocked` 并记录操作名。
- `HttpClientProvider.inspectionClient` 和 `sseClient` 安装拦截器作为最终防线，离线模式下不建立连接。

更新客户端使用独立 OkHttpClient，故 `EntryGuardCoordinator` 必须同时跳过自动更新；主菜单的手动更新入口在离线模式下禁用并给出本地模式提示。

## 联网功能收口

离线模式下：

- 禁用 `AiInspectionActivity` 手动深度分析动作。
- `AiInspectionMenuActivity` 隐藏设备指引和隐患录入卡片，仅保留隐患分析。
- `pushHidDanger`、`pushHidDangerEnd`、企业对象查询、整改建议和拍照上传均由既有企业链路门禁及网络总闸双重阻断。
- 结束巡查只清理本地会话并返回主菜单，不入后台上传队列。

## 错误处理

业务层主动禁用的入口展示“当前为完全离线本地模式”。若遗漏调用到网络层，拦截器以 `IOException("offline_local_blocked:<operation>")` 失败，不尝试 DNS、TCP 或 SSE 连接。

## 验证

- 配置合并单测确认 `localTriger` 的最终离线字段。
- 策略单测确认在线和离线判定。
- 本地触发单测确认空 `placeCode` 仍调用 coordinator。
- 入口/结束策略单测确认离线模式不要求 Wi-Fi、不上传结束指令。
- 运行 `:app:testLocalTrigerDebugUnitTest` 和 `:app:assembleLocalTrigerDebug`。
- 真机分别在 Wi-Fi 关闭、Wi-Fi 已连接两种状态验证；过滤 logcat 并检查不存在 `/ai/`、`pushHidDanger`、企业接口和更新接口请求。

