package com.rokid.glass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.adapter.MenuCardAdapter
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.DeviceGuideActivity
import com.rokid.glass.hiddenrisk.HazardRecordActivity
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.InspectionSession
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.google.gson.Gson
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.utils.WifiScanConfigFactory
import com.rokid.glass.wifi.WifiQrParseResult
import com.rokid.glass.wifi.WifiQrParser
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.IOException
import java.util.concurrent.Executors

class AiInspectionMenuActivity : BaseGlassActivity() {

    private lateinit var tvBottomHint: TextView
    private lateinit var tvInspectionSummary: TextView
    private lateinit var statusBar: GlassStatusBar
    private lateinit var recyclerMenu: RecyclerView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var checkingUpdate = false
    private var autoUpdateChecked = false
    private var selectedIndex = 0

    private lateinit var layoutWifiRequiredDialog: LinearLayout
    private lateinit var tvWifiRequiredMessage: TextView
    private lateinit var tvWifiRetry: TextView
    private lateinit var tvWifiExit: TextView
    private lateinit var tvWifiConnectedToast: TextView
    private lateinit var layoutExitConfirmDialog: LinearLayout
    private lateinit var tvExitConfirmConfirm: TextView
    private lateinit var tvExitConfirmCancel: TextView
    private lateinit var exitConfirmButtons: List<TextView>
    private var entryGuardNavigating = false
    private var wifiRequiredDialogVisible = false
    private var wifiScannerLaunching = false
    private var wifiConnectInProgress = false
    private var autoWifiScanAttempted = false
    private var exitConfirmDialogVisible = false
    private var exitConfirmSelectedIndex = EXIT_CONFIRM_CONFIRM
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideWifiConnectedToastRunnable = Runnable {
        tvWifiConnectedToast.visibility = View.GONE
    }

    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.ai_entry_menu_analysis),
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_device_guide, R.string.ai_entry_menu_guide),
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_hazard_record, R.string.ai_entry_menu_record),
                MenuCardAdapter.MenuCardData(0, R.string.ai_entry_menu_update, iconChar = "↻"),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection_menu)

        tvBottomHint = findViewById(R.id.tvBottomHint)
        tvInspectionSummary = findViewById(R.id.tvInspectionSummary)
        statusBar = findViewById(R.id.statusBar)

        recyclerMenu = findViewById(R.id.recyclerMenu)
        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerMenu.adapter = menuAdapter
        recyclerMenu.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // 初始选中第一张卡片
        menuAdapter.selectedIndex = 0

        layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
        tvWifiRequiredMessage = findViewById(R.id.tvWifiRequiredMessage)
        tvWifiRetry = findViewById(R.id.tvWifiRetry)
        tvWifiExit = findViewById(R.id.tvWifiExit)
        tvWifiConnectedToast = findViewById(R.id.tvWifiConnectedToast)
        tvWifiRetry.setOnClickListener { launchWifiScanner() }
        tvWifiExit.setOnClickListener { exitAppDirectly() }
        layoutExitConfirmDialog = findViewById(R.id.layoutExitConfirmDialog)
        tvExitConfirmConfirm = findViewById(R.id.tvExitConfirmConfirm)
        tvExitConfirmCancel = findViewById(R.id.tvExitConfirmCancel)
        exitConfirmButtons = listOf(tvExitConfirmConfirm, tvExitConfirmCancel)
        tvExitConfirmConfirm.setOnClickListener { exitAppDirectly() }
        tvExitConfirmCancel.setOnClickListener { hideExitConfirmDialog() }
        updateInspectionSummary()
    }

    override fun onResume() {
        super.onResume()
        entryGuardNavigating = false
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        statusBarUpdater.start(statusBar)
        updateInspectionSummary()
        runEntryGuards()
    }

    override fun onPause() {
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideWifiConnectedToastRunnable)
        OfflineTtsPlayer.release(WIFI_AUDIO_OWNER)
        statusBarUpdater.stop()
        updateExecutor.shutdownNow()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun runEntryGuards() {
        if (entryGuardNavigating || wifiRequiredDialogVisible || exitConfirmDialogVisible) return

        if (SystemStateUtils.getCurrentWifiSsid(this) == null) {
            showWifiRequiredDialog(R.string.ai_entry_wifi_required_message)
            if (!autoWifiScanAttempted && !wifiScannerLaunching && !wifiConnectInProgress) {
                autoWifiScanAttempted = true
                launchWifiScanner()
            }
            return
        }

        hideWifiRequiredDialog()

        if (!InspectionSession.isInitialized) {
            entryGuardNavigating = true
            startActivity(Intent(this, InspectionLoadingActivity::class.java).apply {
                putExtra(InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY, AiInspectionMenuActivity::class.java.name)
            })
            return
        }

        if (
            InspectionWorkflowSession.enterpriseQrPayload == null ||
            InspectionWorkflowSession.enterpriseInfo == null
        ) {
            entryGuardNavigating = true
            startEnterpriseQrScan(forceScan = false)
            return
        }

        updateInspectionSummary()
        startAutoUpdateCheck()
        showWifiConnectedToastIfNeeded()
    }

    private fun showWifiRequiredDialog(messageResId: Int) {
        wifiRequiredDialogVisible = true
        tvWifiRequiredMessage.setText(messageResId)
        hideExitConfirmDialog(refreshInput = false)
        layoutWifiRequiredDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        inputSession.updateActions(buildInputActions())
    }

    private fun launchWifiScanner() {
        if (wifiScannerLaunching || wifiConnectInProgress) return
        wifiScannerLaunching = true
        showWifiRequiredDialog(R.string.ai_entry_wifi_required_message)
        OfflineTtsPlayer.play(this, WIFI_AUDIO_OWNER, R.raw.need_scan_wifi)
        runCatching {
            GlassScanner.launch(
                this,
                WifiScanConfigFactory.create(this),
                object : GlassScanCallback {
                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        wifiScannerLaunching = false
                        if (content == null) {
                            showWifiRequiredDialog(R.string.ai_entry_wifi_invalid_qr)
                        } else {
                            handleWifiQrContent(content)
                        }
                    }

                    override fun onScanFailure(message: String) {
                        wifiScannerLaunching = false
                        showWifiRequiredDialog(R.string.ai_entry_wifi_invalid_qr)
                    }

                    override fun onScanCancelled() {
                        wifiScannerLaunching = false
                        showWifiRequiredDialog(R.string.ai_entry_wifi_required_message)
                    }
                },
            )
        }.onFailure { error ->
            wifiScannerLaunching = false
            Log.e(TAG, "launch wifi scanner failed", error)
            showWifiRequiredDialog(R.string.ai_entry_wifi_invalid_qr)
        }
    }

    private fun handleWifiQrContent(content: String) {
        when (val result = WifiQrParser.parse(content)) {
            is WifiQrParseResult.Error -> {
                Log.w(TAG, "wifi qr rejected reason=${result.reason}")
                showWifiRequiredDialog(R.string.ai_entry_wifi_invalid_qr)
            }
            is WifiQrParseResult.Success -> {
                wifiConnectInProgress = true
                showWifiRequiredDialog(R.string.ai_entry_wifi_connecting)
                RokidSdkManager.connectWifi(result.payload) { success, errorMessage ->
                    wifiConnectInProgress = false
                    if (!success) {
                        Log.w(TAG, "wifi connect failed message=$errorMessage")
                        val message = if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
                            R.string.ai_entry_wifi_connect_failed
                        } else {
                            R.string.ai_entry_wifi_sdk_unavailable
                        }
                        showWifiRequiredDialog(message)
                        return@connectWifi
                    }
                    confirmWifiConnected()
                }
            }
        }
    }

    private fun confirmWifiConnected(attempt: Int = 0) {
        if (SystemStateUtils.getCurrentWifiSsid(this) != null) {
            OfflineTtsPlayer.play(this, WIFI_AUDIO_OWNER, R.raw.wifi_success)
            hideWifiRequiredDialog()
            runEntryGuards()
            return
        }
        if (attempt >= WIFI_CONFIRM_MAX_ATTEMPTS) {
            showWifiRequiredDialog(R.string.ai_entry_wifi_connect_failed)
            return
        }
        uiHandler.postDelayed({ confirmWifiConnected(attempt + 1) }, WIFI_CONFIRM_INTERVAL_MS)
    }

    private fun showWifiConnectedToastIfNeeded() {
        if (!MyApplication.consumeWifiConnectedToast()) return
        tvWifiConnectedToast.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideWifiConnectedToastRunnable)
        uiHandler.postDelayed(hideWifiConnectedToastRunnable, WIFI_CONNECTED_TOAST_VISIBLE_MS)
    }

    private fun hideWifiRequiredDialog() {
        wifiRequiredDialogVisible = false
        layoutWifiRequiredDialog.visibility = View.GONE
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
    }

    private fun showExitConfirmDialog() {
        if (wifiRequiredDialogVisible) return
        exitConfirmDialogVisible = true
        exitConfirmSelectedIndex = EXIT_CONFIRM_CONFIRM
        layoutExitConfirmDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        updateExitConfirmSelection()
        inputSession.updateActions(buildInputActions())
    }

    private fun hideExitConfirmDialog(refreshInput: Boolean = true) {
        exitConfirmDialogVisible = false
        if (::layoutExitConfirmDialog.isInitialized) {
            layoutExitConfirmDialog.visibility = View.GONE
        }
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
        if (refreshInput) {
            inputSession.updateActions(buildInputActions())
        }
    }

    private fun exitAppDirectly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        if (wifiRequiredDialogVisible) {
            return listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.ai_entry_wifi_retry),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_retry), "chong xin sao ma"),
                    ),
                ) {
                    launchWifiScanner()
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
        if (exitConfirmDialogVisible) {
            return listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                ) {
                    moveExitConfirmSelection(-1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                ) {
                    moveExitConfirmSelection(+1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.ai_entry_exit_confirm_confirm),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_exit_confirm_confirm), "que ren"),
                    ),
                ) { event ->
                    if (event.trigger is UnifiedInputSession.InputTrigger.Voice) {
                        exitAppDirectly()
                    } else {
                        executeExitConfirmSelection()
                    }
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Cancel,
                    label = getString(R.string.ai_entry_exit_confirm_cancel),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_exit_confirm_cancel), "qu xiao"),
                    ),
                ) {
                    hideExitConfirmDialog()
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
                id = UnifiedInputSession.InputActionId("ai_menu_analysis"),
                label = "实时分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("实时分析", "shi shi fen xi")),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_guide"),
                label = "设备指引",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("设备指引", "she bei zhi yin")),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_record"),
                label = "隐患拍照",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患拍照", "yin huan pai zhao")),
            ) {
                onItemConfirmed(2)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_update"),
                label = "检查更新",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("检查更新", "jian cha geng xin")),
                enabled = { !checkingUpdate },
            ) {
                checkUpdateManually()
            },
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
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_scan"),
                label = "检查扫码",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("检查扫码", "jian cha sao ma")),
            ) {
                startEnterpriseQrScan(forceScan = true)
            },
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
        )
    }

    private fun updateInspectionSummary() {
        val companyName = InspectionWorkflowSession.enterpriseInfo
            ?.companyName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"
        tvInspectionSummary.text = getString(
            R.string.ai_entry_menu_inspection_summary,
            companyName,
            InspectionWorkflowSession.buildEndReportHazardCount(),
        )
    }

    /** 移动选中框：更新高亮，并确保目标卡片完整停留在可视区域内 */
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

    private fun moveExitConfirmSelection(delta: Int) {
        val target = (exitConfirmSelectedIndex + delta).coerceIn(0, exitConfirmButtons.lastIndex)
        if (target == exitConfirmSelectedIndex) return
        exitConfirmSelectedIndex = target
        updateExitConfirmSelection()
    }

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

    private fun executeExitConfirmSelection() {
        when (exitConfirmSelectedIndex) {
            EXIT_CONFIRM_CONFIRM -> exitAppDirectly()
            EXIT_CONFIRM_CANCEL -> hideExitConfirmDialog()
        }
    }

    private fun onItemConfirmed(index: Int) {
        if (entryGuardNavigating || wifiRequiredDialogVisible || exitConfirmDialogVisible) return
        when (index) {
            0 -> startHazardAnalysis()
            1 -> startDeviceGuide()
            2 -> startActivity(Intent(this, HazardRecordActivity::class.java))
            3 -> checkUpdateManually()
            else -> Unit
        }
    }

    private fun startHazardAnalysis() {
        val targetActivity = if (InspectionSession.isInitialized) {
            AiInspectionActivity::class.java
        } else {
            InspectionLoadingActivity::class.java
        }
        startActivity(Intent(this, targetActivity))
    }

    private fun startDeviceGuide() {
        val targetActivity = if (InspectionSession.isInitialized) {
            DeviceGuideActivity::class.java
        } else {
            InspectionLoadingActivity::class.java
        }
        startActivity(
            Intent(this, targetActivity).apply {
                if (targetActivity == InspectionLoadingActivity::class.java) {
                    putExtra(InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY, DeviceGuideActivity::class.java.name)
                }
            },
        )
    }

    private fun startEnterpriseQrScan(forceScan: Boolean) {
        startActivity(Intent(this, EnterpriseQrScanActivity::class.java).apply {
            putExtra(EnterpriseQrScanActivity.EXTRA_FORCE_SCAN, forceScan)
        })
    }

    private fun startEndReport() {
        startActivity(
            InspectionEndReportActivity.createIntent(
                this,
                InspectionEndReportReturnDestination.AI_MENU_HOME,
            ),
        )
    }

    private fun startAutoUpdateCheck() {
        if (autoUpdateChecked) return
        autoUpdateChecked = true
        updateExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = false)
                if (!result.hasUpdate || result.info == null) return@execute
                runOnUiThread {
                    if (!updateManager.markAutoPromptShownIfAllowed()) return@runOnUiThread
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

    private fun checkUpdateManually() {
        if (checkingUpdate) return
        checkingUpdate = true
        tvBottomHint.setText(R.string.ai_entry_menu_update_checking)
        inputSession.updateActions(buildInputActions())
        updateExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = true)
                runOnUiThread {
                    checkingUpdate = false
                    inputSession.updateActions(buildInputActions())
                    if (result.hasUpdate && result.info != null) {
                        startActivity(
                            Intent(this, AppUpdatePromptActivity::class.java).apply {
                                putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, Gson().toJson(result.info))
                            },
                        )
                    } else {
                        tvBottomHint.setText(R.string.ai_entry_menu_update_latest)
                    }
                }
            } catch (error: IOException) {
                Log.e(TAG, "检查更新失败", error)
                runOnUiThread {
                    checkingUpdate = false
                    tvBottomHint.setText(R.string.ai_entry_menu_update_failed)
                    inputSession.updateActions(buildInputActions())
                }
            }
        }
    }

    companion object {
        private const val TAG = "AiInspectionMenu"
        private const val EXIT_CONFIRM_CONFIRM = 0
        private const val EXIT_CONFIRM_CANCEL = 1
        private const val WIFI_AUDIO_OWNER = "AiInspectionMenuWifi"
        private const val WIFI_CONNECTED_TOAST_VISIBLE_MS = 2000L
        private const val WIFI_CONFIRM_INTERVAL_MS = 500L
        private const val WIFI_CONFIRM_MAX_ATTEMPTS = 10
    }
}
