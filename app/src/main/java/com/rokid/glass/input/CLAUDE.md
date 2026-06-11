# input/ — 统一输入层

## 业务概述

将触控（单击/双击/返回）、语音识别、头部手势统一抽象为 `UnifiedInput`，各页面通过 `buildInputActions()` 注册动作映射表，输入层根据当前页面态动态分发。

### 配套系统
- `WearStateManager` — 全局佩戴状态入口，维护当前前台页面回调
- `GlassesWearStateMachine` — 维护 `ACTIVE` / `SLEEP` / `WAKE` 三态佩戴恢复流程
- `HeadMotionStabilityTracker` — 陀螺仪跟踪头部稳定性
- `GlassesWearMonitor` — 佩戴状态广播监听

### 当前约束
- `HEAD_GESTURE_LISTENING_ENABLED = false` — 头部动作全局关闭

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `UnifiedInput.kt` | **统一输入核心**，注册动作、分发触控/语音/头部手势。提供 `buildPageCommonActions()`（页面级确认/取消自动注册）和 `buildCardVoiceActions()`（VoiceActionItem 批量语音注册） | `UnifiedInputSession.attach()`, `updateActions()`, `dispatchTouch()`, `buildPageCommonActions()`, `buildCardVoiceActions()`, `InputActionSpec`, `InputTrigger` |
| `VoiceActionItem.kt` | **语音指令项接口**，实现此接口的类（如 MenuCardData）可被自动发现并注册离线语音指令 | `labelResId`, `pinyinResId`, `pinyinAliases`, `execute()` |
| `WearStateManager.kt` | **全局佩戴状态管理器**，监听佩戴广播并向当前前台页面派发状态 | `init()`, `subscribe()`, `updateOwnerEligibility()`, `reportRecoveryReady()` |
| `GlassesWearStateMachine.kt` | **佩戴恢复状态机**，摘镜暂停+戴回动态恢复 | `Snapshot`, `onGlassesRemoved()`, `onGlassesWorn()`, `onRecoveryReady()` |
| `HeadMotionStabilityTracker.kt` | **头部稳定性跟踪**，陀螺仪数据→稳定性判断 | `start()`, `stop()`, `onStabilityChanged()` |
| `GlassesWearMonitor.kt` | 眼镜佩戴状态广播监听 | `attach()`, `detach()`, `onWearStateChanged()` |

## 核心调用链

```
页面注册:
  Activity.buildInputActions() → List<InputActionSpec>
    → UnifiedInputSession.updateActions(specs)
      → syncAdapters() (触控/语音适配器)

触控分发:
  BaseGlassActivity.onGlassKeyEvent(keyEvent)
    → UnifiedInputSession.dispatchTouch(key)
      → dispatchTrigger(touchTrigger)
        → 匹配当前页面态 → 执行 Action

语音分发:
  VoiceRecognition → "分析" / "取消"
    → UnifiedInputSession.dispatchTrigger(Voice(text, pinyin))
      → 匹配当前页面态 → 执行 Action

佩戴检测恢复:
  MyApplication.onCreate()
    → WearStateManager.init()
  BaseGlassActivity.onResume()
    → WearStateManager.subscribe(当前前台页面)
  GlassesWearMonitor.onWearStateChanged(false)
    → GlassesWearStateMachine.onGlassesRemoved()
      → WearStateManager 派发 SLEEP → 页面暂停检测并提示重新佩戴
  GlassesWearMonitor.onWearStateChanged(true)
    → GlassesWearStateMachine.onGlassesWorn()
      → WearStateManager 派发 WAKE → 页面恢复帧流并等待检测输入就绪
        → WearStateManager.reportRecoveryReady() → ACTIVE
```

## 依赖关系

- **依赖：** Android Sensor API、Rokid Glass SDK
- **被依赖：** `hiddenrisk/`（所有页面通过 UnifiedInputSession 注册动作）、adapter（`MenuCardData` 实现 `VoiceActionItem` 接口）
