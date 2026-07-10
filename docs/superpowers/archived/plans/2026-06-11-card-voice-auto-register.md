# 菜单语音指令自动注册机制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 `VoiceActionItem` 接口和 `buildPageCommonActions()` 方法，实现卡片语音指令和页面通用动作的自动注册，消除手动重复代码。

**Architecture:** 两层自动注册 — 卡片层：`MenuCardData` 实现 `VoiceActionItem` 接口，Activity 注入 onClick lambda；页面层：`UnifiedInputSession.buildPageCommonActions()` 统一提供确认/取消触发集。两者在 `buildInputActions()` 中通过 `buildList { addAll() }` 组合。

**Tech Stack:** Kotlin, Android RecyclerView, Rokid Glass SDK (离线语音)

---

### Task 1: Create VoiceActionItem interface

**Files:**
- Create: `app/src/main/java/com/rokid/glass/input/VoiceActionItem.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.rokid.glass.input

/**
 * 语音指令项接口。
 * 实现此接口的类（如菜单卡片）可被自动发现并注册为离线语音指令，
 * 无需手动在 buildInputActions() 中逐个注册。
 *
 * 使用方式：
 * 1. 数据类实现此接口，提供 labelResId 和 pinyinResId
 * 2. Activity 中通过 buildCardVoiceActions() 批量生成 InputActionSpec
 * 3. Activity 切换时由 UnifiedInputSession 自动管理生命周期
 */
interface VoiceActionItem {
    /** 语音指令文字的资源 ID（同时用作卡片的显示标签） */
    val labelResId: Int
    /** 拼音的资源 ID，命名约定: <label_resource_name>_pinyin */
    val pinyinResId: Int
    /** 语音/点击触发后执行的动作 */
    fun execute()
}
```

- [ ] **Step 2: Verify file compiles**

Run: `./gradlew :app:compileStandardDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (file has no dependencies, should compile trivially)

---

### Task 2: Add page-level common actions to UnifiedInputSession

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`

- [ ] **Step 1: Add PAGE_CONFIRM_TRIGGERS, PAGE_CANCEL_TRIGGERS, buildPageCommonActions, buildCardVoiceActions to companion object**

Locate the `companion object` block in `UnifiedInputSession` (around line 186). After the existing `buildCancelTriggers` method (around line 208), insert the following before the closing `}` of the companion object:

```kotlin
        /** 页面级确认触发集：单击 + 确认/确定/继续 */
        val PAGE_CONFIRM_TRIGGERS: List<InputTrigger> = listOf(
            InputTrigger.Touch(InputKey.CLICK),
            InputTrigger.Voice("确认", "que ren"),
            InputTrigger.Voice("确定", "que ding"),
            InputTrigger.Voice("继续", "ji xu"),
        )

        /** 页面级取消/返回触发集：返回键 + 双击 + 取消/返回 */
        val PAGE_CANCEL_TRIGGERS: List<InputTrigger> = listOf(
            InputTrigger.Touch(InputKey.BACK),
            InputTrigger.Touch(InputKey.DOUBLE_CLICK),
            InputTrigger.Voice("取消", "qu xiao"),
            InputTrigger.Voice("返回", "fan hui"),
        )

        /**
         * 为页面自动构建确认/取消两个 InputActionSpec。
         * 所有页面共享同一套触发集，只需提供各自的行为回调。
         *
         * @param onConfirm 确认回调（单击/说"确认"/"确定"/"继续"时触发）
         * @param onCancel 取消回调（双击/返回键/说"取消"/"返回"时触发）
         */
        fun buildPageCommonActions(
            onConfirm: () -> Unit,
            onCancel: () -> Unit,
        ): List<InputActionSpec> = listOf(
            InputActionSpec(
                id = InputActionId.Confirm,
                label = "确认",
                triggers = PAGE_CONFIRM_TRIGGERS,
                onTrigger = { onConfirm() },
            ),
            InputActionSpec(
                id = InputActionId.Cancel,
                label = "取消",
                triggers = PAGE_CANCEL_TRIGGERS,
                onTrigger = { onCancel() },
            ),
        )

        /**
         * 将所有 VoiceActionItem 自动转换为语音 InputActionSpec。
         * 每张卡片的 label 文字即为语音指令文字，pinyin 由配对资源提供。
         *
         * @param items 实现了 VoiceActionItem 的卡片列表
         * @param context 用于解析字符串资源
         */
        fun buildCardVoiceActions(
            items: List<VoiceActionItem>,
            context: android.content.Context,
        ): List<InputActionSpec> {
            return items.mapIndexed { index, item ->
                InputActionSpec(
                    id = InputActionId("card_voice_$index"),
                    label = context.getString(item.labelResId),
                    triggers = listOf(
                        InputTrigger.Voice(
                            command = context.getString(item.labelResId),
                            pinyin = context.getString(item.pinyinResId),
                        ),
                    ),
                    onTrigger = { item.execute() },
                )
            }
        }
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileStandardDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 3: Update MenuCardData to implement VoiceActionItem

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/adapter/MenuCardAdapter.kt`

