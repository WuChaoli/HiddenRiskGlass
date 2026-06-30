# APK 更新优化实施计划

> **给 agentic workers 的要求：** 实施本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行。步骤使用 checkbox（`- [ ]`）追踪状态。

**目标：** 优化 APK 更新方案：控制弹窗时机（加载页不弹、扫码页和菜单页弹）、取消后本次 app 期间不再弹、弹窗不自动消失、菜单页改为 ViewPager2 横向滑动。

**架构：** 5 个任务。AppUpdateManager 增加 session 级跳过标志；移除 InspectionLoadingActivity 的自动检查；在 EnterpriseQrScanActivity 接入自动检查；AppUpdatePromptActivity 取消改为 session 跳过；AiInspectionMenuActivity 迁移到 ViewPager2 横向滑动。

**技术栈：** Kotlin、AndroidX ViewPager2、OkHttp、Gson

---

## 文件结构

- 修改 `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt` — 增加 sessionSkipped 标志 + skipCurrentSession()
- 修改 `app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt` — KEEP_SCREEN_ON 移到 onStop，子类可按需覆盖
- 修改 `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt` — 删除自动更新检查
- 修改 `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt` — 增加自动更新检查
- 修改 `app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt` — 取消 → session 跳过
- 新增 `app/src/main/java/com/rokid/glass/adapter/MenuCardAdapter.kt` — ViewPager2 卡片适配器
- 修改 `app/src/main/res/layout/activity_ai_inspection_menu.xml` — 替换横向 LinearLayout 为 ViewPager2
- 修改 `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt` — ViewPager2 接入
- 修改 `app/build.gradle` — 增加 ViewPager2 依赖

## 任务 1：AppUpdateManager session 跳过 + KEEP_SCREEN_ON 修复

**文件：**
- 修改：`app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`
- 修改：`app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt`

- [ ] **步骤 1：在 AppUpdateManager 增加 session 跳过**

在 [AppUpdateManager.kt](app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt) 中增加 session 跳过标志和方法：

```kotlin
// 在类体内、fun checkForUpdate 之前增加：
private var sessionSkipped = false

/** 本次 app 期间不再弹出更新提示（非持久化） */
fun skipCurrentSession() {
    sessionSkipped = true
}
```

- [ ] **步骤 2：在 checkForUpdate 中检查 session 跳过**

修改 `checkForUpdate()` 方法，在最前面增加 session 跳过检查：

```kotlin
// Before (~line 22):
fun checkForUpdate(ignoreSkipped: Boolean = false): AppUpdateCheckResult {
    val currentVersion = getCurrentVersionCode()
    val latest = client.fetchLatest()
    val effectiveInfo = if (
        latest.versionCode > currentVersion &&
        (ignoreSkipped || latest.mandatory || !isVersionSkipped(latest.versionCode))
    ) {

// After:
fun checkForUpdate(ignoreSkipped: Boolean = false): AppUpdateCheckResult {
    val currentVersion = getCurrentVersionCode()
    // 用户取消后在本次 app 期间不再弹出
    if (!ignoreSkipped && sessionSkipped) {
        return AppUpdateCheckResult(null, currentVersion)
    }
    val latest = client.fetchLatest()
    val effectiveInfo = if (
        latest.versionCode > currentVersion &&
        (ignoreSkipped || latest.mandatory || !isVersionSkipped(latest.versionCode))
    ) {
```

- [ ] **步骤 3：修复 BaseGlassActivity KEEP_SCREEN_ON 导致弹窗消失**

问题：`BaseGlassActivity.onPause()` 清除 `KEEP_SCREEN_ON`。当更新弹窗短暂失焦（如权限弹窗切出），屏幕会变暗或消失。

在 [BaseGlassActivity.kt](app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt) 中，将 `KEEP_SCREEN_ON` 逻辑改为 `open` 方法，子类可覆盖：

