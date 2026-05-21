---
name: rokid-glass3-sdk
description: Integrate, inspect, and troubleshoot Rokid Glass3 Android SDK features in Android projects, including Gradle/Maven setup, GlassSdk initialization, client registration, service binding, media/message/device APIs, offline voice commands, recognition services, and enterprise demo alignment. Use when Codex needs to接入、迁移、改造或排查 Rokid / Rokid Glass3 / GlassSdk / glass3.open.sdk / 眼镜端 SDK 相关代码、配置或文档。
---

# Rokid Glass3 SDK

## 概览

使用这个技能处理 Rokid Glass3 Android SDK 的接入、迁移和排错，并把修改限制在当前任务真正需要的 Gradle、生命周期和服务调用范围内。

主流程保留在这个文件里，详细接口说明按需加载：

- 修改 Gradle、依赖、打包配置前，先读 [references/setup.md](references/setup.md)
- 实现或调整具体 `GlassSdk` 服务调用前，先读 [references/api.md](references/api.md)

## 快速开始

1. 先检查当前仓库，不要凭记忆直接改。
2. 先确认项目里是否已经有 Rokid 文档、demo 代码或现成的 `GlassSdk` 接入。
3. 按这个顺序接入 SDK：Maven 仓库 -> 依赖 -> `GlassSdk.bindSecurityService()` -> `GlassSdk.registerClient()` -> 类型化 service accessor -> 生命周期清理。
4. 涉及眼镜端和手机端联动时，保持 `clientId` 一致。
5. 在宿主生命周期结束时显式释放或解绑服务，不要把 SDK 状态留成隐式行为。

## 集成流程

### 1. 确认构建接线

用 [references/setup.md](references/setup.md) 核对这些点：

- Rokid Maven 仓库是否已经配置
- 目标 SDK 依赖是否已经存在
- `libr2aud.so` 这类打包冲突是否已经处理
- 本地 demo 或参考工程是否可用于对照

如果仓库里已经有等价配置，复用它，不要重复新增一套 Gradle 片段。

### 2. 安全地绑定与注册

用 `GlassSdk.isReady()` 防重复初始化，并且只在服务连接完成之后注册 client。

```kotlin
if (GlassSdk.isReady()) return

GlassSdk.bindSecurityService(context, object : IServiceConnectionCallback {
    override fun onServiceConnected() {
        GlassSdk.registerClient(clientId, clientCallback)
    }
})
```

实现时遵守这些规则：

- 只要功能依赖跨端路由，眼镜端和手机端就使用同一个 `clientId`
- 优先使用 `GlassSdk.getGlassMediaService()` 这类类型化 accessor，而不是直接调用 `getService()`
- 如果拿到的 service 是 `null`，优先按生命周期问题排查：绑定、注册、服务可用性

### 3. 选对服务面

写代码前先把任务路由到正确模块：

- 媒体采集、录像、变焦、录音：看 [references/api.md](references/api.md) 里的 `IMediaServer`
- 文本、音频、视频、二进制传输和文件收发：看 [references/api.md](references/api.md) 里的 `IMessageServer` 与 `IGlassFileOperate`
- 设备信息、亮度、音量、麦克风场景、重启：看 [references/api.md](references/api.md) 里的 `IDeviceService`
- 离线语音指令：看 [references/api.md](references/api.md) 里的 `IOfflineCmdService`
- 在线人脸 / 车牌检测：看 [references/api.md](references/api.md) 里的 `IOnlineRecService` 与 `IGlassDetectionListener`
- 离线特征识别：看 [references/api.md](references/api.md) 里的 `IOfflineFeatureRecService` 与 `IGlassRecListener`
- 经典蓝牙和指环：看 [references/api.md](references/api.md) 里的 `IBTService` 与 `IBluetoothRingService`

如果任务提到的 service wrapper 在 `GlassSdk` 里存在，但参考文件里没有详细接口，就先去看本地 SDK stub 或 demo 再动手。

### 4. 补齐清理与可观测性

不要只写 happy path，要补或核对 teardown：

- 宿主生命周期结束时移除 listener
- 显式停止录制、推流、检测
- 由宿主持有 SDK 生命周期时，调用 `release()` / `unbindSecurityService()`

排错时优先看内置日志路径：

- `Downloads/glass3Log/<clientId>.txt`

## 常见任务模式

### 给现有 Android App 接入 Rokid SDK

1. 先读 [references/setup.md](references/setup.md)
2. 对齐仓库、依赖、打包配置和现有 Gradle 文件
3. 找到正确的生命周期宿主来 bind/register
4. 只补当前任务真正需要的 service 调用

### 从企业版 demo 迁移行为

1. 先对比 build 文件和 SDK 生命周期接线
2. 再对比 service 使用方式
3. 除非任务明确要求大迁移，否则只借用模式，不整块搬 demo 模块

### 排查接入异常

按这个顺序排查：

1. SDK 还没绑上：`GlassSdk.isReady()` 为 false，或者 service getter 返回 `null`
2. 没有在 `onServiceConnected()` 里注册 client
3. `clientId` 错误或两端不一致
4. 缺少功能前置条件，比如公有目录文件路径、离线库资产
5. listener 没注册、过早移除，或生命周期结束后没释放

## 约束

- 优先相信仓库现有的 Rokid 文档和 demo 参考，不要靠记忆硬写
- 保持最小修改，不要把一次 SDK 调整扩成无关架构改造
- 新增 listener 或长生命周期 service 时，保持 Android 生命周期正确
- 文件传输使用 SDK 可访问的公有目录路径
- `switchMicScene` 有大约 3 秒切换延迟，UI 和状态机要留余量
- 涉及离线识别能力时，先确认离线库或特征包真的存在，再假设接口可用
