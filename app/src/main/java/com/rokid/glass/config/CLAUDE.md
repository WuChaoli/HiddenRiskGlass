# config/ — 运行时配置系统

## 业务概述

从 `app/src/main/assets/inspection_config.base.jsonc` 加载巡检运行时配置，支持不同风味（flavor）覆盖。控制推理参数、API 端点、特性开关。

### 配置加载流程

```
InspectionConfigRepository.init(context, flavor)
  → loadFromAssets("inspection_config.base.jsonc")
  → overlayAssetName(flavor) → 加载风味覆盖
  → merge(base, overlay) → InspectionAppConfig
  → currentConfig = 合并结果
```

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `InspectionAppConfig.kt` | **配置数据类**，定义全部配置项结构 | `AiInspectionConfig`, `NetworkConfig`, `FeatureFlagsConfig`, `EnterpriseScanConfig`, `AutoInferenceMode`, `InferenceBackend`, `GpuProfile` |
| `InspectionConfigRepository.kt` | **配置加载器**，从 assets 读取并合并配置 | `init()`, `get()`, `reloadForTest()`, `buildConfig()`, `merge()` |

### 关键配置项（AiInspectionConfig）

| 字段 | 用途 |
|------|------|
| `targetInputSize` | NCNN 输入尺寸（当前 640）|
| `backend` | 推理后端（System Vulkan）|
| `gpuProfile` | GPU 配置（Balanced FP16）|
| `autoInferenceMode` | 自动推理模式 |
| `remoteFailureFallbackThreshold` | 远端失败 fallback 阈值 |
| `enableOnlineSceneHazardDetection` | 场景识别开关 |
| `enableOnlineAdvicePage` | 在线建议页开关 |
| `onlineDetectIntervalMs` | 在线检测间隔（默认 500ms）|
| `onlineDetectConcurrencyLimit` | 在线检测并发上限（默认 5）|

### 关键配置项（NetworkConfig）

| 字段 | 用途 |
|------|------|
| `aiAutoApi` | `/ai/auto` 端点 |
| `aiArApi` | `/ai/deep` + `/ai/device` 端点 |
| `saveResultApi` | 隐患保存端点 |

## 依赖关系

- **依赖：** Android AssetManager, Gson
- **被依赖：** `hiddenrisk/`（所有推理/接口参数从配置读取）