- [ ] **Step 1: Add import for VoiceActionItem**

At the top of the file, after the existing imports (line 13), add:

```kotlin
import com.rokid.glass.input.VoiceActionItem
```

- [ ] **Step 2: Modify MenuCardData to implement VoiceActionItem**

Replace the existing `data class MenuCardData` (lines 20-24):

```kotlin
    data class MenuCardData(
        val iconResId: Int,
        override val labelResId: Int,
        override val pinyinResId: Int,
        val iconChar: String? = null,
        val onClick: (() -> Unit)? = null,
    ) : VoiceActionItem {
        override fun execute() = onClick?.invoke()
    }
```

- [ ] **Step 3: Change cards constructor parameter from private to public**

On line 17, change `private val cards` to `val cards`:

```kotlin
class MenuCardAdapter(
    val cards: List<MenuCardData>,
) : RecyclerView.Adapter<MenuCardAdapter.ViewHolder>() {
```

This exposes the cards list so `buildCardVoiceActions` can access it.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileStandardDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (no callers changed yet, all new fields have defaults)

---

### Task 4: Add pinyin string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add pinyin resources**

After the existing card label strings (around line 248), insert the pinyin counterparts:

```xml
    <!-- 主菜单卡片拼音 -->
    <string name="main_menu_card_inspection_pinyin">ji ceng ying xiao</string>
    <string name="main_menu_card_wifi_pinyin">lian jie wifi</string>
    <string name="main_menu_card_update_pinyin">jian cha geng xin</string>

    <!-- 巡检菜单卡片拼音 -->
    <string name="ai_entry_menu_analysis_pinyin">shi shi fen xi</string>
    <string name="ai_entry_menu_guide_pinyin">she bei zhi yin</string>
    <string name="ai_entry_menu_record_pinyin">yin huan pai zhao</string>
```

- [ ] **Step 2: Verify resource compilation**

Run: `./gradlew :app:generateStandardDebugResources 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 5: Update MainMenuActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/MainMenuActivity.kt`

- [ ] **Step 1: Update card definitions — add pinyinResId and onClick lambda**

Replace the `menuAdapter` lazy block (lines 103-111):

```kotlin
    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_ai_analysis,
                    labelResId = R.string.main_menu_card_inspection,
                    pinyinResId = R.string.main_menu_card_inspection_pinyin,
                    onClick = { startInspection() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_wifi,
                    labelResId = R.string.main_menu_card_wifi,
                    pinyinResId = R.string.main_menu_card_wifi_pinyin,
                    onClick = { launchWifiScannerWithPermissionCheck() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_update,
                    labelResId = R.string.main_menu_card_update,
                    pinyinResId = R.string.main_menu_card_update_pinyin,
                    onClick = { checkUpdateManually() },
                ),
            ),
        )
    }
```

- [ ] **Step 2: Simplify executeConfirmedAction**

Replace the `executeConfirmedAction` method (lines 324-331):

```kotlin
    private fun executeConfirmedAction(index: Int) {
        menuAdapter.cards.getOrNull(index)?.execute()
    }
```

- [ ] **Step 3: Rewrite buildInputActions to use buildPageCommonActions + buildCardVoiceActions**

Replace the entire `buildInputActions` method (lines 186-304):

```kotlin
    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        if (wifiRequiredDialogVisible) {
            return buildList {
                addAll(
                    UnifiedInputSession.buildPageCommonActions(
                        onConfirm = { executeWifiDialogAction() },
                        onCancel = { hideWifiRequiredDialog() },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId("wifi_dialog_switch"),
                        label = "切换焦点",
                        triggers = listOf(
                            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                        ),
                        onTrigger = { moveWifiDialogFocus() },
                    ),
                )
            }
        }
        return buildList {
            // 页面通用动作：确认→点击卡片，取消→退出应用
            addAll(
                UnifiedInputSession.buildPageCommonActions(
                    onConfirm = { onItemConfirmed(selectedIndex) },
                    onCancel = { exitAppDirectly() },
                ),
            )
            // 导航：前后滑动选卡片
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                    onTrigger = { moveSelection(-1) },
                ),
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                    onTrigger = { moveSelection(+1) },
                ),
            )
            // 卡片语音指令：自动从 VoiceActionItem 生成
            addAll(
                UnifiedInputSession.buildCardVoiceActions(
                    menuAdapter.cards,
                    this@MainMenuActivity,
                ),
            )
            // 非卡片语音指令：退出应用（独立于卡片，手动注册）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Exit,
                    label = getString(R.string.main_menu_voice_exit_app),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice(
                            getString(R.string.main_menu_voice_exit_app),
                            getString(R.string.main_menu_voice_exit_app_pinyin),
                        ),
                    ),
                    onTrigger = { exitAppDirectly() },
                ),
            )
        }
    }
```

