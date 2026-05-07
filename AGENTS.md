# glassdemo - AGENTS.md

旨在减少大语言模型（LLM）常见编码错误的行为准则。可根据需要与项目特定说明合并使用。

**权衡说明：** 本准则偏向谨慎而非速度。对于简单琐碎的任务，请自行判断。

## 1. 编码前先思考

**不要主观臆断。不要隐藏疑惑。显式说明权衡取舍。**

在动手实现之前：
- 明确陈述你的假设。如有不确定之处，直接提问。
- 如果需求存在多种理解，请全部列出——不要擅自默默选择其一。
- 如果存在更简单的方案，请明确指出。在理由充分时，敢于提出异议。
- 如果有任何不明确的地方，立即暂停。指出困惑所在并提问。

## 2. 简洁优先

**用最少代码解决问题。绝不添加假设性代码。**

- 不添加需求之外的功能。
- 不为仅使用一次的代码创建抽象层。
- 不添加未经请求的“灵活性”或“可配置性”。
- 不为不可能发生的情况编写错误处理逻辑。
- 如果你写了 200 行代码，而实际上 50 行就能搞定，请重写。

自问：“资深工程师会认为这过于复杂吗？”如果是，请简化。

## 3. 精准修改（外科手术式变更）

**只修改必须改动的部分。只清理自己造成的遗留问题。**

编辑现有代码时：
- 不要“顺手改进”相邻的代码、注释或格式。
- 不要重构原本正常运行的代码。
- 遵循现有代码风格，即使你个人有不同偏好。
- 如果发现无关的死代码（dead code），请指出但不要删除。

当你的修改产生残留代码时：
- 移除因**你的变更**而不再使用的导入、变量或函数。
- 除非明确要求，否则不要移除原本就存在的死代码。

检验标准：每一行修改都应能直接追溯到用户的具体需求。

## 4. 目标导向执行

**定义成功标准。持续迭代直至验证通过。**

将任务转化为可验证的目标：
- “添加验证” → “编写针对无效输入的测试，然后使其通过”
- “修复 Bug” → “编写能复现该 Bug 的测试，然后使其通过”
- “重构 X” → “确保重构前后测试均能通过”

对于多步骤任务，先简述计划：
1. [步骤] → 验证：[检查项]
2. [步骤] → 验证：[检查项]
3. [步骤] → 验证：[检查项]

清晰的成功标准能让你独立推进迭代；模糊的标准（如“让它能跑就行”）则需要反复沟通确认。

**若以下情况出现，说明本准则正在发挥作用：** 代码差异（diff）中的非必要变更减少、因过度设计导致的重写减少、确认性问题出现在动手编码之前，而不是在犯错之后才去补救。

Rokid Glass Android 应用，包含相机、人脸识别、车牌识别、HiddenRisk NCNN 推理等功能。

## Rokid Glass 显示设计基线

- 当前眼镜端经 adb 探测的显示基线为：`480 x 640 px`，`240 dpi`，即 `1dp = 1.5px`。
- 理论满屏设计尺寸为：`320 x 426.7 dp`。
- 常规页面关键内容优先按应用可用区 `320 x 402 dp` 设计；沉浸式或全屏背景可延展到 `320 x 426.7 dp`。

## 构建/测试命令

### Gradle 构建
```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease

# 清理并构建
./gradlew clean assembleDebug

# 安装到设备
./gradlew installDebug
```

### 测试
```bash
# 运行所有单元测试
./gradlew test

# 运行所有仪器测试
./gradlew connectedAndroidTest

# 运行单个单元测试 (指定类)
./gradlew test --tests "com.rokid.glesse.ExampleUnitTest"

# 运行单个测试方法
./gradlew test --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"

# 运行特定模块测试
./gradlew :app:testDebugUnitTest
```

### Python 模型脚本
```bash
cd models
source .venv/bin/activate  # 或 .venv/Scripts/activate (Windows)

# 导出 HiddenRisk 模型
bash scripts/export_hiddenrisk_640.sh

# 验证模型资产
bash scripts/validate_hiddenrisk_assets.sh
```

### C++ JNI 构建
JNI 代码由 Gradle 自动通过 CMake 构建。CMake 配置在 `app/src/main/jni/CMakeLists.txt`。

## 代码风格

### 语言约定
- **目录/文件/代码命名**: English
- **注释/文档**: 简体中文
- Kotlin 代码风格: `official` (见 gradle.properties)

