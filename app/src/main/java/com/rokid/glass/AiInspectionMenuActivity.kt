package com.rokid.glass

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.adapter.MenuCardAdapter
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.DeviceGuideActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HazardRecordActivity
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.InspectionSession
import com.google.gson.Gson
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import java.io.IOException
import java.util.concurrent.Executors

class AiInspectionMenuActivity : BaseGlassActivity() {

    private lateinit var tvBottomHint: TextView
    private lateinit var statusBar: GlassStatusBar
    private lateinit var recyclerMenu: RecyclerView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var checkingUpdate = false
    private var autoUpdateChecked = false
    private var selectedIndex = 0

    private lateinit var layoutWifiRequiredDialog: LinearLayout
    private lateinit var tvWifiRequiredConfirm: TextView
    private var entryGuardNavigating = false
    private var wifiRequiredDialogVisible = false

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
        statusBar = findViewById(R.id.statusBar)
        updateBatteryLevel()

        recyclerMenu = findViewById(R.id.recyclerMenu)
        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerMenu.adapter = menuAdapter
        recyclerMenu.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // 初始选中第一张卡片
        menuAdapter.selectedIndex = 0

        layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
        tvWifiRequiredConfirm = findViewById(R.id.tvWifiRequiredConfirm)
        tvWifiRequiredConfirm.setOnClickListener { exitAppFromWifiDialog() }
    }

    override fun onResume() {
        super.onResume()
        entryGuardNavigating = false
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        updateBatteryLevel()
        runEntryGuards()
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        updateExecutor.shutdownNow()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun runEntryGuards() {
        if (entryGuardNavigating || wifiRequiredDialogVisible) return

        if (SystemStateUtils.getCurrentWifiSsid(this) == null) {
            showWifiRequiredDialog()
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
            startActivity(Intent(this, EnterpriseQrScanActivity::class.java))
            return
        }

        startAutoUpdateCheck()
    }

    private fun showWifiRequiredDialog() {
        wifiRequiredDialogVisible = true
        layoutWifiRequiredDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        inputSession.updateActions(buildInputActions())
    }

    private fun hideWifiRequiredDialog() {
        wifiRequiredDialogVisible = false
        layoutWifiRequiredDialog.visibility = View.GONE
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
    }

    private fun exitAppFromWifiDialog() {
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
                    label = getString(R.string.ai_entry_wifi_required_confirm),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_required_confirm), "que ding"),
                    ),
                ) {
                    exitAppFromWifiDialog()
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

    private fun onItemConfirmed(index: Int) {
        if (entryGuardNavigating || wifiRequiredDialogVisible) return
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

    /**
     * 获取当前电池电量并更新电池图标填充
     */
    private fun updateBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBar.setBatteryPercent(batteryPct)
            }
        }
    }

    companion object {
        private const val TAG = "AiInspectionMenu"
    }
}
