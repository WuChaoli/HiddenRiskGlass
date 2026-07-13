# 企业扫码后模型加载门禁设计

## 背景

`localTriger` 变体当前会在应用首页和 AI 二级菜单后台预加载本地 NCNN 模型。模型首次加载约需 29 秒，并与本地识别共用单线程执行器；如果巡检页面在模型加载完成前开始提交识别请求，请求会在加载任务之后排队并触发 4 秒业务超时。

本次调整恢复独立的 `InspectionLoadingActivity`，将它放在企业信息确认与 AI 二级菜单之间。模型加载完成是进入二级菜单的强制门禁。

## 目标流程

```text
EnterpriseQrScanActivity
  -> 获取企业信息
  -> EnterpriseInfoActivity
  -> 用户确认
  -> InspectionLoadingActivity
  -> 本地模型加载成功
  -> AiInspectionMenuActivity
```

企业扫码页和企业信息页保持现有职责不变。加载页只负责 SDK 状态确认、模型加载、错误重试和成功放行。

## 加载策略

本地模型需求必须由统一策略判断。满足以下任一条件时，加载页必须等待模型成功加载：

- `autoDetectProvider == LOCAL_TRIGGER`
- `autoInferenceMode == LOCAL_ONLY`
- `autoHazardRoutingMode == LOCAL_ONLY`
- `enableLocalFallbackLoading == true`

不需要本地模型时，加载页可以完成初始化并直接放行。模型已经处于就绪状态时，复用 `InspectionSession` 中的同一模型实例，不重复加载。

## 导航与生命周期

- `EnterpriseInfoActivity` 的确认动作改为启动 `InspectionLoadingActivity`，不再直接启动 `AiInspectionMenuActivity`。
- `InspectionLoadingActivity` 仅在 `InspectionSession.ensureModelLoaded()` 成功后调用 `InspectionSession.markInitialized()` 并进入二级菜单。
- `MainMenuActivity` 不再启动模型后台预加载。
- `AiInspectionMenuActivity` 不再启动模型后台预加载；它只消费已经通过加载门禁的会话。
- 加载页跳转成功后结束自身，避免返回键重新进入已完成的加载流程。

## 失败处理

- 模型加载失败时停留在加载页，不进入二级菜单。
- 复用加载页现有错误视图和单击重试行为。
- 重试前重置 `InspectionSession`，重新执行同一加载流程。
- 返回或退出保持加载页现有行为，不把失败会话标记为已初始化。

## 测试与验证

### 单元测试

为本地模型需求策略覆盖以下场景：

- `LOCAL_TRIGGER` 必须加载模型。
- `LOCAL_ONLY` 推理或路由模式必须加载模型。
- 启用本地 fallback 必须加载模型。
- 所有本地条件关闭时不需要加载模型。

### 构建验证

- 编译 `localTrigerDebug` Kotlin。
- 构建 `localTrigerDebug` APK。

### 真机验证

真机日志顺序必须满足：

1. 企业信息页确认后启动 `InspectionLoadingActivity`。
2. 出现模型加载开始日志。
3. 出现模型加载成功日志和 `InspectionSession` 初始化完成日志。
4. 成功日志之后才启动 `AiInspectionMenuActivity`。
5. 进入实时分析后首个本地识别请求的 `queueWaitMs` 接近零，不再被模型加载阻塞。

## 非目标

- 不修改 NCNN 模型、阈值、输入尺寸和 Vulkan 配置。
- 不修改企业二维码解析或企业信息接口。
- 不修改 `/ai/deep` 调用及隐患展示规则。
- 不新增加载页面或重复的模型持有者。
