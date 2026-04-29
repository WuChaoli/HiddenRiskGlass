package com.rokid.glass

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.utils.ToastUtil
import com.rokid.glass.workflow.InspectionWorkflowSession


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

    // 追踪当前存活的 Activity 数量，用于判断应用是否完全退出
    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        // 初始化全局 Context
        mContext = this
        gMainHandler = Handler(Looper.getMainLooper())
        ToastUtil.init(this)
        registerActivityLifecycleCallbacks(AppLifecycleCallbacks())
    }

    /**
     * Activity 生命周期回调，用于检测应用是否完全退出。
     * 当最后一个 Activity 被销毁时，清除企业扫码信息，确保下次打开需要重新扫码。
     */
    private inner class AppLifecycleCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activityCount++
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            activityCount--
            if (activityCount <= 0) {
                // 所有 Activity 均已销毁，应用已退出，清除企业信息和本轮巡检累计结果。
                InspectionWorkflowSession.clearInspectionAccumulatedResults()
                InspectionWorkflowSession.clearEnterpriseData()
                Log.i("MyApplication", "app exited, enterprise and inspection session cleared")
            }
        }
    }


}
