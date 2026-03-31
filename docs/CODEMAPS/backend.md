# Android 后端架构

<!-- Generated: 2026-03-30 | Files scanned: 40+ | Token estimate: ~700 -->

## JNI 接口定义

### HiddenRiskNcnn.java

**路径**: `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java`

```java
// 模型加载
boolean loadModel(String paramPath, String binPath,
                  int backend, int gpuProfile, int targetSize)

// 推理接口
boolean submitBitmap(Bitmap bitmap)
boolean submitHardwareBuffer(HardwareBuffer buffer)

// 状态管理
void clearFrameState()
NativeInferenceStats getLatestInferenceStats()
```

### Native 方法映射

| Java 方法 | Native 函数 | 用途 |
|-----------|-------------|------|
| `loadModel()` | `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_loadModel` | 加载 NCNN 模型 |
| `submitBitmap()` | `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_submitBitmap` | Bitmap 推理 |
| `submitHardwareBuffer()` | `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_submitHardwareBuffer` | HardwareBuffer 推理 |
| `clearFrameState()` | `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_clearFrameState` | 清除状态 |
| `getLatestInferenceStats()` | `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_getLatestInferenceStats` | 获取统计 |

## 后端类型

```
Backend:
├── 0: CPU              # 纯 CPU 推理
├── 1: System Vulkan    # 系统 Vulkan 驱动
└── 2: Turnip           # Turnip GPU 驱动

GPU Profile:
├── 0: Safe FP32        # 安全 FP32 模式
├── 1: Balanced FP16    # 平衡 FP16 模式 (推荐)
└── 2: No Packing FP32  # 无打包 FP32
```

## 推理流程

```
┌─────────────────────────────────────────────────────────────┐
│ HiddenRiskProbeActivity                                     │
│ 1. 初始化 RokidSdkManager                                   │
│ 2. 初始化 QuickCameraManager                                │
│ 3. 加载 NCNN 模型 (HiddenRiskNcnn.loadModel)                │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ Camera2 API (QuickCameraManager)                            │
│ takeGpuFrame() → HardwareBuffer                             │
└──────────────────────┬──────────────────────────────────────┘
                       │ HardwareBuffer
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ yolov8ncnn.cpp (JNI)                                        │
│ 1. AHardwareBuffer_toHardwareBuffer                         │
│ 2. ncnn::Mat::from_android_hardware_buffer                  │
│ 3. ncnn::Extractor::input()                                 │
│ 4. ncnn::Extractor::extract()                               │
└──────────────────────┬──────────────────────────────────────┘
                       │ ncnn::Mat (输出)
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ yolov8_det.cpp                                              │
│ 1. 解析输出 (8400 anchors × 85 dims)                        │
│ 2. NMS (非极大值抑制)                                        │
│ 3. 生成 DetectionResult[]                                   │
└──────────────────────┬──────────────────────────────────────┘
                       │ DetectionResult[]
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ HiddenRiskMultiOverlayRenderer                              │
│ 1. 过滤低置信度检测                                          │
│ 2. 坐标转换 (模型空间 → 屏幕空间)                            │
│ 3. 绘制检测框 + 中文标签                                     │
└─────────────────────────────────────────────────────────────┘
```

## 关键类职责

| 类 | 职责 |
|----|------|
| `RokidSdkManager` | Rokid Glass SDK 生命周期管理 (bind/unbind) |
| `QuickCameraManager` | Camera2 API 封装，支持预览/拍照/录像/GPU 帧捕获 |
| `HiddenRiskNcnn` | JNI 桥接，加载模型并提交推理任务 |
| `HiddenRiskMultiOverlayRenderer` | 检测结果可视化，绘制边界框和标签 |
| `DetectionResult` | 单条检测结果数据类 (label, bbox, score) |
| `NativeInferenceStats` | 推理统计信息 (后端、耗时、错误码) |

## 错误处理

```cpp
// Native 错误码定义
enum ErrorStage {
    STAGE_NONE = 0,
    STAGE_LOAD_MODEL = 1,
    STAGE_SUBMIT_BITMAP = 2,
    STAGE_SUBMIT_HWBUFFER = 3,
    STAGE_EXTRACT_OUTPUT = 4
};

// 错误信息通过 NativeInferenceStats 返回
// - stage: 错误发生的阶段
// - errorCode: 错误码
// - errorMessage: 错误描述
```