### Kotlin 规范
- 使用 `kotlin.code.style=official`
- JVM 目标: `1.8`
- 类名: PascalCase (`HiddenRiskProbeActivity`)
- 函数/变量: camelCase (`detectPreprocess`)
- 常量: UPPER_SNAKE_CASE (`TARGET_INPUT_SIZE`)
- 工具类/扩展: 前缀 `kt_ext_` 或放在 `utils/` 包
- 使用 `data class` 表示纯数据载体
- 优先使用 `val` 而非 `var`

### Java 规范
- 仅用于 JNI 接口层和部分旧代码
- 类名: PascalCase, 方法: camelCase
- 新代码优先用 Kotlin

### C++ (JNI) 规范
- 文件名: snake_case (`yolov8_det.cpp`)
- 函数名: snake_case (JNI 风格)
- 使用 ncnn 框架进行推理
- OpenCV 用于图像预处理
- 包含路径相对 `jni/` 目录

### 包结构
```
com.rokid.glass/
├── annotation/     # 注解
├── adapter/        # RecyclerView 适配器
├── base/           # 基类 Activity
├── bean/           # 数据模型
├── camera/         # 相机管理
├── component/      # UI 组件
├── data/           # 全局数据/事件
├── enum/           # 枚举
├── hiddenrisk/     # HiddenRisk NCNN 推理
├── recycleview/    # RecyclerView 相关
├── utils/          # 工具类
└── *.kt            # Activity 入口
```

### 错误处理
- JNI 层: 使用 `__android_log_print` 输出日志, 返回错误码
- Kotlin/Java: 使用 try-catch, 关键操作记录日志
- 模型推理失败时, 记录日志并降级处理, 不崩溃

### 关键依赖
- AndroidX Core KTX, AppCompat, Material3
- Jetpack Compose (UI)
- ncnn (NCNN 推理, Vulkan 后端)
- OpenCV (图像预处理)
- ML Kit (条码扫描)
- Glide (图片加载)
- Gson (JSON 序列化)
- Rokid Glass SDK (`com.rokid.security:glass3.open.sdk:2.1.5-E`)

## HiddenRisk NCNN 经验

详细验证方法、准确率基线、历史根因与探针页排障经验，统一收敛到：

- `docs/公共能力/隐患识别验证与排障.md`

## HeadGesture 经验

头部动作识别当前基线参数、验证方法与调参经验，统一收敛到：

- `docs/公共能力/头部动作调参与验证.md`

## UnifiedInput 经验

统一输入注册层、调试页接入方式与后续业务页迁移顺序，统一收敛到：

- `docs/公共能力/统一输入设计与接入.md`

## 产品规格文档导航

当前产品行为基线、正式主链页面跳转与功能规格，统一收敛到 `docs/`：

- `docs/README.md`
  - 新文档体系总导航
- `docs/总体旅程图/总体旅程图.md`
  - 正式巡检主链全景
- `docs/公共能力/架构总览.md`
  - 页面层、会话层、输入层与识别链路的总览
- `docs/功能模块/WiFi连接.md`
  - Wi-Fi 扫码、配网、连接验证与成功跳转
- `docs/功能模块/任务关联.md`
  - 企业扫码、企业信息页与菜单前置上下文
- `docs/功能模块/主菜单.md`
  - AI 巡检菜单项、触控翻页与语音直达
- `docs/功能模块/隐患识别.md`
  - `InspectionLoadingActivity` + `AiInspectionActivity` 的识别主流程、状态与控制逻辑
- `docs/功能模块/设备指引.md`
  - 设备指引能力、判定确认与详情展示
- `docs/功能模块/隐患录入.md`
  - 拍照、实时分析、保存与结束任务流程
- `docs/功能模块/结束巡查.md`
  - 统一结束页、来源返回与结束上报
- `docs/公共能力/统一输入.md`
  - 业务接入层的统一输入规则

### 当前已验证可运行的 GPU 组合

- HiddenRisk 当前在眼镜端已验证可运行的组合为：
  - 推理尺寸：`640`
  - 后端：`System Vulkan`
  - GPU Profile：`Balanced FP16`
  - `ncnn::Option.lightmode = true`
  - `ncnn::Option.use_local_pool_allocator = true`
- 该组合下，`detect ex.extract` 已可稳定完成，不再像 `960 + No Packing FP32` 那样在 extract 阶段被 `lmkd` 杀进程。

### 当前仓库中的模型资产状态

