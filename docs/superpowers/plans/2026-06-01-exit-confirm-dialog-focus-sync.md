# 退出确认对话框焦点同步修复实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复菜单页退出确认对话框中，按键选中"取消"后按 CLICK 仍会退出 app 的 bug。

**Architecture:** 在 `updateExitConfirmSelection()` 方法中同步 Android 焦点和应用管理的选中状态，确保按 CLICK 时触发的 `OnClickListener` 与视觉选中一致。

**Tech Stack:** Kotlin, Android SDK

---

### Task 1: 修改 updateExitConfirmSelection() 方法

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt:397-406`

- [ ] **Step 1: 读取当前方法实现**

```bash
Read: app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt (lines 397-406)
```

当前代码：
```kotlin
private fun updateExitConfirmSelection() {
    exitConfirmButtons.forEachIndexed { index, button ->
        button.setBackgroundResource(
            if (index == exitConfirmSelectedIndex) R.drawable.glass_card_outline_selected
            else R.drawable.glass_card_outline,
        )
        button.setTextColor(getColor(R.color.green))
        button.setTypeface(null, if (index == exitConfirmSelectedIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }
}
```

- [ ] **Step 2: 添加焦点同步逻辑**

在 `setTypeface` 调用后添加焦点同步：

```kotlin
private fun updateExitConfirmSelection() {
    exitConfirmButtons.forEachIndexed { index, button ->
        button.setBackgroundResource(
            if (index == exitConfirmSelectedIndex) R.drawable.glass_card_outline_selected
            else R.drawable.glass_card_outline,
        )
        button.setTextColor(getColor(R.color.green))
        button.setTypeface(null, if (index == exitConfirmSelectedIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        // 同步焦点，确保按键事件和视觉状态一致
        if (index == exitConfirmSelectedIndex) {
            button.requestFocus()
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
bash scripts/android/build-debug.sh
```

预期：编译成功，无错误。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt
git commit -m "fix: 同步退出确认对话框焦点与选中状态

在 updateExitConfirmSelection() 中调用 button.requestFocus()，
确保 Android 焦点系统与应用管理的 exitConfirmSelectedIndex 保持一致。
修复选中取消后按 CLICK 仍会退出 app 的问题。"
```
