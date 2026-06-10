package com.rokid.glass

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.AppVisibilityKeepAliveService
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glass.input.WearStateManager
import com.rokid.glass.utils.AppFileLogger
import com.rokid.glass.utils.DeviceUtil
import com.rokid.glass.utils.ToastUtil
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.BuildConfig


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
        private const val BOOT_PACKAGE_PROPERTY = "persist.vendor.boot.pkg"

        var gMainHandler: Handler? = null

        // 获取全局 Context 的方法
        var sendVideoStatus = false
        @Volatile
        private var wifiConnectedToastShown = false

        fun getContext(): MyApplication {
            return mContext ?: throw IllegalStateException("Application not initialized")
        }

        fun consumeWifiConnectedToast(): Boolean {
            if (wifiConnectedToastShown) return false
            wifiConnectedToastShown = true
            return true
        }
    }

    // 追踪当前存活的 Activity 数量，用于判断应用是否完全退出
    private var activityCount = 0
    private var appVisibilityKeepAliveServiceStarted = false
    private var appVisibilityKeepAliveServiceStartScheduled = false
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                RokidSdkManager.scheduleScreenOnAppVisibilityRefresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化全局 Context
        mContext = this
        gMainHandler = Handler(Looper.getMainLooper())
        ToastUtil.init(this)
        AppFileLogger.init(this, enabled = BuildConfig.DEBUG)
        installCrashLogger()
        ensureBootAutoStart()
        InspectionConfigRepository.init(this)
        WearStateManager.init(this)
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerActivityLifecycleCallbacks(AppLifecycleCallbacks())
        RokidSdkManager.initialize(this)
    }

    override fun onTerminate() {
        runCatching { unregisterReceiver(screenOnReceiver) }
        RokidSdkManager.release()
        super.onTerminate()
    }

    private fun ensureBootAutoStart() {
        val currentPackage = DeviceUtil.getSystemProp(BOOT_PACKAGE_PROPERTY)
        if (currentPackage == packageName) {
            AppFileLogger.i("MyApplication", "boot auto-start already configured package=$packageName")
            return
        }

        DeviceUtil.setSystemProp(BOOT_PACKAGE_PROPERTY, packageName)
        val updatedPackage = DeviceUtil.getSystemProp(BOOT_PACKAGE_PROPERTY)
        if (updatedPackage == packageName) {
            AppFileLogger.i("MyApplication", "boot auto-start configured package=$packageName")
        } else {
            AppFileLogger.e(
                "MyApplication",
                "boot auto-start configuration failed expected=$packageName actual=$updatedPackage",
            )
        }
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppFileLogger.e("MyApplication", "uncaught exception thread=${thread.name}", throwable)
            AppFileLogger.writeCrash(throwable)
            AppFileLogger.flush()
            previousHandler?.uncaughtException(thread, throwable)
        }
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

        override fun onActivityResumed(activity: Activity) {
            if (appVisibilityKeepAliveServiceStarted || appVisibilityKeepAliveServiceStartScheduled) {
                return
            }
            appVisibilityKeepAliveServiceStartScheduled = true
            gMainHandler?.postDelayed({
                appVisibilityKeepAliveServiceStartScheduled = false
                runCatching {
                    startService(Intent(this@MyApplication, AppVisibilityKeepAliveService::class.java))
                }.onSuccess {
                    appVisibilityKeepAliveServiceStarted = true
                }.onFailure { throwable ->
                    AppFileLogger.e(
                        "MyApplication",
                        "start app visibility keep-alive service failed",
                        throwable,
                    )
                }
            }, 500L)
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            activityCount--
            if (activityCount <= 0) {
                InspectionCameraCoordinator.releaseAppCamera(reason = "app_last_activity_destroyed")
                // 所有 Activity 均已销毁，应用已退出，清除企业信息和本轮巡检累计结果。
                InspectionWorkflowSession.clearInspectionAccumulatedResults()
                InspectionWorkflowSession.clearEnterpriseData()
                wifiConnectedToastShown = false
                AppFileLogger.i("MyApplication", "app exited, enterprise and inspection session cleared")
            }
        }
    }

}