```kotlin
// Before (~line 75-84):
override fun onResume() {
    Log.e("startActivity",this.javaClass.name)
    super.onResume()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

override fun onPause() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    super.onPause()
}

// After: 新增 open 属性，子类按需控制
/** 子类可覆盖为 false 以禁用 KEEP_SCREEN_ON */
protected open val keepScreenOnEnabled: Boolean
    get() = true

override fun onResume() {
    Log.e("startActivity", this.javaClass.name)
    super.onResume()
    if (keepScreenOnEnabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

override fun onPause() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    super.onPause()
}
```

- [ ] **步骤 4：在 AppUpdatePromptActivity 中禁用自动息屏**

在 [AppUpdatePromptActivity.kt](app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt) 中覆盖 `keepScreenOnEnabled`：

```kotlin
// 在类体内增加：
override val keepScreenOnEnabled: Boolean
    get() = false
```

这样更新弹窗不会被 Glass 自动息屏逻辑影响。

- [ ] **步骤 5：编译验证**

```bash
./gradlew :app:compileStandardDebugKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 6：提交**

```bash
git add app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/BaseGlassActivity.kt \
        app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt
git commit -m "feat: session skip + keep screen on fix

- AppUpdateManager 增加 sessionSkipped，取消后本次 app 期间不弹
- BaseGlassActivity KEEP_SCREEN_ON 改为可被子类覆盖
- AppUpdatePromptActivity 禁用自动息屏"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## 任务 2：移除 InspectionLoadingActivity 自动更新检查

**文件：**
- 修改：`app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`

- [ ] **步骤 1：删除更新相关字段**

删除以下三行（在 ~line 109-111）：

```kotlin
// 删除：
    private val updateCheckExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var autoUpdateCheckStarted = false
```

- [ ] **步骤 2：删除 startAutoUpdateCheck() 方法调用**

在 `onCreate()` 中删除 `startAutoUpdateCheck()` 调用（~line 191）：

```kotlin
// 删除这一行：
        startAutoUpdateCheck()
```

- [ ] **步骤 3：删除 startAutoUpdateCheck() 方法体**

删除整个 `startAutoUpdateCheck()` 方法（~line 231-250）。

- [ ] **步骤 4：删除 updateCheckExecutor.shutdownNow()**

在 `onDestroy()` 中删除（~line 275）：

```kotlin
// 删除这一行：
        updateCheckExecutor.shutdownNow()
```

- [ ] **步骤 5：删除不再使用的 imports**

删除：

```kotlin
// 删除这些 import：
import com.google.gson.Gson
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import java.io.IOException
```

注意：仅删除 InspectionLoadingActivity 中**不再被其他代码使用**的 import。检查文件确认 Gson、AppUpdateManager、AppUpdatePromptActivity、IOException 是否在文件其他位置仍有引用；如果没有则全部删除。

- [ ] **步骤 6：编译验证**

```bash
./gradlew :app:compileStandardDebugKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 7：提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt
git commit -m "refactor: 移除 InspectionLoadingActivity 自动更新检查

弹窗时机控制：加载页不再弹更新提示，避免抢占加载流程。"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## 任务 3：EnterpriseQrScanActivity 接入自动更新检查

**文件：**
- 修改：`app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`

- [ ] **步骤 1：增加 imports**

在 [EnterpriseQrScanActivity.kt](app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt) 的 import 区增加：

```kotlin
import com.google.gson.Gson
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import java.io.IOException
import java.util.concurrent.Executors
```

- [ ] **步骤 2：增加字段**

在类体内，`private val inputSession` 之前增加：

```kotlin
    private val updateCheckExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var autoUpdateChecked = false
```

- [ ] **步骤 3：在 onResume 中触发自动检查**

在 `onResume()` 方法末尾（`startStatusBarUpdates()` 调用之后）增加：

```kotlin
    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        startStatusBarUpdates()
        if (debugSnapshotMode) return
        startAutoUpdateCheck()
    }
