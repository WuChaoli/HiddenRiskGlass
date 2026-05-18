# component/ — 可复用 UI 组件

## 业务概述

提供眼镜端通用 UI 组件，包括状态栏（时间/电量）、取景预览、功能菜单、操作指引、状态弹窗。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `GlassStatusBar.kt` | **顶部状态栏**，显示时间+电量 | `updateTime()`, `updateBattery()`, `setBatteryPercent()` |
| `RokidCameraPreviewView.kt` | **相机预览视图**，渲染帧流+健康监控 | `startPreview()`, `stopPreview()`, `PreviewHealthListener` |
| `FunctionMenuView.kt` | **右上功能菜单**，显示菜单标题+内容 | `setMenu(title, content)` |
| `BottomPromptView.kt` | **底部提示栏**，显示操作提示文案 | `setPrompt(title, subtitle)` |
| `OperationGuideView.kt` | **操作指引卡片**，显示引导标题+内容 | `setGuide(title, content)` |
| `StatusAlertOverlayView.kt` | **状态弹窗叠层**，倒计时+动画+自动消失 | `render(model)`, `reset()`, `AlertBehavior` |
| `StatusAlertStateMachine.kt` | 弹窗状态机，控制显示/隐藏决策 | `render()`, `RenderDecision.Show/Hide` |
| `StatusAlertModels.kt` | 弹窗数据模型 | `StatusAlertModel`, `AlertBehavior`, `AlertStyle` |

## 依赖关系

- **依赖：** Android View 体系
- **被依赖：** `hiddenrisk/`（各页面使用这些组件构建 UI）
