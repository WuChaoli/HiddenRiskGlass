# data/ — 全局状态数据

## 业务概述

提供全项目共享的简单状态容器，用于跨模块传递设备连接状态和 SDK 初始化状态。基于 Kotlin `MutableStateFlow` 实现，支持响应式订阅。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `GlobalData.kt` | **全局连接状态容器**，维护 P2P、蓝牙、H.264、SDK 初始化、Ring 的五态 Flow | `isGlassConnect()`, `setSdkInitState()`, `reset()` |
| `YXData.java` | **通用响应数据模型**（answer/code/end），用于部分遗留接口的数据承载 | |

## 关键状态说明

| Flow | 含义 | 更新时机 |
|------|------|----------|
| `p2pConnectState` | P2P 网络连接状态 | 眼镜与手机 P2P 建立/断开时 |
| `btConnectState` | 蓝牙连接状态 | 蓝牙配对状态变化时 |
| `h264ConnectState` | H.264 视频流连接状态 | 视频流传输建立/断开时 |
| `sdkInitState` | Rokid SDK 初始化完成状态 | SDK 初始化成功/失败时 |
| `ringConnectState` | Ring 配件连接状态 | Ring 控制器连接状态变化时 |

## 依赖关系

- **依赖：** Kotlin Coroutines Flow (`utils/` 的 `call()` 扩展)
- **被依赖：** `hiddenrisk/`、`camera/` 等模块读取设备连接状态
