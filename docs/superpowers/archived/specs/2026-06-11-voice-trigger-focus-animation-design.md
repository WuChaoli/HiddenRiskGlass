# 语音触发卡片：焦点动画 + 执行

**日期**: 2026-06-11
**状态**: 设计完成

## 背景

当前语音命中的卡片直接执行 `onClick()` 跳转，缺少视觉反馈。触摸点击有焦点移动 + 按压回弹动画，语音触发也应该复用同一流程。

## 设计

核心思路：语音触发复用 `onItemConfirmed()` — 即触摸点击的完整流程（移动焦点 → 找 ViewHolder → 动画 → 执行）。

### 改动 1：`buildCardVoiceActions` 加回调参数

```kotlin
// UnifiedInput.kt — buildCardVoiceActions
fun buildCardVoiceActions(
    items: List<VoiceActionItem>,
    context: Context,
    onVoiceTrigger: (Int) -> Unit,  // 新增：由 Activity 处理焦点+动画+执行
): List<InputActionSpec>
```

将 `onTrigger = { item.execute() }` 改为 `onTrigger = { onVoiceTrigger(index) }`。

### 改动 2：`onItemConfirmed` 用传入的 index 定位 ViewHolder

```kotlin
// MainMenuActivity.kt / AiInspectionMenuActivity.kt
private fun onItemConfirmed(index: Int) {
    // 先移动焦点到目标卡片
    selectedIndex = index
    menuAdapter.selectedIndex = index
    // 找 ViewHolder（用传入的 index，而非旧的 selectedIndex）
    val viewHolder = recyclerMenu.findViewHolderForAdapterPosition(index) as? ...
    if (viewHolder != null) {
        menuAdapter.animateClick(viewHolder) { executeConfirmedAction(index) }
    } else {
        executeConfirmedAction(index)
    }
}
```

### 数据流

```
用户说"实时分析"
  → GlassSdk 语音回调
    → dispatchTrigger(Voice("实时分析", "shi shi fen xi"))
      → onVoiceTrigger(index=0)
        → onItemConfirmed(0)
          → selectedIndex = 0, menuAdapter.selectedIndex = 0  (焦点移动)
          → findViewHolderForAdapterPosition(0)
          → animateClick(viewHolder) {
              executeConfirmedAction(0) → card.execute() → 跳转
            }
```

## 影响文件

| 文件 | 改动 |
|------|------|
| `input/UnifiedInput.kt` | `buildCardVoiceActions` 加 `onVoiceTrigger` 参数 |
| `MainMenuActivity.kt` | `onItemConfirmed` 用 index 定位；传入 `onVoiceTrigger` |
| `AiInspectionMenuActivity.kt` | 同上 |

## 验证

1. 编译通过
2. 主菜单说"基层应消"：焦点移到第一张卡片 → 动画 → 进入巡检菜单
3. 巡检菜单说"设备指引"：焦点移到第二张卡片 → 动画 → 进入设备指引
4. 触摸点击行为不变（回归）
