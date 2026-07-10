# 按需相机所有权与页面释放设计

## 1. 背景与目标

当前入口菜单和加载流程会提前注册相机帧流，并通过 `pause()` 保持 NV21 链路。该行为会持续占用 App 相机，导致 Rokid SDK 的 `GlassScanner` 无法获得相机。

本次改造目标：

- 菜单页和加载页不再预热、注册或持有相机。
- 仅在正式业务页面需要摄像头时注册 Surface 和 NV21。
- Activity 明确离开时主动完整释放相机。
- Activity 遗漏释放时，新 owner 请求由底层强制清理并移交。
- 业务相机获取失败时由底层统一重试。
- `GlassScanner` 首次直接启动；仅在明确相机错误时释放 App 相机并重试一次。

## 2. 范围

### 2.1 本次覆盖

- `AiInspectionActivity`
- `DeviceGuideActivity`
- `HazardRecordActivity`
- `EnterpriseQrScanActivity`
- `InspectionCameraCoordinator`
- `EntryGuardCoordinator`
- `MainMenuActivity`
- `InspectionLoadingActivity`
- 所有现有 `GlassScanner.launch()` 入口

### 2.2 本次不覆盖

- `RawCameraPreviewDebugActivity`
- `HiddenRiskProbeActivity`
- `LightshotActivity`
- 其他调试或独立相机页面

上述页面保持现状，不在本次改造中统一生命周期接口。

## 3. 总体架构

保留 `InspectionCameraCoordinator` 作为 App 内 Surface、NV21、owner 和 generation 的唯一协调器，不新增只做代理的业务会话对象。

协调器增强以下能力：

1. 业务页面按需获取相机。
2. 临时暂停不完整释放 NV21。
3. 明确导航时完整释放 Surface、NV21 和 owner。
4. 新 owner 请求时强制清理未释放的旧 owner。
5. 相机获取失败后统一清理并重试。
6. generation 隔离过期请求和异步回调。

外部扫码流程使用轻量统一入口封装 `GlassScanner.launch()` 的资源冲突恢复，但不承担业务相机 owner。

## 4. InspectionCameraCoordinator 设计

### 4.1 页面接口

协调器提供语义明确的页面接口：

- `acquireForActivity(...)`
  - 页面需要摄像头时调用。
  - 根据参数注册 Surface 和 NV21。
  - 绑定明确的 `CameraOwner`。
- `pauseTemporarily(owner, reason)`
  - 用于权限弹窗、系统遮挡或短暂进入后台。
  - 停止页面消费或预览，但不完整释放 NV21 和 owner。
- `releaseForNavigation(owner, reason)`
  - 用于返回、取消、完成、跳转其他 Activity 或主动 `finish()`。
  - 停止 Surface 和 NV21，并清空 owner。

现有底层方法可继续保留为内部实现或兼容入口，但四个正式业务页面统一使用上述语义接口。

### 4.2 新 owner 强制移交

`acquireForActivity()` 收到与当前 owner 不同的新请求时：

1. 使旧 generation 失效。
2. 停止旧 owner 绑定的 Surface。
3. 停止 NV21 帧流。
4. 清空旧 owner 和预览绑定。
5. 创建新 generation。
6. 为新 owner 重新注册所需链路。

强制移交是遗漏主动释放时的兜底，不替代 Activity 的正常主动释放。

### 4.3 业务相机重试

每次业务相机获取最多尝试四次：

- 第一次正常尝试。
- 失败后最多额外重试三次。
- 每次重试前完整清理 App 相机资源。
- 每次重试间隔固定为 `300ms`。

重试必须绑定请求 generation。请求过期、owner 改变或页面明确释放后，后续重试立即终止。最终结果只向 Activity 回调一次。

### 4.4 并发与日志

关键日志至少包含：

- 操作类型。
- 旧 owner 和新 owner。
- generation。
- 当前尝试次数。
- 释放或移交原因。
- Surface 和 NV21 的停止结果。
- 最终成功或失败结果。

## 5. Activity 生命周期

四个正式业务页面统一采用以下规则。

### 5.1 页面进入

页面实际需要摄像头时调用 `acquireForActivity()`，并按业务需求传入预览视图和 `needPreview`。

### 5.2 临时暂停

普通 `onPause()` 不执行完整释放。需要暂停页面消费时调用 `pauseTemporarily()`，避免权限框、系统弹窗或短暂遮挡导致相机链路反复销毁。

### 5.3 明确离开

下列出口在导航或 `finish()` 前调用 `releaseForNavigation()`：

