# 完整画幅实时打框测试页设计

## 1. 背景

当前正式自动隐患识别页使用裁切后的正方形帧进行预览、NV21 推理和远程请求，并在左上角显示相机预览。已有 `RawCameraPreviewDebugActivity` 验证了相机画面与眼镜显示的缩放、平移、固定距离视差和透明 Overlay 打框，但该测试链路会先把已对齐画面裁成 `3:4` 再发送 `/auto`。

后续业务改造要求以 Camera 实际返回的原始 `3:4` 纵向帧作为本地标准坐标系。原始帧不做裁切，只等比例缩小为 `960×1280` 后发送给远程服务；服务端返回的 BBox 先还原到原始帧坐标，再在显示阶段应用眼镜视野裁切和投影映射。为隔离验证风险，本阶段新建独立测试 Activity，先验证该坐标链能否稳定贴合真实物体，不修改现有测试页和正式业务页。

## 2. 目标

新建一个可通过 ADB 独立拉起的完整画幅实时打框测试页，实现以下闭环：

```text
Camera 实际返回的原始 3:4 Frame
  → 不裁切，等比例缩小为 960×1280
  → JPEG 编码
  → 调用真实 /auto
  → 获取 960×1280 坐标 BBox
  → 等比例还原到原始 Frame 坐标
  → 固定 1m + scale/offsetX/offsetY 映射
  → 透明 Overlay 绘制框、Label 和置信度
```

测试页需要支持真机调节 `scale`、`offsetX` 和 `offsetY`，并能在半透明相机画面与纯框模式之间切换，以验证检测框是否与现实目标对齐。

## 3. 非目标

本阶段不实现：

- BBox 面积占 `480×640` 画面的 `1/8` 判定。
- `/ai/deep` 请求。
- `/auto` 与 `/ai/deep` 双线状态机。
- 正式 `AiInspectionActivity` 的预览、推理或页面跳转改造。
- 二级业务菜单入口。
- 隐患保存、上传、巡检统计或文字流展示。
- 对现有 `RawCameraPreviewDebugActivity` 模式的行为修改。

上述业务能力在测试页真机验收通过后单独设计和实施。

## 4. 方案选择

采用独立 Activity 方案，新建 `FullFrameDetectionOverlayTestActivity`。不在现有 `RawCameraPreviewDebugActivity` 中增加模式，原因是后续还要继续改造该原型，而现有测试页需要保留为已验证对照基线。

测试页复用共享相机、远程 `/auto` 协议和现有标定结论，但将完整画幅请求、坐标映射、请求调度和 Overlay 渲染拆成独立组件，避免 Activity 同时承担所有职责。

## 5. 组件设计

### 5.1 `FullFrameDetectionOverlayTestActivity`

职责：

- 通过 `InspectionCameraCoordinator` 以独立 Camera Owner 申请和释放共享相机。
- 获取 Camera 实际返回的原始 `3:4` 帧，不在请求前裁成正方形或再次裁成 `3:4`。
- 将原始帧等比例缩小为 `960×1280`，禁止非等比拉伸。
- 管理生命周期、摘戴恢复、ADB 启动和眼镜按键输入。
- 协调 `/auto` 请求控制器、标定状态和 Overlay View。
- 显示请求尺寸、请求状态、BBox 数量、标定参数和虚拟显示窗口等诊断信息。
- 控制半透明预览与纯框模式。

测试 Activity 不读取或修改 `InspectionWorkflowSession` 的业务统计，也不触发隐患上传。

### 5.2 `FullFrameAutoDetectionController`

职责：

- 从原始 `3:4` 帧源选择最新帧，并保留实际 `sourceWidth/sourceHeight`。
- 不裁切原始帧，只等比例缩小为 `960×1280`，编码为 JPEG 后调用真实 `/auto`。
- 同一时间最多保留一个在途请求。
- 使用单调递增的 request ID 丢弃过期响应。
- 成功时输出经过协议校验的 `960×1280` 请求图坐标 BBox，以及该请求对应的原始帧尺寸。
- 请求失败或超时时报告错误，但不主动清除上一帧框。

首版沿用现有对齐测试链路的请求节奏和超时策略，具体数值由统一配置或现有常量提供，不在 Activity 中新增重复硬编码。

### 5.3 `FullFrameOverlayCalibrationState`

职责：

