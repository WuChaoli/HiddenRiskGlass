package com.rokid.glass.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Debug 配置管理类
 * 用于存储和读取调试相关的开关状态
 */
object DebugConfig {

    private const val PREFS_NAME = "debug_config"
    private const val KEY_DEBUG_MODE = "debug_mode_enabled"

    private var prefs: SharedPreferences? = null

    /**
     * 初始化，在 Application 或 MainActivity 中调用
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 设置 Debug 模式开关
     */
    fun setDebugMode(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DEBUG_MODE, enabled)?.apply()
    }

    /**
     * 获取 Debug 模式状态
     */
    fun isDebugMode(): Boolean {
        return prefs?.getBoolean(KEY_DEBUG_MODE, false) ?: false
    }

    /**
     * 切换 Debug 模式
     */
    fun toggleDebugMode(): Boolean {
        val newState = !isDebugMode()
        setDebugMode(newState)
        return newState
    }
}