- `app/src/main/assets/hiddenrisk.ncnn.param` 与 `app/src/main/assets/hiddenrisk.ncnn.bin` 已由同一次正式重导成对替换。
- 当前正式小模型源目标为 `models/source/hidden_risk_mini_0330.onnx`。
- 若运行时资产不是由该源同次重导得到，需先重导 `param + bin` 再做验证，避免"源模型与资产不一致"。
- `models/generated/hiddenrisk_640` 这套由 `hidden_risk_mini_0330.onnx` 重导出的 ONNX/NCNN 产物已在 CPU 端按 JNI 一致的 `letterbox + pad114 + /255 -> out0_raw -> decoded postprocess` 流程验证与 ONNX 输出对齐；出现语义漂移时请优先排查运行时 profile，而不是重提转换链漂移。
- 旧的 `models/scripts/compare_onnx_ncnn.py` 仅对图像执行 `Resize(640,640)`，没有复用 JNI 中的 `letterbox+pad114` 流程，因此不应作为 HiddenRisk 语义结论的直接依据。
- 原生侧统一读取 `out0_raw`，C++ 后处理兼容：
  - raw proposal `64 + 26`
  - decoded proposal `4 + 26`
- 当前 mini 模型的 ONNX / NCNN 检测头为单输出 `1x30x8400`，落在 decoded 分支。

### 正式重导约束

- `models/scripts/export_hiddenrisk_640.sh` 当前支持 `.pt` 与 `.onnx` 两类正式输入源。
- `models/source/best.pt` 保留为完整训练资产源。
- `models/source/hidden_risk_mini_0330.onnx` 是当前目标小模型部署源。
- 正式发布前，必须通过仓库内脚本执行完整链路：
  - `best.pt -> static torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn`
  - `hidden_risk_mini_0330.onnx -> pnnx(fp16=1) -> ncnn`
- 正式资产必须使用同一次重导生成的 `param + bin` 成对替换当前 patch 资产。
- 正式运行时统一使用 `out0_raw` 作为最终 blob 名称。
- `models/source/model_20251218.onnx` 仅保留为历史参考，不再作为正式导出输入。

### 参数图 patch 方案的使用边界

- 该方案适合：
  - 当前环境缺少 `torch / ultralytics / onnx / onnx2ncnn / ncnnoptimize` 工具链时，先快速验证眼镜端 GPU 是否能跑通。
  - 目标是先降低显存峰值，优先验证 `extract` 是否可稳定执行。
- 该方案不适合：
  - 需要长期维护、可重复构建、可审计的模型发布流程。
  - 需要严格保证图结构、decode 逻辑、输出语义完全来自同一份源模型导出结果。

### 后续更推荐的正式方案

- 正式发布前，优先采用"从源模型重导"的完整链路：
  - `best.pt -> torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn`
- 若已经拿到定型的小模型 ONNX，可直接走：
  - `hidden_risk_mini_0330.onnx -> pnnx(fp16=1) -> ncnn`
- 重导后应使用新生成的 `param + bin` 成对替换当前资产，不要长期维持"新 param + 旧 bin + 绕过 decode tail"的混合状态。
- 正式重导与校验入口固定为：
  - `models/scripts/export_hiddenrisk_640.sh`
  - `models/scripts/validate_hiddenrisk_assets.sh`

## 探针页经验

- 当前 `HiddenRiskProbeActivity` 只是探针页，不适合展示全量检测结果。
- 当检测结果接近数千条时，如果每轮都把全量 detections 从 JNI 搬到 Java，再渲染到 `TextView`，会显著增加：
  - JNI 对象分配压力
  - Java 堆压力
  - UI 布局和主线程卡顿
- 当前项目已限制调试页最多显示前 `20` 条 detection，同时保留总 `detectionCount`。
- 如果后续仍需要长时间循环压测，优先减少 UI 刷新频率或完全关闭调试页明细渲染。

## 调试与验证建议

- 关注以下日志是否出现：
  - `detect preprocess target=640`
  - `detect padded ... anchors=8400`
  - `detect ex.extract done blob=out0_raw`
- 若再次出现 GPU 稳定性问题，先按以下顺序排查：
  1. 确认 `TARGET_INPUT_SIZE` 是否仍为 `640`
  2. 确认 `GPU_PROFILE` 是否仍为 `Balanced FP16`
  3. 确认 `lightmode/local_pool_allocator` 没被改回诊断配置
  4. 区分是 ncnn 推理失败，还是探针页/UI 自身导致进程退出
