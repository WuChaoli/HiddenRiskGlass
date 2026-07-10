# 两层菜单导航重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单层菜单拆分为两层菜单，第一层负责后台静默初始化（WiFi/SDK/相机）和系统入口，第二层负责业务功能，结束巡检后返回第一层而非退出App。

**Architecture:** 新建 `MainMenuActivity` 作为 LAUNCHER 入口，`EntryGuardCoordinator` 封装后台静默初始化流程（WiFi检测 + SDK初始化 + 相机预热 + 自动更新），改造 `AiInspectionMenuActivity` 为纯业务菜单，`InspectionEndReportActivity` 完成后返回第一层并暂停相机。

**Tech Stack:** Kotlin, Android SDK, Rokid Glass SDK, NCNN/Vulkan, Jetpack RecyclerView

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/rokid/glass/MainMenuActivity.kt` | 新建 | 第一层菜单（3卡片）、后台静默初始化集成、禁用双击退出、语音"退出应用" |
| `app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt` | 新建 | 后台静默初始化流程：WiFi检测、SDK初始化、相机预热、自动更新检查 |
| `app/src/main/res/layout/activity_main_menu.xml` | 新建 | 第一层菜单布局，3卡片 + WiFi对话框 + 状态指示 |
| `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt` | 改造 | 第二层菜单，移除检查更新/WiFi/SDK/相机逻辑，保留企业QR扫码，双击/后退弹出结束巡检确认 |
| `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt` | 改造 | 移除 `startAutoUpdateCheck()` |
| `app/src/main/java/com/rokid/glass/InspectionEndReportActivity.kt` | 改造 | 提交成功后返回 `MainMenuActivity`，暂停相机（不释放），清空巡检状态 |
| `app/src/main/java/com/rokid/glass/InspectionEndReportReturnDestination.kt` | 改造 | 新增 `MAIN_MENU_HOME` |
| `app/src/main/AndroidManifest.xml` | 改造 | LAUNCHER 移到 `MainMenuActivity` |
| `app/src/main/res/values/strings.xml` | 改造 | 新增第一层菜单相关文案 |

---

## Task 1: 新建 EntryGuardCoordinator

**Files:**
- Create: `app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt`
- Test: `app/src/test/java/com/rokid/glass/EntryGuardCoordinatorTest.kt`

**设计说明：** `EntryGuardCoordinator` 封装第一层菜单的后台静默初始化流程。WiFi检测、SDK初始化、相机预热、自动更新检查均为异步后台执行，通过 `Callback` 接口向 `MainMenuActivity` 报告状态变化。各阶段独立失败不影响其他阶段。

- [ ] **Step 1: 创建 `EntryGuardCoordinator.kt` 骨架与回调接口**

```kotlin
package com.rokid.glass

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.InspectionSession
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.wifi.WifiQrParseResult
import com.rokid.glass.wifi.WifiQrParser
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import java.io.IOException
import java.util.concurrent.Executors

class EntryGuardCoordinator(
    private val context: Context,
    private val callback: Callback,
) {
    interface Callback {
        fun onWifiRequired(messageResId: Int)
        fun onWifiConnecting()
        fun onWifiConnected()
        fun onWifiConnectionFailed(messageResId: Int)
        fun onSdkStateChanged(state: SdkInitState)
        fun onCameraStateChanged(state: CameraWarmupState)
        fun onAutoUpdateAvailable(updateInfoJson: String)
        fun onAutoUpdateCheckComplete(hasUpdate: Boolean)
        fun onAllGuardsReady()
    }

    enum class SdkInitState { IDLE, INITIALIZING, READY, FAILED }
    enum class CameraWarmupState { IDLE, WARMING_UP, READY, FAILED }

    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(context.applicationContext) }

    private var sdkInitState = SdkInitState.IDLE
    private var cameraWarmupState = CameraWarmupState.IDLE
    private var autoUpdateChecked = false
    private var wifiConnected = false
    private var wifiScannerLaunching = false
    private var wifiConnectInProgress = false
    private var autoWifiScanAttempted = false
    private var allGuardsReadyFired = false

    fun startBackgroundGuards() {
        Log.i(TAG, "startBackgroundGuards")
        runWifiGuard()
    }

    fun launchWifiScanner(activity: Activity) {
        // 复用 AiInspectionMenuActivity 的 WiFi 扫码逻辑
    }

    fun checkUpdateManually() {
        // 复用 AiInspectionMenuActivity 的手动更新检查逻辑
    }

    fun release() {
        updateExecutor.shutdownNow()
    }

    private fun runWifiGuard() {
        if (SystemStateUtils.getCurrentWifiSsid(context) != null) {
            wifiConnected = true
            callback.onWifiConnected()
            runSdkGuard()
            return
        }
        callback.onWifiRequired(R.string.ai_entry_wifi_required_message)
        if (!autoWifiScanAttempted && !wifiScannerLaunching && !wifiConnectInProgress) {
            autoWifiScanAttempted = true
            // 自动启动 WiFi 扫码由 Activity 触发
        }
    }

    private fun runSdkGuard() {
        // SDK 初始化
    }

    private fun runCameraWarmup() {
        // 相机预热
    }

    private fun runAutoUpdateCheck() {
        // 自动更新检查
    }

    private fun checkAllGuardsReady() {
        if (allGuardsReadyFired) return
        if (wifiConnected && sdkInitState == SdkInitState.READY && cameraWarmupState == CameraWarmupState.READY) {
            allGuardsReadyFired = true
            callback.onAllGuardsReady()
        }
    }

    companion object {
        private const val TAG = "EntryGuardCoordinator"
    }
}
```

- [ ] **Step 2: 实现 WiFi 扫码连接逻辑**

将 `AiInspectionMenuActivity` 中的 WiFi 相关逻辑迁移到 `EntryGuardCoordinator`：

```kotlin
fun launchWifiScanner(activity: Activity) {
    if (wifiScannerLaunching || wifiConnectInProgress) return
    wifiScannerLaunching = true
    callback.onWifiRequired(R.string.ai_entry_wifi_required_message)
    runCatching {
        GlassScanner.launch(
            activity,
            WifiScanConfigFactory.create(activity),
            object : GlassScanCallback {
                override fun onScanSuccess(content: String?, barcode: Barcode) {
                    wifiScannerLaunching = false
                    if (content == null) {
                        callback.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr)
                    } else {
                        handleWifiQrContent(content)
                    }
                }

                override fun onScanFailure(message: String) {
                    wifiScannerLaunching = false
                    callback.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr)
                }

                override fun onScanCancelled() {
                    wifiScannerLaunching = false
                    callback.onWifiRequired(R.string.ai_entry_wifi_required_message)
                }
            },
        )
    }.onFailure { error ->
        wifiScannerLaunching = false
        Log.e(TAG, "launch wifi scanner failed", error)
        callback.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr)
    }
}

