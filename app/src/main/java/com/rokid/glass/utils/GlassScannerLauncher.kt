package com.rokid.glass.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner

/**
 * GlassScanner 统一启动入口封装。
 * 提供相机资源冲突检测和自动恢复重试逻辑：
 * 首次直接启动；仅在明确相机占用/打开错误时释放 App 相机并重试一次；
 * 第二次仍失败则回调 onCameraUnavailable()。
 */
object GlassScannerLauncher {

    private const val TAG = "GlassScannerLauncher"
    private const val RETRY_DELAY_MS = 300L

    /** 统一的启动回调，相比 GlassScanCallback 增加了 onCameraUnavailable */
    interface LauncherCallback {
        fun onSuccess(content: String?, barcode: Barcode)
        fun onFailure(error: String)
        fun onCancelled()
        fun onCameraUnavailable()
    }

    /** 相机错误关键词，用于判断是否属于相机占用/冲突类错误 */
    private val cameraErrorPatterns = listOf(
        "CameraAccessException",
        "CameraDevice",
        "connectHelper",
        "Higher-priority client using camera",
        "currently unavailable",
        "ServiceSpecificException",
        "CameraRuntimeException",
    )

    /**
     * 启动扫码。
     * @param activity 宿主 Activity
     * @param config 扫码配置
     * @param callback 回调
     */
    fun launch(
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        callback: LauncherCallback,
    ) {
        tryLaunch(activity, config, callback, isRetry = false)
    }

    private fun tryLaunch(
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        callback: LauncherCallback,
        isRetry: Boolean,
    ) {
        try {
            GlassScanner.launch(
                activity,
                config,
                object : GlassScanCallback {
                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        callback.onSuccess(content, barcode)
                    }
                    override fun onScanFailure(error: String) {
                        handleResult(error, callback, activity, config, isRetry)
                    }
                    override fun onScanCancelled() {
                        callback.onCancelled()
                    }
                },
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            AppFileLogger.e(TAG, "launch exception: $errorMsg", e)
            handleResult(errorMsg, callback, activity, config, isRetry)
        }
    }

    private fun handleResult(
        errorMsg: String,
        callback: LauncherCallback,
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        isRetry: Boolean,
    ) {
        if (!isCameraError(errorMsg)) {
            callback.onFailure(errorMsg)
            return
        }
        if (!isRetry) {
            AppFileLogger.w(TAG, "camera error on first attempt: $errorMsg, releasing app camera and retrying")
            InspectionCameraCoordinator.releaseAppCamera(reason = "glass_scanner_conflict_recovery")
            Handler(Looper.getMainLooper()).postDelayed({
                tryLaunch(activity, config, callback, isRetry = true)
            }, RETRY_DELAY_MS)
            return
        }
        AppFileLogger.e(TAG, "camera error on retry: $errorMsg, camera unavailable")
        callback.onCameraUnavailable()
    }

    /** 判断错误信息是否属于相机占用/冲突类错误 */
    fun isCameraError(error: String): Boolean {
        return cameraErrorPatterns.any { pattern -> error.contains(pattern) }
    }
}
