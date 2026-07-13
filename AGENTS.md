# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Rokid AR 眼镜 Android 应用（"基层应消"），具备 AI 隐患检测功能。通过 NCNN (Vulkan GPU) 运行 YOLOv8 实现端侧推理，同时支持在线 SSE 远端推理作为补充（双轨推理架构）。

- 包名/applicationId：`com.rokid.glesse`
- 技术栈：Kotlin + C++ (JNI)
- 版本：`2.0.9`

## 显示设计基线

眼镜端显示基线：**480 x 640 px**，**240 dpi**（`1dp = 1.5px`）。理论满屏 `320 x 426.7 dp`，常规页面关键内容按 `320 x 402 dp` 设计。

## Android 构建与真机调试

```bash
bash scripts/android/doctor.sh              # 所有构建/设备操作前先做环境检查
bash scripts/android/doctor.sh --device     # 同时确认 Windows ADB 设备通路
bash scripts/android/build-debug.sh         # 构建 standardDebug APK
bash scripts/android/install-debug.sh -s <serial>
bash scripts/android/package-release.sh    # 正式配置不足时只生成 debug 签名演示包
bash scripts/android/verify-apk.sh <apk>    # 输出版本和证书摘要

# 测试
./gradlew :app:testStandardDebugUnitTest   # standard 变体单元测试
./gradlew connectedAndroidTest   # 仪器测试
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"

# 模型转换与验证（在同级独立工程中执行）
cd ../model_transformer
scripts/setup.sh
scripts/run_pipeline.sh
```

默认业务变体为 `standard`。WSL 编译使用根目录本地 `.env` 中的 JDK/SDK，Rokid Glass 真机操作使用 Windows `adb.exe`；命令、签名规则和踩坑记录见 `scripts/android/CLAUDE.md`。JNI/C++ 由 Gradle 通过 CMake 自动构建（`app/src/main/jni/CMakeLists.txt`），NDK 版本 `29.0.14206865`。

## 架构

五层架构：页面层 -> 会话层 -> 输入层 -> 相机层 -> 识别链路层。跨模块关系、依赖矩阵、端到端数据流见 **`docs/CODEMAPS.md`**（L2 内存文档）。

核心架构决策：**双轨推理** -- 本地 NCNN (YOLOv8 Vulkan) 始终运行作为 fallback，在线 SSE 推理提供更丰富的分析结果，由 `AutoHazardPipelineDecider` 链路调度。

## 模块代码地图

收到代码定位任务时，先查此表找到对应模块 CLAUDE.md，再进入具体文件。

| 模块 | 包/路径 | CLAUDE.md | 覆盖范围 |
|------|---------|--------|----------|
| 入口/菜单 | `com.rokid.glass/` 根包 | -- | MainMenuActivity (LAUNCHER)、AiInspectionMenuActivity、EntryGuardCoordinator、EnterpriseQrScanActivity 等 |
| 隐患识别/推理 | `hiddenrisk/` | [README](app/src/main/java/com/rokid/glass/hiddenrisk/README.md) | 巡检页面、在线/本地推理、自动链路、隐患上传、设备指引、拍照录入 (~50文件) |
| 相机/帧流 | `camera/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/camera/CLAUDE.md) | 相机管理、帧捕获、预览、恢复控制 |
| 统一输入 | `input/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/input/CLAUDE.md) | 触控、语音、头部动作映射、自动休眠 |
| 巡检工作流 | `workflow/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/workflow/CLAUDE.md) | 跨页面业务上下文、企业信息、QR 解析 |
| UI 组件 | `component/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/component/CLAUDE.md) | 状态栏、取景器、菜单、弹窗、提示 |
| 配置系统 | `config/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/config/CLAUDE.md) | 运行时配置、推理参数、API 端点、特性开关 |
| 原生推理 (JNI) | `jni/` | [CLAUDE.md](app/src/main/jni/CLAUDE.md) | C++ JNI桥接、YOLOv8推理、NCNN Vulkan |
| 网络 | `network/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/network/CLAUDE.md) | OkHttp 单例提供 |
| 应用更新 | `updater/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/updater/CLAUDE.md) | App 版本检查、下载、升级提示 |
| 工具库 | `utils/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/utils/CLAUDE.md) | 日志、Bitmap、SSE、TTS、系统状态查询等 |
| 全局状态 | `data/` | [CLAUDE.md](app/src/main/java/com/rokid/glass/data/CLAUDE.md) | 设备连接状态（P2P/蓝牙/H.264/SDK初始化）全局 Flow |

## 代码风格

- 代码/文件/目录命名：English；注释和文档：简体中文

### Kotlin
- 风格：`official`（`kotlin.code.style=official`），JVM 目标 1.8
- 类名：PascalCase，函数/变量：camelCase，常量：UPPER_SNAKE_CASE
- 优先使用 `val` 而非 `var`；纯数据载体使用 `data class`

### Java（仅 JNI 接口层和旧代码）
- 类名：PascalCase，方法：camelCase
- 新代码优先使用 Kotlin

### C++ (JNI)
- 文件名/函数名：snake_case
- 使用 ncnn 框架推理，OpenCV 图像预处理
- 日志：`__android_log_print`