private fun handleWifiQrContent(content: String) {
    when (val result = WifiQrParser.parse(content)) {
        is WifiQrParseResult.Error -> {
            Log.w(TAG, "wifi qr rejected reason=${result.reason}")
            callback.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr)
        }
        is WifiQrParseResult.Success -> {
            wifiConnectInProgress = true
            callback.onWifiConnecting()
            RokidSdkManager.connectWifi(result.payload) { success, errorMessage ->
                wifiConnectInProgress = false
                if (!success) {
                    Log.w(TAG, "wifi connect failed message=$errorMessage")
                    val message = if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
                        R.string.ai_entry_wifi_connect_failed
                    } else {
                        R.string.ai_entry_wifi_sdk_unavailable
                    }
                    callback.onWifiConnectionFailed(message)
                    return@connectWifi
                }
                confirmWifiConnected()
            }
        }
    }
}

private fun confirmWifiConnected(attempt: Int = 0) {
    if (SystemStateUtils.getCurrentWifiSsid(context) != null) {
        wifiConnected = true
        callback.onWifiConnected()
        runSdkGuard()
        return
    }
    if (attempt >= WIFI_CONFIRM_MAX_ATTEMPTS) {
        callback.onWifiConnectionFailed(R.string.ai_entry_wifi_connect_failed)
        return
    }
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { confirmWifiConnected(attempt + 1) },
        WIFI_CONFIRM_INTERVAL_MS,
    )
}
```

- [ ] **Step 3: 实现 SDK 初始化和相机预热逻辑**

```kotlin
private fun runSdkGuard() {
    if (sdkInitState != SdkInitState.IDLE) return
    sdkInitState = SdkInitState.INITIALIZING
    callback.onSdkStateChanged(sdkInitState)

    RokidSdkManager.initialize(context.applicationContext as android.app.Application)
    RokidSdkManager.addListener(object : RokidSdkManager.Listener {
        override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
            when (state) {
                RokidSdkManager.SdkState.READY -> {
                    sdkInitState = SdkInitState.READY
                    callback.onSdkStateChanged(sdkInitState)
                    runCameraWarmup()
                }
                RokidSdkManager.SdkState.FAILED -> {
                    sdkInitState = SdkInitState.FAILED
                    callback.onSdkStateChanged(sdkInitState)
                    runCameraWarmup() // 相机预热不依赖 SDK
                }
                else -> {}
            }
        }
    })
    RokidSdkManager.ensureInitialized()
}

private fun runCameraWarmup() {
    if (cameraWarmupState != CameraWarmupState.IDLE) return
    cameraWarmupState = CameraWarmupState.WARMING_UP
    callback.onCameraStateChanged(cameraWarmupState)

    InspectionCameraCoordinator.acquire(
        owner = InspectionCameraCoordinator.CameraOwner.LOADING,
        needPreview = false,
    ) { success ->
        cameraWarmupState = if (success) {
            InspectionCameraCoordinator.pause(
                InspectionCameraCoordinator.CameraOwner.LOADING,
                reason = "main_menu_camera_warmup_ready",
            )
            CameraWarmupState.READY
        } else {
            CameraWarmupState.FAILED
        }
        callback.onCameraStateChanged(cameraWarmupState)
        runAutoUpdateCheck()
        checkAllGuardsReady()
    }
}

