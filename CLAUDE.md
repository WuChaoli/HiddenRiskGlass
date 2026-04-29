# CLAUDE.md

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


## 项目概述

Rokid AR 眼镜 Android 应用，具备 AI 隐患检测功能。通过 NCNN (Vulkan GPU) 运行 YOLOv8 实现端侧推理。包名：`com.rokid.glass`，技术栈：Kotlin + C++ (JNI)。

## 构建命令

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew assembleRelease        # 构建 release APK
./gradlew clean assembleDebug    # 清理后构建
./gradlew installDebug           # 安装到已连接设备

# 测试
./gradlew test                   # 单元测试
./gradlew connectedAndroidTest   # 仪器测试
./gradlew test --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"  # 运行单个测试

# 模型导出（需要 models/ 下的 Python 虚拟环境）
cd models && source .venv/bin/activate  # Windows 下用 .venv/Scripts/activate
bash scripts/export_hiddenrisk_640.sh
bash scripts/validate_hiddenrisk_assets.sh
```

JNI/C++ 由 Gradle 通过 CMake 自动构建（`app/src/main/jni/CMakeLists.txt`）。

## 架构

**UI 层** — Jetpack Compose Activity：
- `HiddenRiskProbeActivity` — AI 推理探针/调试页面（入口）
- `HomeActivity` — 导航菜单
- `CameraPageActivity`、`GlassFaceTrackActivity`、`GlassLprTrackActivity` — 功能页面

**AI/ML 层** — 基于 NCNN 的 YOLOv8 检测：
- 原生 C++ 代码位于 `app/src/main/jni/`：`yolov8ncnn.cpp`（JNI 桥接）、`yolov8.cpp`、`yolov8_det.cpp`（检测 + 后处理）
- 模型资产位于 `app/src/main/assets/`：`hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`
- 已验证的 GPU 配置：640 输入尺寸、System Vulkan、Balanced FP16、lightmode + local_pool_allocator 开启

**相机层** — Camera2 API，通过 `QuickCameraManager` 实现 GPU 帧捕获

**Kotlin 包结构**（`com.rokid.glass/`）：
- `hiddenrisk/` — NCNN 推理逻辑与探针页
- `camera/` — 相机管理
- `base/` — Activity 基类
- `bean/` — 数据模型
- `utils/` — 工具类与扩展函数
- `data/` — 全局状态/事件

## 代码风格

- **命名规范**：代码/文件/目录使用英文；注释和文档使用简体中文
- Kotlin 风格：`official`（`kotlin.code.style=official`），JVM 目标 1.8
- 类名：PascalCase，函数/变量：camelCase，常量：UPPER_SNAKE_CASE
- C++ 文件：snake_case；新代码优先使用 Kotlin，Java 仅用于旧 JNI 接口
- 优先使用 `val` 而非 `var`；纯数据载体使用 `data class`

## NCNN 模型流水线

- 当前部署源：`models/source/hidden_risk_mini_0330.onnx`
- 完整训练资产：`models/source/best.pt`
- 导出链路：`.pt -> torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn` 或 `.onnx -> pnnx(fp16=1) -> ncnn`
- 原生侧读取 blob `out0_raw`；C++ 后处理兼容 raw (64+26) 和 decoded (4+26) 两种 proposal
- 当前 mini 模型输出 `1x30x8400`（decoded 分支）
- 资产必须使用同一次导出生成的 `param + bin` 成对替换

## 关键依赖

- Rokid Glass SDK `2.1.5-E`、NCNN (Vulkan)、OpenCV Mobile 4.13.0
- ML Kit（条码扫描）、Jetpack Compose、Glide、Gson

## 调试

验证推理是否正常运行的关键日志：
- `detect preprocess target=640`
- `detect padded ... anchors=8400`
- `detect ex.extract done blob=out0_raw`

GPU 稳定性排查顺序：检查 `TARGET_INPUT_SIZE=640` → `GPU_PROFILE=Balanced FP16` → `lightmode/local_pool_allocator` 设置 → 区分是 ncnn 推理失败还是 UI/探针页自身崩溃。

详细验证与排障文档：`docs/HiddenRisk_验证与排障.md`
