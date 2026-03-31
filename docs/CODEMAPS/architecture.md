# GlassDemo 系统架构

<!-- Generated: 2026-03-30 | Files scanned: 50+ | Token estimate: ~1200 -->

## 系统概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App (app/)                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   UI Layer  │  │  AI Layer   │  │     Camera Layer        │  │
│  │  (Compose)  │  │  (NCNN/VK)  │  │    (Camera2 API)        │  │
│  └──────┬──────┘  └──────┬──────┘  └───────────┬─────────────┘  │
│         │                │                      │               │
│         └────────────────┼──────────────────────┘               │
│                          ▼                                      │
│              ┌───────────────────────┐                          │
│              │  HiddenRisk Probe     │                          │
│              │  (HiddenRiskProbeActivity)                       │
│              └───────────────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Model Pipeline (models/)                      │
│         YOLOv8 (best.pt) → ONNX → PNNX → NCNN (.bin/.param)     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Third-Party Native (third_party/)                   │
│              NCNN + OpenCV Mobile + Vulkan                       │
└─────────────────────────────────────────────────────────────────┘
```

## 核心模块

### 1. UI 层 (Activities)

| Activity | 路径 | 功能 |
|----------|------|------|
| HiddenRiskProbeActivity | `hiddenrisk/HiddenRiskProbeActivity.kt` | **主入口** - AI 推理探针页面 |
| HomeActivity | `HomeActivity.kt` | 功能菜单导航 |
| CameraActivity | `CameraActivity.kt` | 相机预览 + 条码扫描 |
| GlassFaceTrackActivity | `GlassFaceTrackActivity.kt` | 人脸追踪 |
| GlassLprTrackActivity | `GlassLprTrackActivity.kt` | 车牌识别 |

### 2. HiddenRisk AI 模块

```
hiddenrisk/
├── HiddenRiskProbeActivity.kt      # 主 Activity - 协调 SDK/相机/推理
├── HiddenRiskNcnn.java             # JNI 桥接类
├── HiddenRiskMultiOverlayRenderer.kt  # 检测结果渲染
├── RokidSdkManager.kt              # Rokid SDK 生命周期管理
├── BaseGlassActivity.kt            # 眼镜按键事件基类
├── DetectionResult.java            # 检测结果数据类
└── NativeInferenceStats.java       # 推理统计信息
```

### 3. 相机层

| 管理器 | 路径 | 用途 |
|--------|------|------|
| QuickCameraManager | `camera/QuickCameraManager.kt` | **主相机** - Camera2 API + HardwareBuffer |
| CameraTestManager | `camera/CameraTestManager.kt` | 测试用相机 |
| CameraTestManager2 | `camera/CameraTestManager2.kt` | NV21 预览回调测试 |

### 4. JNI/Native 层

```
app/src/main/jni/
├── yolov8ncnn.cpp      # JNI 入口，Java ↔ C++ 桥接
├── yolov8.cpp          # YOLOv8 模型封装
├── yolov8_det.cpp      # 检测后处理
├── CMakeLists.txt      # Native 构建配置
├── ncnn-20260113-android-vulkan/   # NCNN 预编译库
└── opencv-mobile-4.13.0-android/   # OpenCV 预编译库
```

## 数据流

```
Camera (HardwareBuffer)
         │
         ▼
┌──────────────────┐
│ QuickCameraManager│
│ takeGpuFrame()   │
└────────┬─────────┘
         │ HardwareBuffer
         ▼
┌──────────────────┐
│ HiddenRiskNcnn   │
│ submitHardwareBuffer()
└────────┬─────────┘
         │ JNI
         ▼
┌──────────────────┐
│ yolov8ncnn.cpp   │
│ NCNN Inference   │
└────────┬─────────┘
         │ DetectionResult[]
         ▼
┌──────────────────┐
│ HiddenRiskMulti  │
│ OverlayRenderer  │
│ (绘制检测框)      │
└──────────────────┘
```

## 关键配置

- **包名**: `com.rokid.glesse`
- **minSdk**: 29 (Android 10)
- **targetSdk**: 34 (Android 14)
- **NDK**: 29.0.14206865
- **Compose**: 1.5.1
- **Rokid SDK**: 2.1.5-E
