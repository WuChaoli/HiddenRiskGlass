# 本地 NCNN 推理并发修复设计

## 背景

`localTriger` 变体引入 `LocalTriggerDetectionService` 后，`AiInspectionActivity` 与
`DeviceGuideActivity` 会分别创建服务实例及独立线程池。它们共享进程级
`InspectionSession` 和 JNI 全局 `g_yolov8`，但加载、推理与 GPU 生命周期没有被完整串行化。

真机日志确认了两条故障链：

- 已加载模型在另一条页面工作线程中复用后，OpenMP 在
  `__kmp_affinity_initialize` 中触发 `SIGABRT`。
- 两条线程同时进入 `loadModel()`，并发操作 NCNN 全局 GPU instance，最终在
  `YOLOv8::load` 中触发 `SIGSEGV`，故障地址为 `0x8`。

模型资产曾成功完成 `load_param`、`load_model` 和一次真实推理，因此本设计不调整模型文件、
输出解析或 Vulkan 精度配置。

## 目标

- 在 Kotlin 层建立唯一的进程级本地推理执行序列。
- 在 JNI 层保证模型加载、推理和释放的完整生命周期互斥。
- 合并并发模型加载请求，避免重复加载和重复初始化 GPU。
- 页面退出只取消结果交付，不破坏正在执行的 native 调用。
- 巡检会话真正结束时能够显式释放模型和 GPU 资源。
- 保持当前检测结果、网络路由和页面行为不变。

## 非目标

- 不修改 `hiddenrisk.ncnn.param` 或 `hiddenrisk.ncnn.bin`。
- 不调整 `640` 输入尺寸、`System Vulkan` 后端或 `Balanced FP16` 配置。
- 不修改 YOLO 后处理、类别过滤、置信度阈值或 NMS。
- 不重构在线 SSE 检测链路。
- 不引入多模型并行推理或可配置线程池。

## 方案选择

采用 Kotlin 与 JNI 双层防护。

仅在 Kotlin 层串行化不能保护未来绕过协调器的 JNI 调用；仅在 JNI 使用全局大锁虽然安全，
但会让状态查询与耗时加载互相阻塞，也无法清理页面级线程池和取消语义。双层方案由 Kotlin
负责清晰的业务执行顺序，由 JNI 保证最终内存和 GPU 资源安全。

## Kotlin 架构

### 进程级协调器

新增 `LocalInferenceCoordinator`，作为进程级单例持有唯一的单线程 executor。所有本地模型操作
必须通过该协调器提交：

- 确保模型已加载；
- Bitmap 推理；
- 获取本次推理 stats；
- 显式释放模型。

协调器维护以下加载状态：

```text
UNLOADED -> LOADING -> READY
                    -> FAILED
FAILED   -> LOADING
READY    -> UNLOADED  (显式释放)
```

因为状态只在协调器线程中读写，不额外引入复杂锁结构。多个调用方在 `LOADING` 期间提交的检测
任务按序等待同一次加载结果，不再各自调用 `InspectionSession.loadModel()`。

### 服务生命周期

`LocalTriggerDetectionService` 不再创建或关闭模型执行线程。它只负责：

1. 校验 `placeCode`；
2. 将 JPEG 解码成 Bitmap；
3. 向共享协调器提交检测；
4. 将结果投递到主线程；
5. 在请求或页面取消后丢弃回调。

页面销毁时的 `shutdown()` 只标记服务关闭并取消尚未交付的结果，不调用
`shutdownNow()` 中断共享执行器。已经进入 JNI 的任务允许安全完成，其结果在服务关闭后不会投递。

Bitmap 的回收必须在协调器完成 native 推理后发生，并通过 `finally` 保证成功、失败和取消路径
都只回收一次。

### 会话生命周期

`InspectionSession` 继续保存 Java `HiddenRiskNcnn` 包装对象，但创建、加载和释放只能由协调器
调用。普通 Activity 切换不释放模型；只有巡检会话明确 `reset()` 或 `release()` 时，才向协调器
提交显式释放任务。

释放操作与排队中的推理严格有序。释放完成后状态回到 `UNLOADED`，下一次检测可以重新加载。

## JNI 架构

### 完整生命周期互斥

JNI 增加专用于模型生命周期的互斥机制，覆盖：

- 配置复用判断；
- 旧模型删除；
- `destroy_gpu_instance()`；
- `create_gpu_instance()`；
- `YOLOv8::load()`；
- 新模型发布；
- 完整的一次 `detect()`；
- 显式释放。

模型指针不得在锁内复制后脱离保护使用。推理持有模型使用权直到 `detect()` 返回，因此加载或
释放不能在推理期间删除 `g_yolov8` 或销毁 GPU instance。