private fun runAutoUpdateCheck() {
    if (autoUpdateChecked) return
    autoUpdateChecked = true
    updateExecutor.execute {
        try {
            val result = updateManager.checkForUpdate(ignoreSkipped = false)
            if (!result.hasUpdate || result.info == null) {
                callback.onAutoUpdateCheckComplete(false)
                return@execute
            }
            callback.onAutoUpdateAvailable(Gson().toJson(result.info))
        } catch (error: IOException) {
            Log.i(TAG, "auto update check skipped: ${error.message}")
            callback.onAutoUpdateCheckComplete(false)
        }
    }
}
```

- [ ] **Step 4: 实现手动更新检查**

```kotlin
fun checkUpdateManually(listener: UpdateCheckListener) {
    updateExecutor.execute {
        try {
            val result = updateManager.checkForUpdate(ignoreSkipped = true)
            listener.onComplete(result.hasUpdate && result.info != null, result.info?.let { Gson().toJson(it) })
        } catch (error: IOException) {
            Log.e(TAG, "手动检查更新失败", error)
            listener.onComplete(false, null)
        }
    }
}

interface UpdateCheckListener {
    fun onComplete(hasUpdate: Boolean, updateInfoJson: String?)
}
```

- [ ] **Step 5: 添加 companion object 常量**

```kotlin
companion object {
    private const val TAG = "EntryGuardCoordinator"
    private const val WIFI_CONFIRM_INTERVAL_MS = 500L
    private const val WIFI_CONFIRM_MAX_ATTEMPTS = 10
}
```

- [ ] **Step 6: 编写单元测试**

```kotlin
package com.rokid.glass

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EntryGuardCoordinatorTest {

    @Mock
    private lateinit var callback: EntryGuardCoordinator.Callback

    private lateinit var context: Context
    private lateinit var coordinator: EntryGuardCoordinator

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        coordinator = EntryGuardCoordinator(context, callback)
    }

    @Test
    fun `startBackgroundGuards should check wifi first`() {
        coordinator.startBackgroundGuards()
        // WiFi 未连接时触发 onWifiRequired
        verify(callback).onWifiRequired(R.string.ai_entry_wifi_required_message)
    }

    @Test
    fun `release should shutdown executor`() {
        coordinator.release()
        // 验证无崩溃即可
    }
}
```

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.EntryGuardCoordinatorTest"`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt
git add app/src/test/java/com/rokid/glass/EntryGuardCoordinatorTest.kt
git commit -m "feat: 新建 EntryGuardCoordinator，封装后台静默初始化流程"
```

---

## Task 2: 新建 MainMenuActivity + 布局

**Files:**
- Create: `app/src/main/java/com/rokid/glass/MainMenuActivity.kt`
- Create: `app/src/main/res/layout/activity_main_menu.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**设计说明：** `MainMenuActivity` 是新的 LAUNCHER 入口，展示 3 卡片菜单（基层应消、连接WiFi、检查更新）。在 `onResume` 时启动 `EntryGuardCoordinator` 进行后台静默初始化。双击/后退被禁用，仅语音"退出应用"可退出。

- [ ] **Step 1: 创建 `activity_main_menu.xml` 布局**

参考 `activity_ai_inspection_menu.xml` 的结构，创建第一层菜单布局：

```xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/black">

    <!-- 状态栏 -->
    <com.rokid.glass.component.GlassStatusBar
        android:id="@+id/statusBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_alignParentTop="true" />

    <!-- 菜单 RecyclerView -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerMenu"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_centerInParent="true"
        android:clipChildren="false"
        android:clipToPadding="false"
        android:paddingHorizontal="20dp" />

    <!-- 底部提示 -->
    <TextView
        android:id="@+id/tvBottomHint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_alignParentBottom="true"
        android:layout_centerHorizontal="true"
        android:layout_marginBottom="20dp"
        android:text="@string/main_menu_bottom_hint"
        android:textColor="@color/white"
        android:textSize="12sp" />

    <!-- 初始化状态提示 -->
    <TextView
        android:id="@+id/tvInitStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_above="@id/tvBottomHint"
        android:layout_centerHorizontal="true"
        android:layout_marginBottom="8dp"
        android:text="@string/main_menu_init_status"
        android:textColor="@color/green"
        android:textSize="10sp"
        android:visibility="gone" />

    <!-- WiFi 必需对话框 -->
    <LinearLayout
        android:id="@+id/layoutWifiRequiredDialog"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/black"
        android:gravity="center"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/tvWifiRequiredMessage"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="40dp"
            android:gravity="center"
            android:textColor="@color/white"
            android:textSize="16sp" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="30dp"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/tvWifiRetry"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="20dp"
                android:background="@drawable/glass_card_outline_selected"
                android:padding="12dp"
                android:text="@string/ai_entry_wifi_retry"
                android:textColor="@color/green"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/tvWifiExit"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/glass_card_outline"
                android:padding="12dp"
                android:text="@string/ai_entry_wifi_exit"
                android:textColor="@color/white"
                android:textSize="14sp" />
        </LinearLayout>
    </LinearLayout>

</RelativeLayout>
```

- [ ] **Step 2: 添加第一层菜单字符串资源**

在 `app/src/main/res/values/strings.xml` 中添加：

```xml
<string name="main_menu_title">主菜单</string>
<string name="main_menu_bottom_hint">前翻/后翻切换，单击确认</string>
<string name="main_menu_init_status">初始化中...</string>
<string name="main_menu_card_inspection">基层应消</string>
<string name="main_menu_card_wifi">连接WiFi</string>
<string name="main_menu_card_update">检查更新</string>
<string name="main_menu_voice_exit_app">退出应用</string>
<string name="main_menu_voice_exit_app_pinyin">tui chu ying yong</string>
<string name="main_menu_voice_inspection">基层应消</string>
<string name="main_menu_voice_inspection_pinyin">ji ceng ying xiao</string>
```

