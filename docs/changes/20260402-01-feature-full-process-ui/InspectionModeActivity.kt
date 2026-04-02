package com.rokid.glass

import android.os.Bundle
import android.widget.LinearLayout
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glesse.R

/**
 * 巡检模式选择页面
 * 两个选项：AI识患、任务检查
 * 触控板前后滑动切换焦点，单击确认选择
 */
class InspectionModeActivity : BaseGlassActivity() {

    private lateinit var cardAi: LinearLayout
    private lateinit var cardTask: LinearLayout

    private var focusIndex = 0
    private val cardCount = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_mode)

        cardAi = findViewById(R.id.cardAi)
        cardTask = findViewById(R.id.cardTask)

        updateFocus()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                focusIndex = (focusIndex + 1) % cardCount
                updateFocus()
                return true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                focusIndex = (focusIndex - 1 + cardCount) % cardCount
                updateFocus()
                return true
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                onCardSelected(focusIndex)
                return true
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun updateFocus() {
        cardAi.setBackgroundResource(
            if (focusIndex == 0) R.drawable.bg_card_focused else R.drawable.bg_card_unfocused
        )
        cardTask.setBackgroundResource(
            if (focusIndex == 1) R.drawable.bg_card_focused else R.drawable.bg_card_unfocused
        )
    }

    private fun onCardSelected(index: Int) {
        when (index) {
            0 -> {
                // TODO: 跳转到 AI识患 页面
            }
            1 -> {
                // TODO: 跳转到 任务检查 页面
            }
        }
    }
}
