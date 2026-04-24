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
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.InspectionSession
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R

class AiInspectionMenuActivity : BaseGlassActivity() {

    private lateinit var itemHazardAnalysis: FrameLayout
    private lateinit var itemHazardRecord: FrameLayout
    private lateinit var itemDeviceGuide: FrameLayout
    private lateinit var tvBottomHint: TextView
    private lateinit var statusBar: GlassStatusBar

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private lateinit var items: List<FrameLayout>
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection_menu)

        itemHazardAnalysis = findViewById(R.id.itemHazardAnalysis)
        itemHazardRecord = findViewById(R.id.itemHazardRecord)
        itemDeviceGuide = findViewById(R.id.itemDeviceGuide)
        tvBottomHint = findViewById(R.id.tvBottomHint)
        statusBar = findViewById(R.id.statusBar)
        updateBatteryLevel()

        items = listOf(itemHazardAnalysis, itemDeviceGuide, itemHazardRecord)
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
                label = "隐患分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患分析", "yin huan fen xi")),
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
                label = "隐患录入",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患录入", "yin huan lu ru")),
            ) {
                onItemConfirmed(2)
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
            1, 2 -> tvBottomHint.text = getString(R.string.common_feature_in_development)
            else -> Unit
        }
    }

    private fun startHazardAnalysis() {
        val targetActivity = if (InspectionSession.isInitialized && InspectionSession.hiddenRiskNcnn != null) {
            AiInspectionActivity::class.java
        } else {
            InspectionLoadingActivity::class.java
        }
        startActivity(Intent(this, targetActivity))
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