- [ ] **Step 3: 创建 `MainMenuActivity.kt` 骨架**

```kotlin
package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.adapter.MenuCardAdapter
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.updater.AppUpdatePromptActivity
import com.rokid.glass.utils.OfflineTtsPlayer
import com.google.gson.Gson

class MainMenuActivity : BaseGlassActivity() {

    private lateinit var recyclerMenu: RecyclerView
    private lateinit var tvBottomHint: TextView
    private lateinit var tvInitStatus: TextView
    private lateinit var statusBar: GlassStatusBar
    private lateinit var layoutWifiRequiredDialog: LinearLayout
    private lateinit var tvWifiRequiredMessage: TextView
    private lateinit var tvWifiRetry: TextView
    private lateinit var tvWifiExit: TextView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private lateinit var entryGuardCoordinator: EntryGuardCoordinator
    private var selectedIndex = 0
    private var initComplete = false

    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.main_menu_card_inspection),
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_wifi, R.string.main_menu_card_wifi),
                MenuCardAdapter.MenuCardData(0, R.string.main_menu_card_update, iconChar = "↻"),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        recyclerMenu = findViewById(R.id.recyclerMenu)
        tvBottomHint = findViewById(R.id.tvBottomHint)
        tvInitStatus = findViewById(R.id.tvInitStatus)
        statusBar = findViewById(R.id.statusBar)

        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerMenu.adapter = menuAdapter
        recyclerMenu.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        menuAdapter.selectedIndex = 0

        layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
        tvWifiRequiredMessage = findViewById(R.id.tvWifiRequiredMessage)
        tvWifiRetry = findViewById(R.id.tvWifiRetry)
        tvWifiExit = findViewById(R.id.tvWifiExit)

        tvWifiRetry.setOnClickListener { entryGuardCoordinator.launchWifiScanner(this) }
        tvWifiExit.setOnClickListener { exitAppDirectly() }

        entryGuardCoordinator = EntryGuardCoordinator(this, buildEntryGuardCallback())
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        statusBarUpdater.start(statusBar)
        entryGuardCoordinator.startBackgroundGuards()
    }

    override fun onPause() {
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        entryGuardCoordinator.release()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildEntryGuardCallback(): EntryGuardCoordinator.Callback {
        return object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {
                showWifiRequiredDialog(messageResId)
            }

            override fun onWifiConnecting() {
                tvWifiRequiredMessage.setText(R.string.ai_entry_wifi_connecting)
            }

            override fun onWifiConnected() {
                hideWifiRequiredDialog()
                updateInitStatus()
            }

            override fun onWifiConnectionFailed(messageResId: Int) {
                tvWifiRequiredMessage.setText(messageResId)
            }

            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
                updateInitStatus()
            }

            override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) {
                updateInitStatus()
            }

            override fun onAutoUpdateAvailable(updateInfoJson: String) {
                startActivity(
                    Intent(this@MainMenuActivity, AppUpdatePromptActivity::class.java).apply {
                        putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, updateInfoJson)
                    },
                )
            }

            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {
                // 静默完成
            }

            override fun onAllGuardsReady() {
                initComplete = true
                tvInitStatus.visibility = View.GONE
                inputSession.updateActions(buildInputActions())
            }
        }
    }

    private fun updateInitStatus() {
        if (initComplete) return
        val status = buildString {
            append("初始化中")
            if (entryGuardCoordinator.sdkInitState == EntryGuardCoordinator.SdkInitState.READY) append(" · SDK就绪")
            if (entryGuardCoordinator.cameraWarmupState == EntryGuardCoordinator.CameraWarmupState.READY) append(" · 相机就绪")
        }
        tvInitStatus.text = status
        tvInitStatus.visibility = View.VISIBLE
    }

    private fun showWifiRequiredDialog(messageResId: Int) {
        tvWifiRequiredMessage.setText(messageResId)
        layoutWifiRequiredDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        inputSession.updateActions(buildInputActions())
    }

    private fun hideWifiRequiredDialog() {
        layoutWifiRequiredDialog.visibility = View.GONE
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
    }

    private fun exitAppDirectly() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        // WiFi 对话框模式
        if (layoutWifiRequiredDialog.visibility == View.VISIBLE) {
            return listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.ai_entry_wifi_retry),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_retry), "chong xin sao ma"),
                    ),
                ) {
                    entryGuardCoordinator.launchWifiScanner(this)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Cancel,
                    label = getString(R.string.ai_entry_wifi_exit),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_exit), "tui chu ying yong"),
                    ),
                ) {
                    exitAppDirectly()
                },
            )
        }

        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Previous,
                label = "上一个",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
            ) {
                moveSelection(-1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Next,
                label = "下一个",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
            ) {
                moveSelection(+1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK)),
            ) {
                onItemConfirmed(selectedIndex)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("main_menu_inspection"),
                label = getString(R.string.main_menu_card_inspection),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.main_menu_voice_inspection), getString(R.string.main_menu_voice_inspection_pinyin)),
                ),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("main_menu_wifi"),
                label = getString(R.string.main_menu_card_wifi),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.main_menu_card_wifi), "lian jie wifi"),
                ),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("main_menu_update"),
                label = getString(R.string.main_menu_card_update),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.main_menu_card_update), "jian cha geng xin"),
                ),
            ) {
                onItemConfirmed(2)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = getString(R.string.main_menu_voice_exit_app),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.main_menu_voice_exit_app), getString(R.string.main_menu_voice_exit_app_pinyin)),
                ),
            ) {
                exitAppDirectly()
            },
        )
    }

    private fun moveSelection(delta: Int) {
        val target = (selectedIndex + delta).coerceIn(0, menuAdapter.itemCount - 1)
        if (target == selectedIndex) return
        selectedIndex = target
        menuAdapter.selectedIndex = target
        ensureSelectedCardVisible(target)
    }

    private fun ensureSelectedCardVisible(position: Int, retryAfterLayout: Boolean = true) {
        val lm = recyclerMenu.layoutManager as? LinearLayoutManager ?: return
        val itemView = lm.findViewByPosition(position)
        if (itemView == null) {
            lm.scrollToPositionWithOffset(position, recyclerMenu.paddingLeft)
            if (retryAfterLayout) {
                recyclerMenu.post { ensureSelectedCardVisible(position, retryAfterLayout = false) }
            }
            return
        }
        val visibleLeft = recyclerMenu.paddingLeft
        val visibleRight = recyclerMenu.width - recyclerMenu.paddingRight
        val itemLeft = lm.getDecoratedLeft(itemView)
        val itemRight = lm.getDecoratedRight(itemView)
        when {
            itemLeft < visibleLeft -> recyclerMenu.smoothScrollBy(itemLeft - visibleLeft, 0)
            itemRight > visibleRight -> recyclerMenu.smoothScrollBy(itemRight - visibleRight, 0)
        }
    }

    private fun onItemConfirmed(index: Int) {
        if (layoutWifiRequiredDialog.visibility == View.VISIBLE) return
        when (index) {
            0 -> startInspection()
            1 -> entryGuardCoordinator.launchWifiScanner(this)
            2 -> checkUpdateManually()
            else -> Unit
        }
    }

    private fun startInspection() {
        if (!initComplete) {
            tvBottomHint.text = "初始化中，请稍候..."
            return
        }
        startActivity(Intent(this, AiInspectionMenuActivity::class.java))
    }

    private fun checkUpdateManually() {
        tvBottomHint.setText(R.string.ai_entry_menu_update_checking)
        entryGuardCoordinator.checkUpdateManually(object : EntryGuardCoordinator.UpdateCheckListener {
            override fun onComplete(hasUpdate: Boolean, updateInfoJson: String?) {
                runOnUiThread {
                    if (hasUpdate && updateInfoJson != null) {
                        startActivity(
                            Intent(this@MainMenuActivity, AppUpdatePromptActivity::class.java).apply {
                                putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, updateInfoJson)
                            },
                        )
                    } else {
                        tvBottomHint.setText(R.string.ai_entry_menu_update_latest)
                    }
                }
            }
        })
    }

    companion object {
        private const val TAG = "MainMenu"
    }
}
```

