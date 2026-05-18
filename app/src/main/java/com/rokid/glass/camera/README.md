# camera/ — 相机管理与帧捕获

## 业务概述

负责 AR 眼镜相机的打开、预览、拍照和帧流管理。通过 Camera2 API 实现 GPU 帧捕获（HardwareBuffer），支持多页面共享帧流，具备相机异常恢复能力。

### 核心能力
- Camera2 API 相机生命周期管理
- GPU 帧捕获（HardwareBuffer → GpuFrame）
- 预览缩放、偏移、取景模式
- 相机异常自动恢复（RokidCameraRecoveryController）
- 共享帧流协调（InspectionCameraCoordinator，在 hiddenrisk/ 中）

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `QuickCameraManager.kt` | **相机管理器**，打开相机、预览、拍照 | `initialize()`, `attachPreviewTexture()`, `takePicture()`, `GpuFrame` |
| `RokidCameraRecoveryController.kt` | **相机恢复控制器**，检测异常并自动重连 | `start()`, `onRecoveryStarted()`, `onRecoverySucceeded()` |
| `RokidFrameSource.kt` | **帧源抽象**，提供统一帧获取接口 | |

## 核心调用链

```
QuickCameraManager.initialize()
  → CameraManager.openCamera()
  → createCaptureSession()
    → attachPreviewTexture(surfaceTexture)

帧捕获:
  → ImageReader.OnImageAvailable
    → HardwareBuffer → GpuFrame

拍照:
  → takePicture(callback)
    → createCaptureSession (still capture)
    → 保存 JPEG File
```

## 依赖关系

- **依赖：** Android Camera2 API, Rokid Glass SDK
- **被依赖：** `hiddenrisk/`（帧流消费）、`component/`（预览渲染）
