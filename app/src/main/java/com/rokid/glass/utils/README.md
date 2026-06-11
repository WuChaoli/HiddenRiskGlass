# utils/ — 工具库

## 业务概述
提供全项目共用的工具函数和扩展，覆盖日志、图像处理、TTS 播放、SSE 通信、协程作用域、系统状态查询等领域。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AppFileLogger.kt` | 应用内诊断日志落盘，写入私有外部目录便于 adb 拉取 | `log()` |
| `BitmapUtils.kt` | Bitmap 工具：NV21 转 Bitmap、缩放、裁剪、旋转 | `nv21ToBitmap()`、`resizeBitmap()` |
| `DeviceUtil.java` | 设备信息与系统属性访问：序列号、型号、系统版本、屏幕参数、电量、Rokid 系统属性 | `getSystemProp()`、`setSystemProp()`、`getSerialNumber()`、`getDeviceModel()` |
| `DisplayUtils.kt` | dp/px 转换扩展函数 | `Context.dpToPx()`、`View.dpToPx()` |
| `OfflineTtsPlayer.kt` | 本地提示音播放器，使用 raw 音频资源，支持抢占式播放 | `play()` |
| `SSEUtil.kt` | SSE 服务端推送工具，封装 OkHttp EventSource 连接 | `connect()` |
| `Scopes.kt` | 协程作用域提供：IO 工作协程 + Main 主线程协程 | `workScope`、`mainScope` |
| `SpriteToastUtil.java` | 自定义 Toast 工具 (Java)，支持图标+文字 Sprite Toast | `showSpriteToast()`、`showSpriteToastOld()` |
| `StringUtils.kt` | 字符串工具：获取首个非空白字符串 | `firstNonBlank()` |
| `SystemStateUtils.kt` | 系统状态查询：WiFi 状态、网络连接、蓝牙状态 | `isWifiEnabled()`、`isNetworkConnected()` |
| `ToastUtil.kt` | Toast 工具 (Kotlin)，防重复弹出管理，支持手动取消 | `show()`、`cancel()` |
| `WifiScanConfigFactory.kt` | WiFi 扫码页配置工厂，封装 `GlassScanConfig` 创建逻辑 | `create()` |
| `kt_ext_flow.kt` | Kotlin Flow 扩展：`MutableSharedFlow.call()` 便捷发射 | `MutableSharedFlow.call()` |

## 依赖关系

- **依赖：** Android SDK、OkHttp 4.12.0、Kotlin Coroutines
- **被依赖：** `hiddenrisk/`、`updater/`、`camera/`、`component/`、`workflow/`、`input/` 等全项目模块