- 固定 `distanceMeters = 1f`。
- 初始使用现有测试页已验证的 `scale`、`offsetX` 和 `offsetY`。
- 支持在真机上调整 `scale`、`offsetX` 和 `offsetY`。
- 输出当前标定状态和完整画幅中的虚拟显示窗口。

标定参数应通过统一模型表达，不能假定 Camera 每次实际回调都固定为 `3024×4032`。转换过程先进入归一化 Camera 坐标，再映射到本次原始帧坐标，避免分辨率变化改变物理对齐关系。

### 5.4 `FullFrameOverlayMapper`

这是不依赖 Android View 的纯 Kotlin 坐标模块。

输入：

- 原始 `3:4` 帧的实际尺寸。
- 网络请求尺寸，固定为 `960×1280`。
- `/auto` 返回的 `960×1280` 请求图坐标 BBox。
- Overlay 实际尺寸，设计基线为 `480×640`。
- 固定 1m 的标定状态。

输出：

- 还原到原始帧坐标的 BBox。
- `VirtualDisplayCrop` 在原始帧中的位置。
- 与虚拟显示窗口相交后的可见 BBox。
- 映射到 Overlay 坐标系后的 BBox。

本模块不计算面积比例，不输出深度分析触发条件。

### 5.5 `FullFrameDetectionOverlayView`

职责：

- 接收已经映射到屏幕坐标的检测结果。
- 绘制绿色边框、Label 和置信度。
- 不负责原始帧缩放、标定计算或网络请求。
- 新的成功响应为空数组时清空旧框。
- 请求失败、超时或尚无新响应时保留上一帧框。

## 6. 坐标映射

### 6.1 坐标前提

- Camera、FOV 和 Zoom 与现有测试页标定环境一致。
- Camera 源帧是视觉转正的原始 `3:4` 纵向图，实际宽高以 SDK 回调为准。
- 源帧不做裁切，只等比例缩小为 `960×1280` 后发送 `/auto`。
- `/auto` 返回 BBox 使用 `960×1280` 请求图像素坐标，格式为 `left, top, right, bottom`。
- SDK 是否支持目标 `3:4` NV21 输出必须由真机支持尺寸和实际回调证明；现有 Surface 的 `3024×4032` 配置不能替代该证据。

### 6.2 请求坐标还原

服务端返回 BBox 后，先按本次请求记录的原始帧尺寸还原坐标：

```text
scaleX = sourceWidth / 960
scaleY = sourceHeight / 1280

sourceLeft   = responseLeft   * scaleX
sourceTop    = responseTop    * scaleY
sourceRight  = responseRight  * scaleX
sourceBottom = responseBottom * scaleY
```

正常 `3:4` 等比例缩放下 `scaleX == scaleY`，实现仍分别计算并校验两个比例，防止错误尺寸或非等比处理被静默接受。只有完成该还原后，才能应用标定投影参数。

### 6.3 虚拟显示窗口

新方案不在请求前裁切 Camera 源帧，而是在原始帧坐标上计算一个带缩放和平移的虚拟显示窗口：

```text
VirtualDisplayCrop = calibration(
    scale,
    offsetX,
    offsetY,
    distanceMeters = 1m,
)
```

- `scale` 决定显示窗口在 Camera FOV 中覆盖的范围。
- `offsetX` 决定水平平移，并包含固定 1m 的 Camera-to-Eye 视差补偿。
- `offsetY` 决定垂直平移。
- 虚拟窗口不要求位于完整画幅中心。

该窗口只用于坐标计算，不创建裁切后的请求 Bitmap。

### 6.4 BBox 求交和映射

对每个已经还原到原始帧坐标的合法 `/auto` BBox：

```text
visibleBBox = intersection(autoBBox, VirtualDisplayCrop)
```

- 无交集：不显示该框。
- 部分相交：只显示位于虚拟窗口内的可见部分。
- 完全包含：显示完整框。

映射到 Overlay：

```text
screenLeft =
    (visibleLeft - cropLeft) / cropWidth * overlayWidth

screenTop =
    (visibleTop - cropTop) / cropHeight * overlayHeight
```

`right` 和 `bottom` 使用相同公式。最终坐标裁剪到 Overlay 边界内。

## 7. 页面和输入行为

页面包含以下叠层：

```text
Camera Surface
  → FullFrameDetectionOverlayView
  → 诊断信息和操作提示
```

观察模式：

1. 半透明相机画面 + 检测框，用于确认框与相机目标的相对位置。
2. 相机画面完全透明 + 只显示检测框，用于验证最终现实叠加效果。