最新检测结果和错误状态可继续使用现有状态锁保护，避免把轻量 stats 查询与模型加载锁混为
同一职责。锁的获取顺序固定为：先生命周期锁，再状态锁；任何路径不得反向获取。

### 防重入语义

当前 `g_diagnostic_detect_in_flight` 只记录 `reentered`，并不会阻止第二次推理。修复后并发推理
由生命周期互斥串行化，该变量只保留诊断用途或直接删除，不再宣称它提供安全保证。

### 显式释放

为 `HiddenRiskNcnn` 增加 native `releaseModel()`：在生命周期锁保护下删除模型、清空加载配置和
检测状态，然后销毁 GPU instance。该接口必须具备幂等性，未加载时调用也安全成功。

`JNI_OnUnload()` 复用同一内部释放函数，避免维护两套资源清理逻辑。

## 错误处理

- 模型加载失败时协调器进入 `FAILED`，当前检测返回明确错误。
- 下一次检测允许从 `FAILED` 重新进入 `LOADING`，不永久锁死会话。
- native 加载失败必须清理候选模型；已发布模型不得暴露半初始化状态。
- 服务关闭后不交付成功或失败回调，但仍执行 Bitmap 回收。
- Java/Kotlin 的 `try/catch` 只能处理 Java 异常；native 崩溃必须通过互斥和生命周期设计预防，
  不能依赖 `runCatching`。

## 测试设计

### JVM 单元测试

- 两个并发检测请求只触发一次模型加载。
- 模型加载、两次推理和 stats 读取严格按序执行。
- 加载失败后当前请求失败，后续请求能够重新加载并成功。
- 服务关闭后不投递回调，但执行中的任务完成并回收 Bitmap。
- 两个 `LocalTriggerDetectionService` 实例共享同一模型执行线程。
- 显式释放排在已有推理之后，释放后下一次检测重新加载。

测试通过注入 executor、native engine 和主线程 poster 控制执行顺序，不依赖 Android 真机。

### Native/结构测试

- 验证加载、推理与释放使用同一生命周期保护策略。
- 验证 `releaseModel()` 可重复调用。
- 验证失败加载不会发布候选指针或保留错误的已加载配置。
- 若当前工程不具备可运行的 Android native 单元测试环境，使用小型可测试生命周期封装加 JVM
  契约测试，并把真机压力测试列为完成门禁；不得用未执行的 native 测试代替真机结论。

### 回归测试

- 运行 `:app:testLocalTrigerDebugUnitTest`，覆盖新增并发和生命周期用例。
- 运行 `:app:testStandardDebugUnitTest`，确认标准变体未受影响。
- 构建 `localTrigerDebug` APK，确认 JNI 和所有 ABI 链接成功。

### 真机门禁

使用当前 Rokid Glass，至少执行以下序列：

1. 冷启动后进入实时分析，等待首次本地模型加载和推理完成。
2. 返回菜单后立即进入设备指引，连续执行本地触发检测。
3. 在模型加载期间快速切换一次页面，验证请求只排队、不重复加载。
4. 结束巡检触发显式释放，再重新进入并验证模型能够重新加载。
5. 连续运行本地检测，采集耗时、Java heap 和进程 RSS，确认没有持续增长。

验收日志必须满足：

- 每个会话首次使用只出现一次实际模型加载；
- 后续请求明确复用已加载模型；
- 推理开始和结束顺序完整，无重入执行；
- crash buffer 没有新增 `SIGABRT`、`SIGSEGV` 或 `libhiddenriskncnn.so` tombstone；
- 性能与内存数据相对修复前的单次成功推理基线无明显退化。

## 变更范围

预计修改：

- `app/src/main/java/com/rokid/glass/hiddenrisk/LocalInferenceCoordinator.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionSession.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java`
- `app/src/main/jni/yolov8ncnn.cpp`
- 对应 JVM 单元测试和必要的 native 生命周期测试封装

不修改模型转换项目、模型资产、在线检测协议或无关页面代码。

## 完成标准

- 所有本地模型操作均经过唯一协调器执行序列。
- JNI 不存在脱离生命周期保护使用的 `g_yolov8` 裸指针。
- 并发加载只能产生一次实际加载操作。
- 页面销毁不会中断 JNI，也不会收到过期回调。
- 显式释放幂等且只在会话结束时发生。
- 相关 JVM 测试、标准变体回归、APK 构建、真机切页压力测试、性能和内存门禁全部通过。
- 真机 crash buffer 无新增 native 崩溃记录。
