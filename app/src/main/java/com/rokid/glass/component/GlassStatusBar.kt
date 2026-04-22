package com.rokid.glass.component

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glesse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一的 Glass 底部状态栏组件。
 * 包含 WiFi 图标、当前时间、电量指示器。
 * 自动监听电量变化，提供更新时间的方法。
 */
class GlassStatusBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val timeView: TextView
    private val batteryFillView: ImageView
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
        LayoutInflater.from(context).inflate(R.layout.view_glass_status_bar, this, true)
        timeView = findViewById(R.id.tvStatusTime)
        batteryFillView = findViewById(R.id.ivStatusBatteryFill)
    }

    /** 更新时间显示 */
    fun updateTime() {
        timeView.text = timeFormat.format(Date())
    }

    /** 从 BatteryManager Intent 更新电量 */
    fun updateBattery(intent: Intent?) {
        intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                batteryFillView.setImageLevel(batteryPct * 100)
            }
        }
    }

    /** 直接设置电量百分比（0-100） */
    fun setBatteryPercent(percent: Int) {
        batteryFillView.setImageLevel(percent.coerceIn(0, 100) * 100)
    }
}