眼镜输入：

- 单击：依次切换 `offsetX → offsetY → scale`。
- 前滑/后滑：增加或减少当前选中的参数。
- 双击：切换半透明与纯框模式。
- 返回：退出测试页。

诊断信息至少显示：

- 原始帧实际尺寸和固定请求尺寸 `960×1280`。
- 最近一次请求状态和耗时。
- 最近一次返回的 BBox 数量。
- 当前观察模式。
- 固定深度 `1m`。
- 当前 `scale`、`offsetX` 和 `offsetY`。
- `VirtualDisplayCrop` 的原始帧坐标。

## 8. 请求和状态行为

测试页状态简化为：

```text
WAITING_CAMERA
  → READY
  → REQUESTING_AUTO
  → RENDERING_BOXES
  → REQUESTING_AUTO
```

约束：

- 相机未准备好时不调用 `/auto`。
- 同一时间只允许一个 `/auto` 请求在途。
- 当前请求完成或超时后才能调度下一次请求。
- Activity 暂停、摘镜或退出时停止调度并取消在途请求。
- Activity 恢复后重新申请帧流并恢复请求循环。
- 过期响应不能覆盖更新的检测结果。

## 9. 异常处理

- 原始 `3:4` 帧不可用：保持当前框，显示“等待原始帧”。
- SDK 未提供可用的 `3:4` NV21 输出：停止测试并报告支持尺寸，不用裁切的其他比例帧冒充原始 `3:4` 帧。
- JPEG 编码失败：保持当前框，延迟后重试。
- `/auto` 网络失败或超时：保持当前框并显示错误。
- `/auto` 成功返回空数组：清空当前框。
- BBox 非有限数、宽高非正或协议不完整：丢弃对应条目。
- BBox 完全位于虚拟显示窗口外：不渲染。
- 相机首帧超时或停滞：使用共享相机协调器现有恢复机制。
- 页面退出：释放测试页持有的 Camera Owner，不影响其他业务页面。

测试页不保存请求图片，不写入隐患记录，不调用隐患上传接口。

## 10. 验证

### 10.1 JVM 单元测试

- `960×1280` 返回 BBox 按比例还原到实际原始 `3:4` 帧。
- 还原后的原始帧 BBox 映射到 `480×640`。
- 非等比请求尺寸被拒绝或明确报告。
- BBox 完全位于虚拟窗口内。
- BBox 与窗口部分相交。
- BBox 完全位于窗口外。
- `scale` 改变时虚拟窗口尺寸变化。
- `offsetX`、`offsetY` 改变时虚拟窗口位置变化。
- 固定 1m 标定状态。
- 空成功结果清框。
- 请求失败保留旧框。
- 过期响应被丢弃。

### 10.2 构建和静态验证

- `standardDebug` 单元测试通过。
- Debug APK 构建成功。
- Android Manifest 中测试 Activity 可通过显式 ADB Intent 启动。
- 真机支持尺寸和回调日志证明测试页获得目标 `3:4` NV21 源帧。
- 请求编码后的 Bitmap 尺寸有自动化或日志证据证明为 `960×1280`。

### 10.3 真机验收

- ADB 能独立拉起测试页。
- 日志确认 Camera 实际回调为已转正的 `3:4` 源帧。
- 日志确认源帧未裁切、只等比例缩小为 `960×1280` 后发送真实 `/auto`。
- `/auto` 的真实 BBox 能持续刷新。
- 半透明模式下，框与相机画面中的目标基本重合。
- 纯框模式下，相机画面不可见，框与现实目标基本重合。
- 调整 `scale`、`offsetX`、`offsetY` 后，框位置即时更新。
- 连续运行期间没有重复并发请求、明显框闪烁或相机抢占。
- 退出测试页后相机资源正常释放。

## 11. 后续迁移边界

只有测试页完成真机验收后，才开始正式业务改造。允许迁移到业务页的内容包括：

- `FullFrameOverlayMapper`。
- 已验证的 Overlay View 或其渲染模型。
- 固化后的固定 1m 标定参数。
- 原始 `3:4` NV21 取帧、等比例缩小为 `960×1280` 和 `/auto` 请求方式。

业务阶段另行实现 BBox 面积门禁、`/auto` 与 `/ai/deep` 双线状态机、左上角预览移除和文字流跳转。测试页验收不能替代正式业务页构建及真机验证。
