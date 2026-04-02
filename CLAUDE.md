# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指引。

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