- 用户返回。
- 用户取消。
- 业务完成。
- 跳转至其他 Activity。
- 主动结束当前页面。

`onDestroy()` 保留兜底释放，但正常流程不依赖 `onDestroy()`。

## 6. GlassScanner 资源冲突恢复

建立统一扫码启动入口，所有 `GlassScanner.launch()` 调用复用同一策略：

1. 首次直接调用 `GlassScanner.launch()`，不预先释放 App 相机。
2. 若启动抛异常，或失败信息明确属于相机占用、相机打开或相机权限错误，则调用 `InspectionCameraCoordinator.releaseAppCamera()`。
3. 等待 `300ms` 后重试一次 `GlassScanner.launch()`。
4. 第二次仍失败时显示警告弹窗，不再重试。

以下情况不触发相机释放和启动重试：

- 普通二维码识别失败。
- 二维码内容无效。
- 用户取消扫码。
- 无法明确归类为相机资源问题的业务错误。

错误分类集中在扫码启动封装中，Activity 只处理扫码结果和最终警告展示。

## 7. 删除入口相机预热

### 7.1 EntryGuardCoordinator

删除：

- `CameraWarmupState`
- `Callback.onCameraStateChanged()`
- `cameraCheckCompleted`
- `tryStartCameraWarmup()`
- `CameraOwner.LOADING` 的预热调用
- 相机预热日志、导入和注释
- `tryNotifyAllGuardsReady()` 对相机完成状态的等待

调整后的入口职责：

```text
WiFi 检查 -> SDK 初始化 -> 更新检查
                       \-> 菜单就绪
```

自动更新检查独立执行，不阻塞菜单就绪。`MainMenuActivity` 不再接收相机状态，也不注册 Surface 或 NV21。

### 7.2 InspectionLoadingActivity

删除：

- 相机初始化阶段和对应状态。
- 相机预热进度。
- 加载页中的 `CAMERA` 权限申请；媒体读取权限如仍被模型或资源流程使用则保留。
- `InspectionCameraCoordinator.acquire()` 和 `pause()` 调用。
- 相机预热失败处理。

加载页只保留 SDK 初始化、必要的模型加载和页面导航。

## 8. 错误处理

- 业务相机获取最终失败后，页面继续使用现有相机错误状态展示。
- 新 owner 强制移交失败时，不恢复旧 owner；新请求按统一重试策略继续处理。
- 过期 generation 的成功或失败回调一律忽略。
- `GlassScanner` 第二次启动失败后显示明确警告，不循环重试。
- 普通扫码识别错误不得被误判为相机故障。

## 9. 测试与验收

### 9.1 自动化测试

- 协调器主动释放 Surface、NV21 和 owner。
- 新 owner 请求触发旧 owner 强制移交。
- 获取失败后执行三次额外重试，间隔策略可控。
- 页面释放或 owner 改变后终止旧请求重试。
- 过期 generation 回调不能重新绑定相机。
- 最终结果只回调一次。
- 扫码启动错误分类正确。
- 扫码明确相机错误后释放并只重试一次。
- 普通识别失败和取消不触发相机重启。

### 9.2 静态检查

逐个检查四个业务页面的返回、取消、完成、页面跳转和 `finish()` 出口，确认均在导航前调用 `releaseForNavigation()`。

### 9.3 构建验证

按项目规则执行：

```bash
bash scripts/android/doctor.sh
bash scripts/android/build-debug.sh
```

### 9.4 真机验证

- 停留在 `MainMenuActivity` 和 `AiInspectionMenuActivity` 时 App 不占用相机。
- `InspectionLoadingActivity` 不注册 Surface 或 NV21。
- 菜单页可正常启动 `GlassScanner`。
- 明确相机冲突时扫码器释放 App 相机并只重试一次。
- 四个业务页面进入时按需获得 Surface/NV21。
- 四个业务页面明确离开后 Surface、NV21 和 owner 均清空。
- 临时 `onPause()` 不触发完整释放。
- 模拟页面遗漏释放后，新 owner 仍能通过底层强制移交获得相机。
- 日志中的 owner、generation、重试次数和释放顺序符合设计。

## 10. 成功标准

- 菜单页和加载页不再持有相机。
- `GlassScanner` 不再因菜单预热长期占用而无法启动。
- 正式业务页面只在需要时获得相机，并在明确离开时完整释放。
- 遗漏主动释放不会阻塞下一个 App 内相机 owner。
- 业务相机获取具备统一、有限且可取消的重试机制。
- 调试和独立相机页面行为不被本次改造改变。
