# 两层菜单导航重构设计文档

**日期**: 2026-06-10
**主题**: 将单层菜单拆分为两层，第一层为系统级入口（基层应消/WiFi/更新），第二层为业务菜单，并取消双击退出机制
**方案**: 方案B（职责分离 + EntryGuardCoordinator）

---

## 1. 背景与目标

### 1.1 当前问题

- App 入口 `AiInspectionMenuActivity` 同时承担"系统入口检查"（WiFi、SDK预热、自动更新）和"业务功能菜单"（实时分析、设备指引、隐患拍照、检查更新）两重职责
- 双击/结束巡检后 App 直接退出，不符合"常驻前台"需求
- 没有主菜单页，无法在不同功能模块间切换而不退出 App

### 1.2 目标

- 第一层菜单（主菜单）：提供"基层应消"、"连接WiFi"、"检查更新"三个入口
- 第一层后台静默完成 WiFi 检测、SDK 初始化、相机预热、自动更新检查
- 第二层菜单（业务菜单）：仅保留业务功能卡片（实时分析、设备指引、隐患拍照）
- 第二层返回时必须先完成"结束巡检"流程，不能随意退出
- 第一层禁用双击退出，仅语音"退出应用"可真正退出 App

---

## 2. 页面导航流

```
开机自启动/点击图标
    │
    ▼
┌─────────────────────────────────────────────┐
│  MainMenuActivity (新，LAUNCHER)              │
│  ─────────────────────────────────────────  │
│  卡片：基层应消 │ 连接WiFi │ 检查更新         │
│  后台静默：WiFi检测 → SDK初始化 → 相机预热     │
│  后台静默：自动更新检查                       │
│  全局语音：退出应用 (真正退出)                 │
│  双击/后退：无响应（禁用退出）                 │
└─────────────────────────────────────────────┘
    │
    ├── 点击"基层应消" ───────────────────────┐
    │   （若后台初始化未完成，卡片显示加载状态    │
    │    初始化完成后自动解锁可点击）             │
    │                                          ▼
    │                              ┌──────────────────────────────────────┐
    │                              │  AiInspectionMenuActivity (改造后)    │
    │                              │  ──────────────────────────────────  │
    │                              │  卡片：实时分析 │ 设备指引 │ 隐患拍照   │
    │                              │  入口检查：企业QR扫码                  │
    │                              │  双击/后退 → 弹出"结束巡检"确认对话框  │
    │                              │  语音"退出" → 已删除                  │
    │                              │  语音"结束巡查" → InspectionEndReport  │
    │                              │  确认结束 → 提交报告 → 返回第一层      │
    │                              └──────────────────────────────────────┘
    │
    ├── 点击"连接WiFi" → GlassScanner WiFi QR 扫码连接
    │
    └── 点击"检查更新" → 手动检查更新 → AppUpdatePromptActivity
```

---

## 3. Activity 职责

| Activity | 职责 | 操作 |
|----------|------|------|
| `MainMenuActivity` | 第一层菜单展示、后台静默初始化（WiFi/SDK/相机）、自动更新检查、语音"退出应用"、禁用双击退出 | 新建 |
| `AiInspectionMenuActivity` | 第二层菜单展示、企业QR扫码、业务功能导航、双击/后退弹出"结束巡检"确认、语音"退出"删除 | 改造 |
| `EntryGuardCoordinator` | 封装后台静默初始化流程：WiFi检测、SDK初始化、相机预热、自动更新检查 | 新建 |
| `EnterpriseQrScanActivity` | 企业QR扫码（移除自动更新检查） | 改造 |
| `InspectionEndReportActivity` | 巡检结束报告（完成后返回第一层而非退出App） | 改造 |

---

## 4. EntryGuardCoordinator 设计

```kotlin
class EntryGuardCoordinator(
    private val context: Context,
    private val callback: Callback,
) {
    interface Callback {
        // WiFi 相关
        fun onWifiRequired(messageResId: Int)
        fun onWifiConnecting()
        fun onWifiConnected()
        fun onWifiConnectionFailed(messageResId: Int)

        // SDK 相关
        fun onSdkStateChanged(state: SdkInitState)

        // 相机相关
        fun onCameraStateChanged(state: CameraWarmupState)

        // 更新相关
        fun onAutoUpdateAvailable(updateInfoJson: String)
        fun onAutoUpdateCheckComplete(hasUpdate: Boolean)

        // 整体就绪
        fun onAllGuardsReady()
    }

    enum class SdkInitState { IDLE, INITIALIZING, READY, FAILED }
    enum class CameraWarmupState { IDLE, WARMING_UP, READY, FAILED }

    /** 启动后台静默入口检查：WiFi → SDK → 相机 → 自动更新 */
    fun startBackgroundGuards()

    /** 启动 WiFi QR 扫码器 */
    fun launchWifiScanner(activity: Activity)

    /** 手动触发自动更新检查 */
    fun checkUpdateManually()

    /** 释放资源 */
    fun release()
}
```

