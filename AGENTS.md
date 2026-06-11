# glassdemo - AGENTS.md

Rokid Glass Android 应用，包含相机、人脸识别、车牌识别、HiddenRisk NCNN 推理等功能。

## Rokid Glass 显示设计基线

- 当前眼镜端经 adb 探测的显示基线为：`480 x 640 px`，`240 dpi`，即 `1dp = 1.5px`。
- 理论满屏设计尺寸为：`320 x 426.7 dp`。
- 常规页面关键内容优先按应用可用区 `320 x 402 dp` 设计；沉浸式或全屏背景可延展到 `320 x 426.7 dp`。

## Android 构建与设备调试入口

- Android 构建、安装、设备调试和 APK 打包前，必须先执行 `bash scripts/android/doctor.sh`。
- 默认业务变体为 `standard`；日常 debug 构建固定使用 `bash scripts/android/build-debug.sh`。
- WSL 环境下构建走本地 JDK/SDK，眼镜真机命令走 Windows `adb.exe`；具体环境配置与故障经验见 `scripts/android/CLAUDE.md`。
- 正式签名配置不完整时，打包只允许生成明确标识的 debug 签名本地演示包，不得称为正式升级包。

```bash
# 检查构建环境与真机连接
bash scripts/android/doctor.sh
bash scripts/android/doctor.sh --device

# 构建、安装、验包与打包
bash scripts/android/build-debug.sh
bash scripts/android/install-debug.sh -s <serial>
bash scripts/android/verify-apk.sh app/build/outputs/apk/standard/debug/app-standard-debug.apk
bash scripts/android/package-release.sh
```

### 测试
```bash
# 运行所有单元测试
./gradlew :app:testStandardDebugUnitTest

# 运行所有仪器测试
./gradlew connectedAndroidTest

# 运行单个单元测试 (指定类)
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glesse.ExampleUnitTest"

# 运行单个测试方法
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"

# 运行特定模块测试
./gradlew :app:testStandardDebugUnitTest
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
- Rokid Glass SDK (`com.rokid.security:glass3.open.sdk:2.2.0-E`)，推荐 OTA `1.17.e002-20260509-150201` 及以上

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

## AI 代码定位工具使用规范

本项目配置了 Serena（LSP）和 CodeGraph（知识图谱）两套代码智能工具。遵循以下规范可最大化效率、减少 token 浪费：

### 分层使用原则

**第一层：结构探索（优先 CodeGraph）**
- 当需要理解"某个功能涉及哪些文件/模块"时，使用 `codegraph_explore` 或 `codegraph_context`
- 避免用 `Read` 逐行扫描陌生文件来获取结构信息
- CodeGraph 一次返回入口点 + 相关符号 + 代码片段，减少工具调用次数

**第二层：精确定位（优先 Serena）**
- 当需要修改某个具体函数/类时，使用 `serena find_declaration` 精确定位
- 当需要确认"修改某处会影响哪些地方"时，使用 `serena find_referencing_symbols`
- 当需要重命名时，使用 `serena rename_symbol` 而非手动替换

**第三层：简单搜索（使用 Grep）**
- 搜索特定字符串（如端口号、配置键、硬编码值）时，直接用 `Grep`
- 不需要语义分析的场景，不必动用 Serena 或 CodeGraph

### 禁止的低效模式

- 不要用 `Read` 逐行阅读大文件来"找函数在哪里"
- 不要用 `Grep` 搜索符号名然后手动判断哪个是真正的定义
- 不要用多个 `Read` + `Grep` 组合来拼凑跨文件调用链（改用 CodeGraph）

### 典型场景示例

| 场景 | 错误做法 | 正确做法 |
|------|----------|----------|
| 改登录逻辑 | Grep "login" → 逐个 Read 文件找相关代码 | CodeGraph 查 `login_submit` → 返回调用链 |
| 重命名方法 | Grep + 手动替换所有匹配 | `serena rename_symbol` |
| 新模块接入 | 逐个 Read 相邻文件猜接口 | `serena get_symbols_overview` 看已有接口 |
| 排查影响范围 | 手动追踪 import 和调用 | `codegraph_impact` 一键返回 |

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