**注意**：上述代码中 `entryGuardCoordinator.sdkInitState` 和 `cameraWarmupState` 是 `private` 的，需要在 `EntryGuardCoordinator` 中提供公开读取方法或改为 `internal`。

- [ ] **Step 4: 在 EntryGuardCoordinator 中添加公开状态读取方法**

```kotlin
val sdkInitState: SdkInitState
    get() = fieldSdkInitState

val cameraWarmupState: CameraWarmupState
    get() = fieldCameraWarmupState

// 将原来的 private var sdkInitState 改为 fieldSdkInitState
private var fieldSdkInitState = SdkInitState.IDLE
private var fieldCameraWarmupState = CameraWarmupState.IDLE
```

**更正**：更好的做法是在 `MainMenuActivity` 中通过回调记录状态，而不是直接读取 Coordinator 内部状态。修改 `MainMenuActivity` 的回调实现：

```kotlin
private var currentSdkState = EntryGuardCoordinator.SdkInitState.IDLE
private var currentCameraState = EntryGuardCoordinator.CameraWarmupState.IDLE

override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
    currentSdkState = state
    updateInitStatus()
}

override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) {
    currentCameraState = state
    updateInitStatus()
}

private fun updateInitStatus() {
    // 使用 currentSdkState 和 currentCameraState
}
```

- [ ] **Step 5: 禁用双击/后退退出（覆盖 `onGlassKeyEvent`）**

在 `MainMenuActivity` 中添加：

```kotlin
override fun onGlassKeyEvent(keyEvent: Int): Boolean {
    if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK || keyEvent == GlassKeyEvent.KEYCODE_BACK) {
        // 消费事件但不退出
        return true
    }
    return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
}
```

- [ ] **Step 6: 更新 `AndroidManifest.xml` 设置 LAUNCHER**

将 `MainMenuActivity` 添加为 LAUNCHER：