```

实际插入位置请读取当前 `onResume()` 确认。关键点：`startAutoUpdateCheck()` 必须放在 `if (debugSnapshotMode) return` **之后**，确保 debug 模式不触发。

- [ ] **步骤 4：增加 startAutoUpdateCheck() 方法**

在类体内增加：

```kotlin
    private fun startAutoUpdateCheck() {
        if (autoUpdateChecked) return
        autoUpdateChecked = true
        updateCheckExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = false)
                if (!result.hasUpdate || result.info == null) return@execute
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    startActivity(
                        Intent(this, AppUpdatePromptActivity::class.java).apply {
                            putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, Gson().toJson(result.info))
                        },
                    )
                }
            } catch (error: IOException) {
                Log.i(TAG, "auto update check skipped: ${error.message}")
            }
        }
    }
```

- [ ] **步骤 5：在 onDestroy 中释放线程**

在 `onDestroy()` 中合适位置增加：

```kotlin
        updateCheckExecutor.shutdownNow()
```

注意：不要放到 `destroyed = true` 之前。

- [ ] **步骤 6：编译验证**

```bash
./gradlew :app:compileStandardDebugKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 7：提交**

```bash
git add app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt
git commit -m "feat: EnterpriseQrScanActivity 接入自动更新检查

扫码页在 onResume 时检查更新，不抢占扫码主流程。"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## 任务 4：AppUpdatePromptActivity 取消 → session 跳过

**文件：**
- 修改：`app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt`

- [ ] **步骤 1：将取消动作改为 session 跳过**

将 skip 动作中 `skipVersion` 调用改为 `skipCurrentSession`：

```kotlin
// Before (~line 85):
                    updateManager.skipVersion(updateInfo.versionCode)

// After:
                    updateManager.skipCurrentSession()
```

- [ ] **步骤 2：编译验证**

```bash
./gradlew :app:compileStandardDebugKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 3：提交**

```bash
git add app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt
git commit -m "fix: 取消更新改为 session 级别跳过

skipCurrentSession 仅本次 app 期间有效，重启后恢复检查。"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## 任务 5：菜单页 ViewPager2 横向滑动

**文件：**
- 修改：`app/build.gradle`
- 新增：`app/src/main/java/com/rokid/glass/adapter/MenuCardAdapter.kt`
- 修改：`app/src/main/res/layout/activity_ai_inspection_menu.xml`
- 修改：`app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

- [ ] **步骤 1：增加 ViewPager2 依赖**

在 `app/build.gradle` 的 `dependencies` 块中增加：

```groovy
    implementation "androidx.viewpager2:viewpager2:1.0.0"
```

- [ ] **步骤 2：创建菜单卡片适配器**

创建 [MenuCardAdapter.kt](app/src/main/java/com/rokid/glass/adapter/MenuCardAdapter.kt)：

```kotlin
package com.rokid.glass.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glesse.R

/** ViewPager2 菜单卡片适配器 */
class MenuCardAdapter(
    private val cards: List<MenuCardData>,
    private val onItemClick: (Int) -> Unit,
) : RecyclerView.Adapter<MenuCardAdapter.ViewHolder>() {

    data class MenuCardData(
        val iconResId: Int,
        val labelResId: Int,
        val iconChar: String? = null, // 仅文本图标（如"↻"）使用
    )

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: FrameLayout = itemView.findViewById(R.id.itemCard)
        val icon: ImageView = itemView.findViewById(R.id.ivCardIcon)
        val iconText: TextView = itemView.findViewById(R.id.tvCardIconText)
        val label: TextView = itemView.findViewById(R.id.tvCardLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        if (card.iconChar != null) {
            holder.iconText.visibility = View.VISIBLE
            holder.iconText.text = card.iconChar
            holder.icon.visibility = View.GONE
        } else {
            holder.iconText.visibility = View.GONE
            holder.icon.setImageResource(card.iconResId)
            holder.icon.visibility = View.VISIBLE
        }
        holder.label.setText(card.labelResId)
        holder.card.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount(): Int = cards.size
}
```

- [ ] **步骤 3：创建单个卡片 layout 资源**