- [ ] **Step 4: Remove unused InputActionIds import if needed**

The `InputActionId` import should remain since we still use `InputActionId.Previous`, `InputActionId.Next`, `InputActionId.Exit`, and `InputActionId("wifi_dialog_switch")`.

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :app:compileStandardDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

---

### Task 6: Update AiInspectionMenuActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

- [ ] **Step 1: Update card definitions — add pinyinResId and onClick lambda**

Replace the `menuAdapter` lazy block (lines 52-60):

```kotlin
    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_ai_analysis,
                    labelResId = R.string.ai_entry_menu_analysis,
                    pinyinResId = R.string.ai_entry_menu_analysis_pinyin,
                    onClick = { startHazardAnalysis() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_device_guide,
                    labelResId = R.string.ai_entry_menu_guide,
                    pinyinResId = R.string.ai_entry_menu_guide_pinyin,
                    onClick = { startDeviceGuide() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_hazard_record,
                    labelResId = R.string.ai_entry_menu_record,
                    pinyinResId = R.string.ai_entry_menu_record_pinyin,
                    onClick = { startActivity(Intent(this@AiInspectionMenuActivity, HazardRecordActivity::class.java)) },
                ),
            ),
        )
    }
```

- [ ] **Step 2: Simplify executeConfirmedAction**

Replace the `executeConfirmedAction` method (lines 367-374):

```kotlin
    private fun executeConfirmedAction(index: Int) {
        menuAdapter.cards.getOrNull(index)?.execute()
    }
```

- [ ] **Step 3: Rewrite buildInputActions to use buildPageCommonActions + buildCardVoiceActions**

Replace the entire `buildInputActions` method (lines 198-304):

```kotlin
    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        if (exitConfirmDialogVisible) {
            return buildList {
                addAll(
                    UnifiedInputSession.buildPageCommonActions(
                        onConfirm = { executeExitConfirmSelection() },
                        onCancel = { hideExitConfirmDialog() },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId.Previous,
                        label = "上一个",
                        triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                        onTrigger = { moveExitConfirmSelection(-1) },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId.Next,
                        label = "下一个",
                        triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                        onTrigger = { moveExitConfirmSelection(+1) },
                    ),
                )
            }
        }
        return buildList {
            // 页面通用动作：确认→点击卡片，取消→显示退出确认弹窗
            addAll(
                UnifiedInputSession.buildPageCommonActions(
                    onConfirm = { onItemConfirmed(selectedIndex) },
                    onCancel = { showExitConfirmDialog() },
                ),
            )
            // 导航：前后滑动选卡片
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                    onTrigger = { moveSelection(-1) },
                ),
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                    onTrigger = { moveSelection(+1) },
                ),
            )
            // 卡片语音指令：自动从 VoiceActionItem 生成
            addAll(
                UnifiedInputSession.buildCardVoiceActions(
                    menuAdapter.cards,
                    this@AiInspectionMenuActivity,
                ),
            )
            // 非卡片语音指令：结束巡查（手动注册）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("ai_menu_finish_inspection"),
                    label = "结束巡查",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice("结束巡查", "jie shu xun cha"),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish), "jie shu ren wu"),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish_accent_alias), "jie su ren wu"),
                    ),
                    onTrigger = { startEndReport() },
                ),
            )
            // 非卡片语音指令：检查扫码（手动注册）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("ai_menu_scan"),
                    label = "检查扫码",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice("检查扫码", "jian cha sao ma"),
                    ),
                    onTrigger = { startEnterpriseQrScan(forceScan = true) },
                ),
            )
        }
    }
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileStandardDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

---

### Task 7: Full build verification

- [ ] **Step 1: Run full assemble**

```bash
./gradlew :app:assembleStandardDebug 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Check APK output exists**

```bash
ls -la app/build/outputs/apk/standard/debug/*.apk
```
Expected: APK file exists with recent timestamp

- [ ] **Step 3: Verify APK contents**

```bash
bash scripts/android/verify-apk.sh app/build/outputs/apk/standard/debug/app-standard-debug.apk
```
Expected: Shows version 2.0.9 and certificate info

---