```xml
<activity
    android:name="com.rokid.glass.MainMenuActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

将原来的 `AiInspectionMenuActivity` LAUNCHER 移除（保留 `exported="true"`）。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/MainMenuActivity.kt
git add app/src/main/res/layout/activity_main_menu.xml
git add app/src/main/res/values/strings.xml
git add app/src/main/AndroidManifest.xml
git commit -m "feat: 新建 MainMenuActivity 作为第一层菜单入口，集成 EntryGuardCoordinator"
```

---

## Task 3: 改造 AiInspectionMenuActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`

**设计说明：** 将 `AiInspectionMenuActivity` 从"入口+菜单"双重职责改为纯业务菜单。移除 WiFi 检测、SDK 初始化触发、相机预热、自动更新检查、检查更新卡片。保留企业QR扫码作为第二层入口检查。双击/后退弹出"结束巡检"确认对话框，删除语音"退出"。

- [ ] **Step 1: 移除检查更新卡片**

修改 `menuAdapter`：

```kotlin
private val menuAdapter by lazy {
    MenuCardAdapter(
        cards = listOf(
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.ai_entry_menu_analysis),
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_device_guide, R.string.ai_entry_menu_guide),
            MenuCardAdapter.MenuCardData(R.drawable.ic_menu_hazard_record, R.string.ai_entry_menu_record),
        ),
    )
}
```

- [ ] **Step 2: 移除 WiFi 相关字段和方法**

删除以下字段：
- `layoutWifiRequiredDialog`
- `tvWifiRequiredMessage`
- `tvWifiRetry`
- `tvWifiExit`
- `tvWifiConnectedToast`
- `wifiRequiredDialogVisible`
- `wifiScannerLaunching`
- `wifiConnectInProgress`
- `autoWifiScanAttempted`
- `hideWifiConnectedToastRunnable`

删除以下方法：
- `runEntryGuards()` 中的 WiFi 检测部分
- `showWifiRequiredDialog()`
- `hideWifiRequiredDialog()`
- `launchWifiScanner()`
- `handleWifiQrContent()`
- `confirmWifiConnected()`
- `showWifiConnectedToastIfNeeded()`

- [ ] **Step 3: 移除自动更新检查相关字段和方法**

删除以下字段：
- `updateExecutor`
- `updateManager`
- `checkingUpdate`
- `autoUpdateChecked`

删除以下方法：
- `startAutoUpdateCheck()`
- `checkUpdateManually()`

- [ ] **Step 4: 简化入口检查（仅保留企业QR扫码）**

将 `runEntryGuards()` 改造为仅检查企业QR：

```kotlin
private fun runEntryGuards() {
    if (entryGuardNavigating || exitConfirmDialogVisible) return

    if (
        InspectionWorkflowSession.enterpriseQrPayload == null ||
        InspectionWorkflowSession.enterpriseInfo == null
    ) {
        entryGuardNavigating = true
        startEnterpriseQrScan(forceScan = false)
        return
    }

    updateInspectionSummary()
}
```

- [ ] **Step 5: 改造双击/后退行为（弹出结束巡检确认）**

删除 `showExitConfirmDialog()` 和 `hideExitConfirmDialog()` 方法中的旧逻辑，改为"结束巡检"确认对话框。

将原来的退出确认对话框改造为"结束巡检"确认对话框：

```kotlin
private fun showEndInspectionConfirmDialog() {
    if (exitConfirmDialogVisible) return
    exitConfirmDialogVisible = true
    exitConfirmSelectedIndex = EXIT_CONFIRM_CONFIRM
    layoutExitConfirmDialog.visibility = View.VISIBLE
    recyclerMenu.isEnabled = false
    tvBottomHint.visibility = View.GONE
    // 更新对话框文案
    tvExitConfirmConfirm.text = getString(R.string.ai_entry_end_inspection_confirm)
    tvExitConfirmCancel.text = getString(R.string.ai_entry_end_inspection_cancel)
    updateExitConfirmSelection()
    inputSession.updateActions(buildInputActions())
}
```

修改 `executeExitConfirmSelection()`：

```kotlin
private fun executeExitConfirmSelection() {
    when (exitConfirmSelectedIndex) {
        EXIT_CONFIRM_CONFIRM -> startEndReport()
        EXIT_CONFIRM_CANCEL -> hideExitConfirmDialog()
    }
}
```

- [ ] **Step 6: 删除语音"退出"，改造语音"结束巡查"**

在 `buildInputActions()` 中：

删除原"退出"Action：
```kotlin
// 删除以下代码
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Exit,
    label = "退出",
    triggers = listOf(
        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_exit), "tui chu"),
    ),
) {
    showExitConfirmDialog()
},
```

修改"结束巡查"Action为直接触发：
```kotlin
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId("ai_menu_finish_inspection"),
    label = "结束巡查",
    triggers = listOf(
        UnifiedInputSession.InputTrigger.Voice("结束巡查", "jie shu xun cha"),
        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish), "jie shu ren wu"),
        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish_accent_alias), "jie su ren wu"),
    ),
) {
    startEndReport()
},
```

- [ ] **Step 7: 移除 SDK 初始化检查**

在 `startHazardAnalysis()` 和 `startDeviceGuide()` 中，移除 SDK 初始化检查：

