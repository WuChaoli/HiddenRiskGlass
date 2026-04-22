package com.rokid.glass

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.glesse.R
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.input.UnifiedInputSession

/**
 * 正式开始菜单。
 */
class InspectionModeActivity : BaseGlassActivity() {

    private lateinit var itemAiPatrol: FrameLayout
    private lateinit var itemKnowledgeQa: FrameLayout
    private lateinit var itemRemoteAssist: FrameLayout
    private lateinit var tvBottomHint: TextView

    private val items by lazy {
        listOf(itemAiPatrol, itemKnowledgeQa, itemRemoteAssist)
    }
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_menu)

        itemAiPatrol = findViewById(R.id.itemAiPatrol)
        itemKnowledgeQa = findViewById(R.id.itemKnowledgeQa)
        itemRemoteAssist = findViewById(R.id.itemRemoteAssist)
        tvBottomHint = findViewById(R.id.tvBottomHint)
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
                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                updateSelection()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Next,
                label = "下一个",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                ),
            ) {
                selectedIndex = (selectedIndex + 1).coerceAtMost(items.lastIndex)
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
                id = UnifiedInputSession.InputActionId("start_menu_ai_patrol"),
                label = "AI巡检",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("AI巡检", "ei ai xun jian"),
                ),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("start_menu_qa"),
                label = "知识问答",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("知识问答", "zhi shi wen da"),
                ),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("start_menu_remote"),
                label = "远程协作",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("远程协作", "yuan cheng xie zuo"),
                ),
            ) {
                onItemConfirmed(2)
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
                if (index == selectedIndex) R.drawable.glass_menu_card_selected
                else R.drawable.glass_menu_card,
            )
        }
    }

    private fun onItemConfirmed(index: Int) {
        when (index) {
            0 -> startActivity(android.content.Intent(this, InspectionLoadingActivity::class.java))
            1, 2 -> tvBottomHint.text = getString(R.string.common_feature_in_development)
        }
    }

    companion object {
        private const val TAG = "InspectionModeActivity"
    }
}
