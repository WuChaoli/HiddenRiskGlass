package com.rokid.glass.hiddenrisk

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.glesse.R

/**
 * 巡检模式选择页面。
 * 提供 AI识患 和 任务检查 两个选项，默认选中 AI识患。
 * 前后滑动切换选项，单击确认进入。
 */
class InspectionModeActivity : BaseGlassActivity() {

    private lateinit var itemAiInspection: FrameLayout
    private lateinit var itemTaskInspection: FrameLayout
    private lateinit var itemLightshot: FrameLayout
    private lateinit var tvBottomHint: TextView

    private var selectedIndex = 0
    private val itemCount = 3   // AI识患 / 任务检查 / 闪拍

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_mode)

        itemAiInspection = findViewById(R.id.itemAiInspection)
        itemTaskInspection = findViewById(R.id.itemTaskInspection)
        itemLightshot = findViewById(R.id.itemLightshot)
        tvBottomHint = findViewById(R.id.tvBottomHint)

        updateSelection()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                selectedIndex = (selectedIndex + 1).coerceAtMost(itemCount - 1)
                updateSelection()
                true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                updateSelection()
                true
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                onItemConfirmed()
                true
            }
            GlassKeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    /** 根据 selectedIndex 更新三个选项的高亮背景 */
    private fun updateSelection() {
        itemAiInspection.setBackgroundResource(
            if (selectedIndex == 0) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemTaskInspection.setBackgroundResource(
            if (selectedIndex == 1) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemLightshot.setBackgroundResource(
            if (selectedIndex == 2) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
    }

    /** 确认当前选中项，跳转对应页面 */
    private fun onItemConfirmed() {
        when (selectedIndex) {
            0 -> {
                // AI识患：进入隐患检测流程
                startActivity(Intent(this, AiInspectionActivity::class.java))
            }
            1 -> {
                // 任务检查：暂未实现
                tvBottomHint.text = "任务检查模式开发中..."
            }
            2 -> {
                // 闪拍：批量采集模型测试样本
                startActivity(Intent(this, LightshotActivity::class.java))
            }
        }
    }
}