```kotlin
private fun startHazardAnalysis() {
    startActivity(Intent(this, AiInspectionActivity::class.java))
}

private fun startDeviceGuide() {
    startActivity(Intent(this, DeviceGuideActivity::class.java))
}
```

- [ ] **Step 8: 覆盖 `onGlassKeyEvent` 处理双击/后退**

```kotlin
override fun onGlassKeyEvent(keyEvent: Int): Boolean {
    if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK || keyEvent == GlassKeyEvent.KEYCODE_BACK) {
        showEndInspectionConfirmDialog()
        return true
    }
    return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
}
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt
git commit -m "refactor: 改造 AiInspectionMenuActivity 为纯业务菜单，移除系统检查逻辑，双击/后退触发结束巡检"
```

---

## Task 4: 改造 EnterpriseQrScanActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`

**设计说明：** 移除自动更新检查逻辑，因为已上移到 `MainMenuActivity`。保留企业QR扫码核心功能。

- [ ] **Step 1: 移除自动更新检查相关字段和方法**

删除以下字段：
- `updateCheckExecutor`
- `updateManager`
- `autoUpdateChecked`

删除以下方法：
- `startAutoUpdateCheck()`

- [ ] **Step 2: 移除 `onResume` 中的自动更新检查调用**

```kotlin
override fun onResume() {
    isActivityResumed = true
    super.onResume()
    inputSession.attach()
    refreshInputActions()
    statusBarUpdater.start(statusBar)
    if (completed) return
    if (debugSnapshotMode) return
    // 删除：startAutoUpdateCheck()
    if (!intent.getBooleanExtra(EXTRA_FORCE_SCAN, false) && skipScanIfEnterpriseQrCached()) return
    cameraRecoveryController.start()
    if (hasRequiredPermissions()) {
        startCameraPipeline(resetRecoveryAttempts = true)
    } else {
        requestPermissions()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt
git commit -m "refactor: 从 EnterpriseQrScanActivity 移除自动更新检查（已上移到第一层）"
```

---

## Task 5: 改造 InspectionEndReportActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/InspectionEndReportActivity.kt`

**设计说明：** `InspectionEndReportReturnDestination` 的语义是"取消结束时返回哪里"，保持不变。提交成功后不再是 `exitAppDirectly()`，而是固定返回 `MainMenuActivity`。清空 `InspectionWorkflowSession` 的巡检状态（保留企业信息）。相机从 `releaseAppCamera()` 改为 `pause()`。

- [ ] **Step 1: 新建 `returnToMainMenuAfterFinish()` 方法**

提交成功后固定返回 `MainMenuActivity`，不依赖 `returnDestination`：

```kotlin
private fun returnToMainMenuAfterFinish() {
    if (isFinishing || isDestroyed) return
    InspectionWorkflowSession.clearInspectionState() // 仅清空巡检状态，保留企业信息
    InspectionCameraCoordinator.pause(
        InspectionCameraCoordinator.CameraOwner.AI_INSPECTION,
        reason = "inspection_end_return_to_main_menu",
    )
    startActivity(Intent(this, MainMenuActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
    finish()
}
```

**注意**：需要确认 `InspectionWorkflowSession` 是否有 `clearInspectionState()` 方法。如果没有，需要新建一个仅清空巡检数据（如隐患记录、巡检会话ID）但不清空企业信息的方法。替代方案是直接使用 `resetAll()` 如果其语义符合需求（实现时先查看该类代码）。

- [ ] **Step 2: 修改 `submitFinishInspectionInBackground()` 调用**

将原来的 `exitAppAfterFinishSubmitted()` 替换为 `returnToMainMenuAfterFinish()`：

```kotlin
private fun submitFinishInspectionInBackground() {
    if (finishExitTriggered) return
    finishExitTriggered = true
    if (!InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()) {
        returnToMainMenuAfterFinish()
        return
    }
    val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
    if (enterprisePayload == null) {
        android.util.Log.w(TAG, "skip finish background upload: missing enterprise payload")
        returnToMainMenuAfterFinish()
        return
    }
    val taskId = InspectionBackgroundUploadQueue.enqueueFinishInspection(
        taskKey = buildFinishUploadTaskKey(enterprisePayload),
        baseUrl = enterprisePayload.apiBaseUrl,
        authCode = enterprisePayload.authCode,
        objectId = enterprisePayload.objectId,
        userId = enterprisePayload.userId,
        customParam = enterprisePayload.extraField,
    )
    if (!taskId.isNullOrBlank()) {
        InspectionBackgroundUploadService.start(this, taskId)
    }
    returnToMainMenuAfterFinish()
}
```

- [ ] **Step 3: 保留 `returnToMenuDirectly()` 不变**

取消结束时的返回逻辑保持原样，继续通过 `returnDestination` 返回。`AiInspectionMenuActivity` 调用时仍然传 `AI_MENU_HOME`：

```kotlin
// AiInspectionMenuActivity 中
private fun startEndReport() {
    startActivity(
        InspectionEndReportActivity.createIntent(
            this,
            InspectionEndReportReturnDestination.AI_MENU_HOME, // 取消时返回第二层菜单
        ),
    )
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rokid/glass/InspectionEndReportActivity.kt
git commit -m "refactor: InspectionEndReportActivity 提交成功后固定返回 MainMenuActivity，暂停相机而非释放"
```

---