创建 [item_menu_card.xml](app/src/main/res/layout/item_menu_card.xml)：

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/itemCard"
    android:layout_width="140dp"
    android:layout_height="140dp"
    android:background="@drawable/glass_menu_card">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical">

        <ImageView
            android:id="@+id/ivCardIcon"
            android:layout_width="52dp"
            android:layout_height="52dp"
            android:src="@drawable/ic_menu_ai_analysis"
            android:visibility="visible" />

        <TextView
            android:id="@+id/tvCardIconText"
            android:layout_width="52dp"
            android:layout_height="52dp"
            android:gravity="center"
            android:text="↻"
            android:textColor="@color/green"
            android:textSize="34sp"
            android:visibility="gone" />

        <TextView
            android:id="@+id/tvCardLabel"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:textColor="@color/green"
            android:textSize="16sp" />
    </LinearLayout>
</FrameLayout>
```

- [ ] **步骤 4：修改 activity_ai_inspection_menu.xml**

将整个横向 `LinearLayout` 区域替换为 `ViewPager2`：

```xml
<!-- Before: 横向 LinearLayout 包含 4 个 FrameLayout 卡片 -->

<!-- After: 替换为 ViewPager2 -->
<androidx.viewpager2.widget.ViewPager2
    android:id="@+id/viewPagerMenu"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="24dp"
    android:clipToPadding="false"
    android:paddingHorizontal="60dp" />
```

注意：删除原来 4 个 FrameLayout 卡片节点（`itemHazardAnalysis`、`itemDeviceGuide`、`itemHazardRecord`、`itemUpdateCheck`），它们现在由适配器动态创建。保留 `tvBottomHint`、`layoutBottomVoiceHint`、`GlassStatusBar`。

- [ ] **步骤 5：添加 PageTransformer 实现居中+缩放效果**

在 [AiInspectionMenuActivity.kt](app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt) 中增加内部类或在 `onCreate` 中设置：

```kotlin
    // 在 onCreate 中设置：
    viewPagerMenu.offscreenPageLimit = 1
    viewPagerMenu.setPageTransformer(CenterZoomPageTransformer())

    // 在类内增加：
    private class CenterZoomPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = abs(position)
            page.translationX = 0f
            // 非当前页缩小到 85%
            val scale = 1f - 0.15f * absPos.coerceAtMost(1f)
            page.scaleX = scale
            page.scaleY = scale
            // 非当前页降低透明度
            page.alpha = 1f - 0.3f * absPos.coerceAtMost(1f)
        }
    }
```

- [ ] **步骤 6：重写 AiInspectionMenuActivity 接入 ViewPager2**

关键改动：

```kotlin
// 字段变更：
// 删除：itemHazardAnalysis, itemHazardRecord, itemDeviceGuide, itemUpdateCheck (4 个 FrameLayout 字段)
// 删除：lateinit var items: List<FrameLayout>
// 删除：var selectedIndex = 0

// 新增：
private lateinit var viewPagerMenu: ViewPager2
private val menuAdapter by lazy {
    MenuCardAdapter(
        cards = listOf(
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.ai_entry_menu_analysis),
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_device_guide, R.string.ai_entry_menu_guide),
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_hazard_record, R.string.ai_entry_menu_record),
            MenuCardAdapter.MenuCardData(0, R.string.ai_entry_menu_update, iconChar = "↻"),
        ),
        onItemClick = { position -> onItemConfirmed(position) },
    )
}
```

在 `onCreate` 中：

```kotlin
// 删除原来的 4 个 findViewById：
// itemHazardAnalysis = findViewById(R.id.itemHazardAnalysis)
// itemHazardRecord = findViewById(R.id.itemHazardRecord)
// itemDeviceGuide = findViewById(R.id.itemDeviceGuide)
// itemUpdateCheck = findViewById(R.id.itemUpdateCheck)

// 替换为：
viewPagerMenu = findViewById(R.id.viewPagerMenu)
```

设置 ViewPager2：

```kotlin
// 在 onCreate 中（findViewById 之后）：
viewPagerMenu.adapter = menuAdapter
viewPagerMenu.offscreenPageLimit = 1
viewPagerMenu.setPageTransformer(CenterZoomPageTransformer())
// 默认选中第一项（实时分析）
viewPagerMenu.setCurrentItem(0, false)