### 错误处理
- JNI 层：`__android_log_print` 输出日志，返回错误码
- Kotlin/Java：try-catch，关键操作记录日志
- 模型推理失败时降级处理，不崩溃

### 包结构
```
com.rokid.glass/
├── camera/         # 相机管理
├── component/      # UI 组件
├── config/         # 运行时配置
├── data/           # 全局数据/事件
├── hiddenrisk/     # HiddenRisk NCNN 推理
├── input/          # 统一输入（触控/语音/头部动作）
├── network/        # 网络（OkHttp）
├── updater/        # 应用更新
├── utils/          # 工具类
├── workflow/       # 巡检工作流
└── *.kt            # Activity 入口
```

## 关键依赖

- Rokid Glass SDK `2.1.9-E`（推荐 OTA `1.17.e002-20260509-150201` 及以上）
- NCNN (Vulkan GPU 推理)、OpenCV Mobile 4.13.0
- ML Kit（条码扫描）、Jetpack Compose、Glide、Gson
- OkHttp 4.12.0（HTTP + SSE）

## 架构不变量（全局规则）

以下规则在整个代码库中不可违反：

1. **NCNN param + bin 必须成对替换**，不允许只换一个
2. **JNI 调用只能通过 `HiddenRiskNcnn.java`**，禁止在其他位置声明 native 方法
3. **配置只从 `InspectionConfigRepository` 读取**，禁止硬编码推理参数/API 端点
4. **相机帧流只通过 `InspectionSession` 获取**，Activity 不直接持有 Camera 引用

## NCNN 模型与推理配置

### 已验证的 GPU 配置

当前在眼镜端稳定运行的组合：
- 推理尺寸：`640`，后端：`System Vulkan`，GPU Profile：`Balanced FP16`
- `ncnn::Option.lightmode = true`，`ncnn::Option.use_local_pool_allocator = true`
- 该组合下 `detect ex.extract` 可稳定完成（`960 + No Packing FP32` 会在 extract 阶段被 `lmkd` 杀进程）

### 模型资产

- `app/src/main/assets/hiddenrisk.ncnn.param` 与 `.bin` 必须由同一次重导成对替换
- 旧 HiddenRisk Mini/YoloV11 源模型与根目录转换链已退役；当前转换工程为 `../model_transformer/`
- Android 内置 `hiddenrisk.ncnn.param` 与 `.bin` 不会由转换工程自动替换
- 原生侧统一读取 `out0_raw`，C++ 后处理兼容 raw proposal（`64+26`）和 decoded proposal（`4+26`）
- 当前 mini 模型检测头为单输出 `1x30x8400`（decoded 分支）

### 正式模型约束

- 模型转换与 CPU 对齐验证统一在 `../model_transformer/` 执行。
- 只有转换工程门禁通过的成对 NCNN param/bin 才能进入 Android 集成。
- 替换 Android 资产后必须单独执行项目构建和真机 Vulkan 验证；转换工程通过不等于 Android 集成完成。

### 探针页性能

- `HiddenRiskProbeActivity` 仅作探针页，不适合展示全量检测结果
- 当检测结果达数千条时，全量 JNI→Java 搬移会导致对象分配/堆压力/主线程卡顿
- 当前限制最多显示前 20 条 detection；长时间压测建议减少 UI 刷新频率或关闭明细渲染

## 代码定位工具使用规范

### 分层使用原则

| 层级 | 工具 | 适用场景 |
|------|------|----------|
| 第一层：结构探索 | LSP / CodeGraph | 理解"某个功能涉及哪些文件/模块" |
| 第二层：精确定位 | LSP (Serena) | 修改具体函数/类、确认影响范围、重命名 |
| 第三层：简单搜索 | Grep | 搜索硬编码值、配置键、字符串常量 |

### 禁止的低效模式

- 不要用 `Read` 逐行阅读大文件来"找函数在哪里"
- 不要用 `Grep` 搜索符号名然后手动判断哪个是真正的定义
- 不要用多个 `Read` + `Grep` 组合来拼凑跨文件调用链

## 调试与验证

- 关注日志中的 `detect preprocess target=640`、`detect padded ... anchors=8400`、`detect ex.extract done blob=out0_raw`
- GPU 稳定性问题按以下顺序排查：
  1. 确认 `TARGET_INPUT_SIZE` 是否仍为 `640`
  2. 确认 `GPU_PROFILE` 是否仍为 `Balanced FP16`
  3. 确认 `lightmode/local_pool_allocator` 没被改回诊断配置
  4. 区分是 ncnn 推理失败还是探针页/UI 自身导致进程退出

## 文档导航

| 文档 | 路径 | 说明 |
|------|------|------|
| 模块关系地图 (L2) | `docs/CODEMAPS.md` | 架构层级图、依赖矩阵、数据流、边界规则、术语表、任务速查 |
| Android 构建与真机调试 | `scripts/android/CLAUDE.md` | JDK/SDK/ADB 环境、构建、打包签名与排障入口 |
| 各模块 CLAUDE.md | 见上方"模块代码地图"表 | 模块内部文件索引与核心调用链 |

---

**三层文档体系**: `CLAUDE.md` (L1 缓存，本文档) -> `docs/CODEMAPS.md` (L2 内存) -> 各模块 `CLAUDE.md` (L3 硬盘)
