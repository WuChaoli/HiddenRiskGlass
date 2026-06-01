# 退出确认对话框焦点同步修复设计

## 问题描述

在 `AiInspectionMenuActivity`（菜单页）双击触发退出确认对话框后，用户通过方向键将选中状态移动到"取消"按钮，随后按 CLICK（确认键），app 依然会退出。

## 根因分析

退出确认对话框包含两个按钮："确认"和"取消"。这两个按钮分别设置了独立的 `OnClickListener`：

- `tvExitConfirmConfirm.setOnClickListener { exitAppDirectly() }`
- `tvExitConfirmCancel.setOnClickListener { hideExitConfirmDialog() }`

当对话框显示时，Android 焦点系统会自动将焦点设置到第一个可聚焦的视图（即"确认"按钮）。应用通过 `UnifiedInputSession` 管理自己的选中状态（`exitConfirmSelectedIndex`），但 Android 的焦点系统是独立的。

当用户按 CLICK（对应 `KeyEvent.KEYCODE_DPAD_CENTER`）时：

1. Android 焦点系统触发**焦点所在按钮**的 `OnClickListener`（此时焦点仍在"确认"按钮上）→ `exitAppDirectly()`
2. 同时 `UnifiedInputSession` 的 Confirm action 调用 `executeExitConfirmSelection()` → 根据 `exitConfirmSelectedIndex` 决定操作

由于 `exitAppDirectly()` 会结束整个 Task，无论 `UnifiedInputSession` 的逻辑是否正确，app 都会退出。

## 修复方案

**方案：在 `updateExitConfirmSelection()` 中同步焦点**

每次更新 `exitConfirmSelectedIndex` 时，同步调用 `button.requestFocus()` 将 Android 焦点移动到当前选中的按钮。这样：

- 按 CLICK 时，Android 焦点系统触发的 `OnClickListener` 和应用管理的选中状态一致
- 触摸屏幕操作不受影响
- 代码改动最小，只需修改一个方法

## 修改点

### 文件

`app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

### 具体修改

在 `updateExitConfirmSelection()` 方法中，更新按钮样式后增加焦点同步：

```kotlin
private fun updateExitConfirmSelection() {
    exitConfirmButtons.forEachIndexed { index, button ->
        button.setBackgroundResource(
            if (index == exitConfirmSelectedIndex) R.drawable.glass_card_outline_selected
            else R.drawable.glass_card_outline,
        )
        button.setTextColor(getColor(R.color.green))
        button.setTypeface(
            null,
            if (index == exitConfirmSelectedIndex) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL,
        )
        // 同步焦点，确保按键事件和视觉状态一致
        if (index == exitConfirmSelectedIndex) {
            button.requestFocus()
        }
    }
}
```

## 验证方式

在眼镜设备上验证以下操作流程：

1. 进入菜单页（`AiInspectionMenuActivity`）
2. 双击（DOUBLE_CLICK）触发退出确认对话框
3. 按方向键将选中移动到"取消"按钮
4. 按 CLICK（确认键）
5. 预期结果：对话框隐藏，app 不退出
6. 再次双击触发对话框，保持选中"确认"，按 CLICK
7. 预期结果：app 退出