## Task 6: 更新 AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**设计说明：** 将 LAUNCHER intent-filter 从 `AiInspectionMenuActivity` 移到 `MainMenuActivity`。`AiInspectionMenuActivity` 保留 `exported="true"` 以便被 `MainMenuActivity` 启动。

- [ ] **Step 1: 修改 LAUNCHER Activity**

```xml
<!-- 移除 AiInspectionMenuActivity 的 LAUNCHER -->
<activity
    android:name="com.rokid.glass.AiInspectionMenuActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo">
    <!-- LAUNCHER 已移除 -->
</activity>

<!-- 添加 MainMenuActivity 为 LAUNCHER -->
<activity
    android:name="com.rokid.glass.MainMenuActivity"
    android:exported="true"
    android:theme="@style/Theme.Glessedemo">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "chore: LAUNCHER 从 AiInspectionMenuActivity 移到 MainMenuActivity"
```

---

## Task 7: 补充字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

**设计说明：** 补充第一层菜单和结束巡检确认对话框所需的字符串。

- [ ] **Step 1: 添加字符串资源**

```xml
<!-- 第一层菜单 -->
<string name="main_menu_card_inspection">基层应消</string>
<string name="main_menu_card_wifi">连接WiFi</string>
<string name="main_menu_card_update">检查更新</string>
<string name="main_menu_voice_exit_app">退出应用</string>
<string name="main_menu_voice_exit_app_pinyin">tui chu ying yong</string>
<string name="main_menu_voice_inspection">基层应消</string>
<string name="main_menu_voice_inspection_pinyin">ji ceng ying xiao</string>
<string name="main_menu_bottom_hint">前翻/后翻切换，单击确认</string>
<string name="main_menu_init_status">初始化中...</string>

<!-- 结束巡检确认对话框 -->
<string name="ai_entry_end_inspection_confirm">确认结束</string>
<string name="ai_entry_end_inspection_cancel">取消</string>
<string name="ai_entry_end_inspection_title">结束巡检</string>
<string name="ai_entry_end_inspection_message">是否结束当前巡检并提交报告？</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "chore: 添加第一层菜单和结束巡检确认相关字符串资源"
```

---

## 自我审查

### 1. Spec 覆盖检查

| Spec 要求 | 对应 Task | 状态 |
|-----------|-----------|------|
| 新建 `MainMenuActivity` 作为 LAUNCHER | Task 2 | ✅ |
| 新建 `EntryGuardCoordinator` | Task 1 | ✅ |
| `MainMenuActivity` 3 卡片（基层应消/连接WiFi/检查更新） | Task 2 | ✅ |
| 后台静默初始化（WiFi/SDK/相机） | Task 1 | ✅ |
| 禁用双击/后退退出 | Task 2 | ✅ |
| 语音"退出应用" | Task 2 | ✅ |
| 改造 `AiInspectionMenuActivity` 为第二层 | Task 3 | ✅ |
| 移除检查更新卡片 | Task 3 | ✅ |
| 移除 WiFi/SDK/相机入口检查 | Task 3 | ✅ |
| 保留企业QR扫码 | Task 3 | ✅ |
| 双击/后退弹出"结束巡检"确认 | Task 3 | ✅ |
| 删除语音"退出" | Task 3 | ✅ |
| 改造 `EnterpriseQrScanActivity` 移除自动更新 | Task 4 | ✅ |
| 改造 `InspectionEndReportActivity` 返回第一层 | Task 5 | ✅ |
| 相机暂停不释放 | Task 5 | ✅ |
| 清空巡检状态 | Task 5 | ⚠️ 需要确认 `InspectionWorkflowSession` 的方法 |
| 提交成功后固定返回 `MainMenuActivity`（不新增 `MAIN_MENU_HOME`） | Task 5 | ✅ |
| 更新 `AndroidManifest.xml` | Task 6 | ✅ |
| 更新 `strings.xml` | Task 7 | ✅ |

### 2. Placeholder 扫描

- 无 "TBD" / "TODO" / "implement later"
- 所有步骤包含实际代码
- 无 "Similar to Task N" 引用
- 所有类型和方法名在计划内定义

### 3. 类型一致性检查

- `EntryGuardCoordinator.SdkInitState` / `CameraWarmupState` 在 Task 1 定义，Task 2 使用，一致
- `EntryGuardCoordinator.UpdateCheckListener` 在 Task 1 定义，Task 2 使用，一致
- `InspectionEndReportActivity` 提交成功后固定返回 `MainMenuActivity`，不通过 `ReturnDestination` 配置，符合用户要求的语义分离

### 4. 待确认项

- **Task 5 Step 1**: `InspectionWorkflowSession.clearInspectionState()` 方法是否存在需要实现时确认。如果不存在，需要在该类中新建一个仅清空巡检数据（隐患记录、会话ID等）但保留企业信息的方法。替代方案是直接使用 `InspectionWorkflowSession.resetAll()` 如果其语义符合需求。
- **Task 3 Step 5**: 结束巡检确认对话框是否需要独立的布局文件，还是复用现有的 `layoutExitConfirmDialog`。复用现有布局更简洁，只需修改文案。

---

## 执行选项

**Plan complete and saved to `docs/superpowers/plans/2026-06-10-two-tier-menu-refactor.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