### 4.1 从现有代码迁移到 EntryGuardCoordinator 的逻辑

- **WiFi**: `SystemStateUtils.getCurrentWifiSsid()` 检查、`GlassScanner` WiFi QR 扫码、`WifiQrParser` 解析、`RokidSdkManager.connectWifi()` 连接、连接成功确认轮询
- **SDK**: `RokidSdkManager.initialize()`、`ensureInitialized()`、监听 `onSdkStateChanged()` 回调
- **相机**: `InspectionCameraCoordinator.acquire(owner = CameraOwner.MAIN_MENU, needPreview = false)` 后台预热，成功后 `pause()` 保持就绪状态
- **自动更新**: `AppUpdateManager.checkForUpdate()` + `AppUpdatePromptActivity` 导航
- 状态变量：`wifiScannerLaunching`、`wifiConnectInProgress`、`autoWifiScanAttempted`

### 4.2 保留在 Activity 中的逻辑

- 对话框布局的显示/隐藏（`layoutWifiRequiredDialog`）
- Toast 提示（`tvWifiConnectedToast`）
- TTS 播放
- `inputSession.updateActions()`
- 卡片状态指示（如"初始化中..."）

---

## 5. 状态管理

### 5.1 静默初始化状态机

```
[IDLE] ──startBackgroundGuards()──> [RUNNING]
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
              [WIFI_OK]          [SDK_READY]        [CAMERA_READY]
                    │                   │                   │
                    └───────────────────┴───────────────────┘
                                        │
                                        ▼
                                  [ALL_READY]
```

各阶段独立失败不影响其他阶段：
- WiFi 失败：阻塞菜单操作，必须重试
- SDK 失败：显示错误，可重试
- 相机失败：不影响菜单操作，进入业务功能时按需重新预热
- 自动更新失败：静默忽略

### 5.2 状态持久化

| 状态 | 存储位置 | 说明 |
|------|----------|------|
| SDK 初始化完成 | `RokidSdkManager.state == READY` | SDK 全局状态 |
| 相机预热完成 | `InspectionCameraCoordinator` 内部状态 | 相机协调器状态 |
| 企业信息 | `InspectionWorkflowSession` | **内存状态**，进程被杀后丢失 |
| WiFi 连接 | `SystemStateUtils.getCurrentWifiSsid()` | 系统 API |
| 自动更新已检查 | `EntryGuardCoordinator` 实例变量 | 本次生命周期内只查一次 |

---

## 6. 输入行为映射

### 6.1 第一层（MainMenuActivity）

| 事件 | 行为 |
|------|------|
| 单击（CLICK） | 确认选中卡片（若初始化未完成且是"基层应消"，无响应） |
| 前翻（FRONT） | 下一个卡片 |
| 后翻（BEHIND） | 上一个卡片 |
| **双击（DOUBLE_CLICK）** | **消费事件，无响应（不退出）** |
| **后退（BACK）** | **消费事件，无响应（不退出）** |
| 语音"退出应用"（tui chu ying yong） | 调用 `exitAppDirectly()` 真正退出 |
| 语音"基层应消" | 若初始化完成，进入第二层 |
| 语音"连接WiFi" | 启动 WiFi 扫码 |
| 语音"检查更新" | 手动检查更新 |

### 6.2 第二层（AiInspectionMenuActivity）

| 事件 | 行为 |
|------|------|
| 单击（CLICK） | 确认选中卡片 |
| 前翻（FRONT） | 下一个卡片 |
| 后翻（BEHIND） | 上一个卡片 |
| **双击（DOUBLE_CLICK）** | **弹出"结束巡检"确认对话框** |
| **后退（BACK）** | **弹出"结束巡检"确认对话框** |
| ~~语音"退出"~~ | ~~已删除~~ |
| 语音"结束巡查" | 直接进入 `InspectionEndReportActivity` |
| 语音"检查扫码" | 启动企业QR扫码 |
| 语音"实时分析" | 进入实时分析 |
| 语音"设备指引" | 进入设备指引 |
| 语音"隐患拍照" | 进入隐患拍照 |
| 确认对话框 → 确认 | 进入 `InspectionEndReportActivity` |
| 确认对话框 → 取消 | 隐藏对话框，留在第二层 |

---

## 7. 返回路径与结束巡检

### 7.1 结束巡检流程

