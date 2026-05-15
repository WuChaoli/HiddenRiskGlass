---
name: rokid-unified-input
description: Use when working on Rokid Glass event listener registration, unified input migration, or page interaction design that combines voice, touch, and head gestures. This skill tells Codex to use the repo's UnifiedInputSession as the default registration entry, map NOD to confirm and SHAKE to cancel where appropriate, and follow the common page-state interaction patterns already established in glassdemo.
metadata:
  short-description: Rokid 统一事件监听与交互接入
---

# Rokid Unified Input

## Overview

用于 Rokid Glass 页面事件接入与交互设计。
默认目标不是分别拼接 `VoiceAction`、`onGlassKeyEvent`、`HeadGestureManager.Listener`，而是统一收敛到 `UnifiedInputSession`，让页面只声明“业务动作”和“触发方式”。

## When To Use

在以下场景使用本技能：

- 需要给 Rokid 页面注册输入监听
- 需要把旧页面从分散的语音/触控/陀螺仪逻辑迁移到统一入口
- 需要设计确认/取消类交互，决定何时启用点头/摇头
- 需要评审某个页面是否错误地直接操作 `VoiceAction` 或 `HeadGestureManager`

如果只是调 `HeadGestureManager` 参数本身，或修改统一输入底层实现，再额外阅读仓库源码；不要只依赖本技能摘要。

## Default Rule

默认做法：

- 页面层使用 `UnifiedInputSession`
- 页面层声明 `buildInputActions()` 或同等动作列表构造函数
- 生命周期里做 `attach()` / `detach()` / `release()`
- 状态切换时调用 `updateActions()`
- `onGlassKeyEvent()` 只做 `dispatchTouch(...)`

默认不要在业务页面重新散落注册：

- `VoiceAction`
- `GlassSdk.getGlassOfflineCmdService().add/remove(...)`
- `HeadGestureManager.addListener/removeListener`

这些只应出现在统一输入层或其基础设施中，除非当前任务就是在改统一输入底座。

## Repo Anchors

如果当前工作目录是 `glassdemo`，优先以这些文件为准：

- 统一输入实现：`/mnt/c/Users/wuchaoli/Desktop/codespace/glassdemo/app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`
- 设计说明：`/mnt/c/Users/wuchaoli/Desktop/codespace/glassdemo/docs/UnifiedInput_设计与接入.md`
- 调试页示例：`/mnt/c/Users/wuchaoli/Desktop/codespace/glassdemo/app/src/main/java/com/rokid/glass/hiddenrisk/UnifiedInputDebugActivity.kt`
- 正式业务页示例：
  - `LightshotActivity`
  - `InspectionLoadingActivity`
  - `AiInspectionActivity`

先看现有页面怎么声明动作，再决定是否扩展统一输入底座。

## Integration Workflow

1. 先识别页面状态，而不是直接识别输入源。
2. 把页面真正的业务动作列出来，例如“开始”“确认保存”“取消同步”“退出页面”“上一项”“下一项”。
3. 为每个动作声明触发器组合，而不是为每种输入单独写一套分支。
4. 只在当前状态允许该动作时启用该动作。
5. 页面提示文案必须和当前启用的动作一致。

最小接入骨架：

```kotlin
private val inputSession by lazy { UnifiedInputSession(this, TAG) }

override fun onResume() {
    super.onResume()
    inputSession.attach()
    inputSession.updateActions(buildInputActions())
}

override fun onPause() {
    inputSession.detach()
    super.onPause()
}

override fun onDestroy() {
    inputSession.release()
    super.onDestroy()
}

override fun onGlassKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
    return inputSession.dispatchTouch(keyCode) || super.onGlassKeyEvent(keyCode, event)
}
```

当页面状态改变时，重新执行：

```kotlin
inputSession.updateActions(buildInputActions())
```

不要只改页面提示而不改动作注册，也不要只改动作注册而不改页面提示。

## Common Interaction Rules

### Confirm / Cancel 节点

在需要明确确认的节点，优先使用以下语义：

- `NOD` = 主确认动作
- `SHAKE` = 次动作或取消动作
- 触控单击通常也映射主确认
- 返回键或双击通常映射取消/退出
- 语音命令和 UI 文案保持同语义，不要求逐字一致，但不能冲突

典型例子：

- 确认开始巡检：单击/语音“开始”/点头 = 开始；返回/双击/摇头 = 取消或退出
- 确认保存到手机：单击/语音“保存”/点头 = 保存；返回/双击/摇头 = 取消

### Navigation 节点

浏览、翻页、切换候选项时：

- 触控前滑/后滑负责上一项/下一项
- 语音“上一个”“下一个”与触控保持一致
- 默认不要启用陀螺仪

摇头/点头不适合连续导航，否则误触成本高。

### Long Running / Detecting 节点

持续检测、加载、推流、等待结果时：

- 默认关闭陀螺仪确认
- 只保留必要的退出或取消
- 如果页面会在该阶段切到确认态，在切态时再 `updateActions()`

### Error / Retry 节点

失败重试页默认不要直接加点头/摇头，除非产品明确要求。
优先保留：

- 单击重试
- 返回/双击退出
- 可选语音“重试”“退出”

## Review Checklist

评审或改造页面时，优先检查：

- 是否仍在页面里直接增删 `VoiceAction`
- 是否仍在页面里直接注册 `HeadGestureManager.Listener`
- 是否已经按页面状态切换 `updateActions()`
- 是否把 `NOD` 只用于确认类动作
- 是否把 `SHAKE` 只用于取消/次动作
- 是否在持续运行态错误地开启了陀螺仪
- 是否保证页面提示文本与当前启用动作一致

## Read More

当你需要更具体的页面模式、迁移步骤和动作映射模板时，继续读：

- `references/common-patterns.md`
