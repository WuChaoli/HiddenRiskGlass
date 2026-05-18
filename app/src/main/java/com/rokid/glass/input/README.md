# input/ — 统一输入层

## 业务概述

将触控（单击/双击/返回）、语音识别、头部手势统一抽象为 `UnifiedInput`，各页面通过 `buildInputActions()` 注册动作映射表，输入层根据当前页面态动态分发。

### 配套系统
- `AutoSleepStateMachine` — 检测眼镜摘下，自动进入休眠提示
- `HeadMotionStabilityTracker` — 陀螺仪跟踪头部稳定性
- `GlassesWearMonitor` — 佩戴状态广播监听

### 当前约束
- `HEAD_GESTURE_LISTENING_ENABLED = false` — 头部动作全局关闭

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `UnifiedInput.kt` | **统一输入核心**，注册动作、分发触控/语音/头部手势 | `UnifiedInputSession.attach()`, `updateActions()`, `dispatchTouch()`, `InputActionSpec`, `InputTrigger` |
| `AutoSleepStateMachine.kt` | **自动休眠状态机**，摘镜检测+休眠提示 | `Config`, `Snapshot`, `tick()`, `onGlassesRemoved()`, `onGlassesWorn()` |
| `AutoSleepController.kt` | 自动休眠控制器，协调传感器+状态机+UI | `attach()`, `detach()`, `setEnabled()`, `markSleepHandled()` |
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

自动休眠:
  GlassesWearMonitor.onWearStateChanged(false)
    → AutoSleepStateMachine.onGlassesRemoved()
      → tick() 倒计时
        → SLEEP_WARNING → AutoSleepController 通知 UI
```

## 依赖关系

- **依赖：** Android Sensor API、Rokid Glass SDK
- **被依赖：** `hiddenrisk/`（所有页面通过 UnifiedInputSession 注册动作）
