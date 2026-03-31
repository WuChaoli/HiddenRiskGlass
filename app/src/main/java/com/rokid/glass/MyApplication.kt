package com.rokid.glass

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.rokid.glass.utils.ToastUtil


/**
 * Description:
 * Author:Lc
 * Date:2025/5/25
 */
class MyApplication : Application() {
    companion object {
        // 全局 Context 变量
        @Volatile
        private var mContext: MyApplication? = null

        //        val APP_ID = "GlassSample"
        const val ORDER_ACTION_BUTTON_CLICK = "com.rokid.glass3.action.button.CLICK"
        const val ORDER_ACTION_BUTTON_DOUBLE_CLICK = "com.rokid.glass3.action.button.DOUBLE_CLICK"

        const val ACTION_CLICK: String = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        const val ACTION_BUTTON_DOWN: String = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        const val ACTION_BUTTON_UP: String = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        const val ACTION_DOUBLE_CLICK: String = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
        const val ACTION_AI_START: String = "com.android.action.ACTION_AI_START"

        var gMainHandler: Handler? = null
        var curIsCameraActivity = false

        // 获取全局 Context 的方法
        var sendVideoStatus = false
        fun getContext(): MyApplication {
            return mContext ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化全局 Context
        mContext = this
        gMainHandler = Handler(Looper.getMainLooper())
        ToastUtil.init(this)
    }


}