// 监听页面切换，同步底部提示和高亮
viewPagerMenu.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
        updateSelection(position)
    }
})
```

修改 `updateSelection()` 方法：

```kotlin
// Before:
private fun updateSelection() {
    items.forEachIndexed { index, item ->
        item.setBackgroundResource(
            if (index == selectedIndex) R.drawable.glass_menu_card_selected
            else R.drawable.glass_menu_card,
        )
    }
    tvBottomHint.text = getString(R.string.ai_entry_menu_hint)
}

// After: 移除手动高亮逻辑（PageTransformer 已通过缩放和透明度表达选中态）
private fun updateSelection(position: Int) {
    tvBottomHint.text = getString(R.string.ai_entry_menu_hint)
}
```

修改 `onItemConfirmed()`：

```kotlin
// Before:
private fun onItemConfirmed(index: Int) {
    when (index) {
        0 -> startHazardAnalysis()
        ...

// After: index 现在来自 ViewPager2 的 currentItem
private fun onItemConfirmed(index: Int) {
    when (index) {
        0 -> startHazardAnalysis()
        1 -> startDeviceGuide()
        2 -> startActivity(Intent(this, HazardRecordActivity::class.java))
        3 -> checkUpdateManually()
        else -> Unit
    }
}
// 注意：onItemConfirmed 内部逻辑不变，只是 index 来源从 selectedIndex 改为 ViewPager2.currentItem
```

修改触控输入（FRONT/BEHIND 切换页面）：

```kotlin
// 在 buildInputActions() 中：
// Before:
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Previous,
    label = "上一个",
    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
) {
    selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
    updateSelection()
},
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Next,
    label = "下一个",
    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
) {
    selectedIndex = (selectedIndex + 1).coerceAtMost(items.lastIndex)
    updateSelection()
},

// After: 用 ViewPager2.currentItem 切换
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Previous,
    label = "上一个",
    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
) {
    val current = viewPagerMenu.currentItem
    if (current > 0) viewPagerMenu.setCurrentItem(current - 1, true)
},
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Next,
    label = "下一个",
    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
) {
    val current = viewPagerMenu.currentItem
    if (current < menuAdapter.itemCount - 1) viewPagerMenu.setCurrentItem(current + 1, true)
},
```

修改 `Confirm` 动作：

```kotlin
// Before:
onItemConfirmed(selectedIndex)

// After:
onItemConfirmed(viewPagerMenu.currentItem)
```

语音输入动作不变（仍然触发 onItemConfirmed 对应索引）。

- [ ] **步骤 7：编译验证**

```bash
./gradlew :app:compileStandardDebugKotlin
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 8：提交**

```bash
git add app/build.gradle \
        app/src/main/java/com/rokid/glass/adapter/MenuCardAdapter.kt \
        app/src/main/res/layout/item_menu_card.xml \
        app/src/main/res/layout/activity_ai_inspection_menu.xml \
        app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt
git commit -m "feat: menu 页迁移到 ViewPager2 横向滑动

- 4 个卡片恢复原来尺寸，ViewPager2 居中+缩放选中效果
- FRONT/BEHIND 手势滑动切换卡片
- 选中卡片在中间，非选中缩放+降低透明度"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## 自检

- 规格覆盖：任务 1 覆盖 session 跳过 + 弹窗不自动消失；任务 2 移除加载页检查；任务 3 接入扫码页检查；任务 4 取消 → session 跳过；任务 5 ViewPager2 迁移
- 范围控制：不涉及后台更新、HTTPS、远程配置、多渠道
- 类型一致性：MenuCardAdapter.MenuCardData 的 iconChar 与 iconResId 互斥使用；ViewPager2.currentItem 替代 selectedIndex
- 风险说明：ViewPager2 是新增依赖，需确保眼镜端渲染性能可接受。如 ViewPager2 在 Glass 上滑动不流畅，可降级为手动触控切换
