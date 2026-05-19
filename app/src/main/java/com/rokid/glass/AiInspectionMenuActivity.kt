package com.rokid.glass

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
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
import com.rokid.glesse.R
import java.io.IOException
import java.util.concurrent.Executors

class AiInspectionMenuActivity : BaseGlassActivity() {

    private lateinit var itemHazardAnalysis: FrameLayout
    private lateinit var itemHazardRecord: FrameLayout
    private lateinit var itemDeviceGuide: FrameLayout
    private lateinit var itemUpdateCheck: FrameLayout
    private lateinit var tvBottomHint: TextView
    private lateinit var statusBar: GlassStatusBar

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var checkingUpdate = false
    private lateinit var items: List<FrameLayout>
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection_menu)

        itemHazardAnalysis = findViewById(R.id.itemHazardAnalysis)
        itemHazardRecord = findViewById(R.id.itemHazardRecord)
        itemDeviceGuide = findViewById(R.id.itemDeviceGuide)
        itemUpdateCheck = findViewById(R.id.itemUpdateCheck)
        tvBottomHint = findViewById(R.id.tvBottomHint)
        statusBar = findViewById(R.id.statusBar)
        updateBatteryLevel()

        items = listOf(itemHazardAnalysis, itemDeviceGuide, itemHazardRecord, itemUpdateCheck)
        updateSelection()
    }

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
        updateExecutor.shutdownNow()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
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

    private fun updateSelection() {
        items.forEachIndexed { index, item ->
            item.setBackgroundResource(
                if (index == selectedIndex) R.drawable.glass_menu_card_selected
                else R.drawable.glass_menu_card,
            )
        }
        tvBottomHint.text = getString(R.string.ai_entry_menu_hint)
    }

    private fun onItemConfirmed(index: Int) {
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
