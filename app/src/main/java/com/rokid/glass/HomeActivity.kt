package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.glesse.R
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.LightshotActivity
import com.rokid.glass.hiddenrisk.UnifiedInputDebugActivity
import com.rokid.glass.input.UnifiedInputSession

/**
 * 巡检模式首页。
 */
class InspectionModeActivity : BaseGlassActivity() {

    private lateinit var itemAiInspection: FrameLayout
    private lateinit var itemTaskInspection: FrameLayout
    private lateinit var itemLightshot: FrameLayout
    private lateinit var itemQrScan: FrameLayout
    private lateinit var itemUnifiedInputDebug: FrameLayout
    private lateinit var tvBottomHint: TextView

    private lateinit var items: List<FrameLayout>
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_mode)

        itemAiInspection = findViewById(R.id.itemAiInspection)
        itemTaskInspection = findViewById(R.id.itemTaskInspection)
        itemLightshot = findViewById(R.id.itemLightshot)
        itemQrScan = findViewById(R.id.itemQrScan)
        itemUnifiedInputDebug = findViewById(R.id.itemUnifiedInputDebug)
        tvBottomHint = findViewById(R.id.tvBottomHint)
        items = listOf(
            itemAiInspection,
            itemTaskInspection,
            itemLightshot,
            itemQrScan,
            itemUnifiedInputDebug,
        )
        updateSelection()
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
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
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                ),
            ) {
                selectedIndex = (selectedIndex - 1 + items.size) % items.size
                updateSelection()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Next,
                label = "下一个",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                ),
            ) {
                selectedIndex = (selectedIndex + 1) % items.size
                updateSelection()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                ),
            ) {
                onItemConfirmed(selectedIndex)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("inspection_mode_ai"),
                label = "AI识患",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("AI识患", "ai shi huan"),
                ),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("inspection_mode_task"),
                label = "任务检查",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("任务检查", "ren wu jian cha"),
                ),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("inspection_mode_lightshot"),
                label = "闪拍",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("闪拍", "shan pai"),
                ),
            ) {
                onItemConfirmed(2)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("inspection_mode_scan"),
                label = "扫一扫",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("扫一扫", "sao yi sao"),
                ),
            ) {
                onItemConfirmed(3)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("inspection_mode_unified_input_debug"),
                label = "统一输入调试",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("统一输入调试", "tong yi shu ru tiao shi"),
                ),
            ) {
                onItemConfirmed(4)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "退出",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                ),
            ) {
                finish()
            },
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun updateSelection() {
        items.forEachIndexed { index, view ->
            view.setBackgroundResource(
                if (index == selectedIndex) R.drawable.inspection_mode_item_bg_selected
                else R.drawable.inspection_mode_item_bg,
            )
        }
    }

    private fun onItemConfirmed(index: Int) {
        when (index) {
            0 -> startActivity(Intent(this, InspectionLoadingActivity::class.java))
            1 -> tvBottomHint.text = getString(R.string.common_feature_in_development)
            2 -> startActivity(Intent(this, LightshotActivity::class.java).apply {
                putExtra(LightshotActivity.EXTRA_MODE, LightshotActivity.MODE_LIGHTSHOT)
            })
            3 -> startActivity(Intent(this, WifiQrScanActivity::class.java))
            4 -> startActivity(Intent(this, UnifiedInputDebugActivity::class.java))
        }
    }

    companion object {
        private const val TAG = "InspectionModeActivity"
    }
}
