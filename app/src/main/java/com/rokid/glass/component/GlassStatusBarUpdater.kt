package com.rokid.glass.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * 统一管理状态栏时间和电量刷新。
 * Activity 只负责在生命周期中调用 start/stop，避免各页面重复维护定时器和电量广播。
 */
class GlassStatusBarUpdater(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusBars: List<GlassStatusBar> = emptyList()
    private var batteryReceiver: BroadcastReceiver? = null

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateTime()
            mainHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    fun start(vararg bars: GlassStatusBar) {
        statusBars = bars.toList()
        refreshNow()
        mainHandler.removeCallbacks(timeUpdateRunnable)
        mainHandler.post(timeUpdateRunnable)
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateBattery(intent)
                }
            }
            appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(timeUpdateRunnable)
        batteryReceiver?.let {
            appContext.unregisterReceiver(it)
            batteryReceiver = null
        }
        statusBars = emptyList()
    }

    fun refreshNow(vararg bars: GlassStatusBar) {
        if (bars.isNotEmpty()) {
            statusBars = bars.toList()
        }
        updateTime()
        updateBattery(appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    private fun updateTime() {
        statusBars.forEach { it.updateTime() }
    }

    private fun updateBattery(intent: Intent?) {
        statusBars.forEach { it.updateBattery(intent) }
    }

    companion object {
        private const val STATUS_UPDATE_INTERVAL_MS = 1000L
    }
}