```
AiInspectionMenuActivity
    ├── 双击/后退/语音"结束巡查"
    │       │
    │       ▼
    │   ┌──────────────────────────┐
    │   │ "结束巡检"确认对话框      │
    │   │ 确认 → InspectionEndReport│
    │   │ 取消 → 留在第二层         │
    │   └──────────────────────────┘
    │
    └── InspectionEndReportActivity
            │
            ├── 用户填写报告 → 点击确认
            │       │
            │       ▼
            │   后台提交报告（不阻塞 UI）
            │   清空 InspectionWorkflowSession 巡检状态
            │   暂停相机（不释放）
            │   finish() → 返回 AiInspectionMenuActivity
            │
            └── AiInspectionMenuActivity.onActivityResult
                    │
                    └── finish() → 返回 MainMenuActivity
```

### 7.2 导航栈规则

- 所有 Activity 使用 `standard` 启动模式
- 结束巡检后使用 `Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP` 返回 `MainMenuActivity`，避免栈中多层叠加
- `MainMenuActivity` 在 `onNewIntent` 中重置菜单选中状态

---

## 8. 文件改造清单

| # | 文件 | 操作 | 改动内容 |
|---|------|------|----------|
| 1 | `MainMenuActivity.kt` | 新建 | 第一层菜单（3卡片）、后台静默初始化、自动更新检查、禁用双击退出、语音"退出应用" |
| 2 | `EntryGuardCoordinator.kt` | 新建 | WiFi检测、SDK初始化、相机预热、自动更新检查的后台封装 |
| 3 | `AiInspectionMenuActivity.kt` | 改造 | 移除检查更新卡片、移除WiFi检测/SDK初始化/相机预热、保留企业QR扫码、双击/后退弹出"结束巡检"确认、语音"退出"删除 |
| 4 | `EnterpriseQrScanActivity.kt` | 改造 | 移除 `startAutoUpdateCheck()`（已上移到第一层） |
| 5 | `InspectionEndReportActivity.kt` | 改造 | 提交成功后清空巡检状态、暂停相机（不释放）、返回 `MainMenuActivity` |
| 6 | `AndroidManifest.xml` | 改造 | LAUNCHER 从 `AiInspectionMenuActivity` 移到 `MainMenuActivity` |
| 7 | `activity_main_menu.xml` | 新建 | 第一层菜单布局（参考 `activity_ai_inspection_menu.xml`，增加状态指示区域） |
| 8 | `strings.xml` | 改造 | 新增第一层菜单相关文案 |

---

## 9. 错误处理

| 场景 | 处理 |
|------|------|
| 第一层 WiFi 连接失败 | 显示 WiFi 对话框，阻断菜单操作，可重试 |
| 第一层 SDK 初始化失败 | 显示错误提示，可重试初始化 |
| 第一层相机预热失败 | 不影响菜单操作，进入业务功能时按需重新预热 |
| 第一层检查更新网络失败 | 静默忽略，不阻断菜单使用 |
| 用户点击"基层应消"时初始化未完成 | 卡片显示"初始化中..."，完成后自动解锁 |
| 第二层企业QR扫码失败 | 保留在扫码页，可重新扫码 |
| 第二层"结束巡检"报告提交失败 | `InspectionEndReportActivity` 后台重试，不阻塞返回 |
| 用户从第二层直接杀进程 | 下次启动从第一层开始，企业信息丢失需重新扫码 |
| 第一层禁用双击/后退退出 | 消费事件但不执行任何操作，防止误触 |

---

## 10. 关键设计决策

### 10.1 为什么后台静默初始化而不使用 Loading 页面

用户明确要求"不启用 loading 加载页面"。后台静默初始化允许用户在初始化过程中就能与第一层菜单交互（如连接 WiFi、检查更新），只有当点击"基层应消"时才需要等待初始化完成。这提升了用户体验，减少了等待焦虑。

### 10.2 为什么相机预热后 pause() 而不是 release()

用户明确要求"巡检结束后不再释放相机，而是暂停使用，等待下次进入的时候继续使用"。`InspectionCameraCoordinator.pause()` 暂停帧流但保持相机会话，下次 `acquire()` 时可以快速恢复，避免了重复的预热时间。但需要注意：`pause()` 后相机资源仍被占用，如果系统需要回收资源，可能需要处理恢复失败的情况。

### 10.3 为什么禁用第一层双击退出而不是覆盖为返回上一层

第一层已经是导航栈的根节点，没有上一层可以返回。禁用双击退出可以防止用户误操作退出 App，而语音"退出应用"作为明确的退出指令，确保退出是有意识的。

### 10.4 为什么第二层必须先"结束巡检"才能返回

这是业务约束——确保用户不会意外中断巡检流程而未完成报告提交。双击/后退都会触发"结束巡检"确认，只有完成报告提交后才能返回第一层。

### 10.5 为什么 SDK 和相机状态需要分开管理

`InspectionSession.isInitialized` 当前同时包含 SDK 初始化和模型加载状态。但在后台静默初始化场景中，SDK 就绪和相机就绪是两个独立的异步过程，可能先后完成。分开管理允许更细粒度的状态展示（如"SDK 就绪，相机预热中..."）。
