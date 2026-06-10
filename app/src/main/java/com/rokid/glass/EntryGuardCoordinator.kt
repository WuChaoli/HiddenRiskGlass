package com.rokid.glass

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.utils.WifiScanConfigFactory
import com.rokid.glass.wifi.WifiQrParseResult
import com.rokid.glass.wifi.WifiQrParser
import com.rokid.glesse.R
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 入口守卫协调器。
 * 封装后台静默初始化流程：WiFi 检测与连接、SDK 初始化、相机预热、自动更新检查。
 * 各阶段独立失败不影响其他阶段，所有回调通过主线程 Handler 投递。
 */
class EntryGuardCoordinator(
    private val context: Context,
    private val callback: Callback,
) {

    interface Callback {
        /** WiFi 未连接，需要扫码 */
        fun onWifiRequired(messageResId: Int)
        /** WiFi 正在连接中 */
        fun onWifiConnecting()
        /** WiFi 已连接 */
        fun onWifiConnected()
        /** WiFi 连接失败 */
        fun onWifiConnectionFailed(messageResId: Int)
        /** SDK 状态变更 */
        fun onSdkStateChanged(state: SdkInitState)
        /** 相机预热状态变更 */
        fun onCameraStateChanged(state: CameraWarmupState)
        /** 有可用更新（JSON 字符串） */
        fun onAutoUpdateAvailable(updateInfoJson: String)
        /** 自动更新检查完成 */
        fun onAutoUpdateCheckComplete(hasUpdate: Boolean)
        /** 所有守卫就绪 */
        fun onAllGuardsReady()
    }

    enum class SdkInitState { IDLE, INITIALIZING, READY, FAILED }
    enum class CameraWarmupState { IDLE, WARMING_UP, READY, FAILED }

    interface UpdateCheckListener {
        fun onComplete(hasUpdate: Boolean, updateInfoJson: String?)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateExecutor = Executors.newSingleThreadExecutor()

    // 线程安全的状态标记
    private val released = AtomicBoolean(false)
    private val wifiCheckCompleted = AtomicBoolean(false)
    private val sdkCheckCompleted = AtomicBoolean(false)
    private val cameraCheckCompleted = AtomicBoolean(false)
    private val updateCheckCompleted = AtomicBoolean(false)

    // WiFi 相关状态
    private var wifiScannerLaunching = false
    private var wifiConnectInProgress = false

    // SDK 监听器
    private val sdkListener = object : RokidSdkManager.Listener {
        override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
            if (released.get()) return
            when (state) {
                RokidSdkManager.SdkState.READY -> {
                    postSdkState(SdkInitState.READY)
                    sdkCheckCompleted.set(true)
                    tryStartCameraWarmup()
                    tryNotifyAllGuardsReady()
                }
                RokidSdkManager.SdkState.FAILED -> {
                    postSdkState(SdkInitState.FAILED)
                    sdkCheckCompleted.set(true)
                    // SDK 失败不阻塞相机预热
                    tryStartCameraWarmup()
                    tryNotifyAllGuardsReady()
                }
                RokidSdkManager.SdkState.BINDING,
                RokidSdkManager.SdkState.REBINDING -> {
                    postSdkState(SdkInitState.INITIALIZING)
                }
                else -> Unit
            }
        }
    }

    /**
     * 启动所有后台检查。
     * 顺序：WiFi -> SDK -> 相机 -> 更新检查（各阶段独立）。
     */
    fun startBackgroundGuards() {
        if (released.get()) {
            Log.w(TAG, "startBackgroundGuards called after release")
            return
        }
        Log.i(TAG, "startBackgroundGuards")
        startWifiCheck()
    }

    /**
     * 启动 WiFi QR 扫码。
     * 由 Activity 在用户触发时调用。
     */
    fun launchWifiScanner(activity: Activity) {
        if (released.get() || wifiScannerLaunching || wifiConnectInProgress) return
        wifiScannerLaunching = true
        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
        runCatching {
            GlassScanner.launch(
                activity,
                WifiScanConfigFactory.create(activity),
                object : GlassScanCallback {
                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        wifiScannerLaunching = false
                        if (content == null) {
                            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
                        } else {
                            handleWifiQrContent(content)
                        }
                    }

                    override fun onScanFailure(error: String) {
                        wifiScannerLaunching = false
                        postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
                    }

                    override fun onScanCancelled() {
                        wifiScannerLaunching = false
                        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
                    }
                },
            )
        }.onFailure { error ->
            wifiScannerLaunching = false
            Log.e(TAG, "launch wifi scanner failed", error)
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
        }
    }

    /**
     * 手动检查更新。
     */
    fun checkUpdateManually(listener: UpdateCheckListener) {
        if (released.get()) return
        updateExecutor.execute {
            try {
                val result = AppUpdateManager(context.applicationContext).checkForUpdate(ignoreSkipped = true)
                val json = result.info?.let { Gson().toJson(it) }
                uiHandler.post { listener.onComplete(result.hasUpdate, json) }
            } catch (error: IOException) {
                Log.e(TAG, "manual update check failed", error)
                uiHandler.post { listener.onComplete(false, null) }
            }
        }
    }

    /**
     * 释放资源。
     */
    fun release() {
        if (released.getAndSet(true)) return
        Log.i(TAG, "release")
        uiHandler.removeCallbacksAndMessages(null)
        RokidSdkManager.removeListener(sdkListener)
        updateExecutor.shutdownNow()
    }

    // -------------------------------------------------------------------------
    // WiFi
    // -------------------------------------------------------------------------

    private fun startWifiCheck() {
        if (SystemStateUtils.getCurrentWifiSsid(context) != null) {
            Log.i(TAG, "wifi already connected")
            wifiCheckCompleted.set(true)
            postCallback { it.onWifiConnected() }
            // WiFi 就绪后继续 SDK 初始化
            startSdkInit()
            return
        }

        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
        // 不自动启动扫码，等待 Activity 调用 launchWifiScanner
    }

    private fun handleWifiQrContent(content: String) {
        when (val result = WifiQrParser.parse(content)) {
            is WifiQrParseResult.Error -> {
                Log.w(TAG, "wifi qr rejected reason=${result.reason}")
                postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
            }
            is WifiQrParseResult.Success -> {
                wifiConnectInProgress = true
                postCallback { it.onWifiConnecting() }
                RokidSdkManager.connectWifi(result.payload) { success, errorMessage ->
                    wifiConnectInProgress = false
                    if (!success) {
                        Log.w(TAG, "wifi connect failed message=$errorMessage")
                        val message = if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
                            R.string.ai_entry_wifi_connect_failed
                        } else {
                            R.string.ai_entry_wifi_sdk_unavailable
                        }
                        postCallback { it.onWifiConnectionFailed(message) }
                        return@connectWifi
                    }
                    confirmWifiConnected()
                }
            }
        }
    }

    private fun confirmWifiConnected(attempt: Int = 0) {
        if (released.get()) return
        if (SystemStateUtils.getCurrentWifiSsid(context) != null) {
            Log.i(TAG, "wifi connected confirmed")
            wifiCheckCompleted.set(true)
            postCallback { it.onWifiConnected() }
            startSdkInit()
            return
        }
        if (attempt >= WIFI_CONFIRM_MAX_ATTEMPTS) {
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_connect_failed) }
            return
        }
        uiHandler.postDelayed({ confirmWifiConnected(attempt + 1) }, WIFI_CONFIRM_INTERVAL_MS)
    }

    // -------------------------------------------------------------------------
    // SDK 初始化
    // -------------------------------------------------------------------------

    private fun startSdkInit() {
        if (released.get()) return
        Log.i(TAG, "startSdkInit")
        postSdkState(SdkInitState.INITIALIZING)
        RokidSdkManager.initialize(context.applicationContext as android.app.Application)
        RokidSdkManager.addListener(sdkListener)
        RokidSdkManager.ensureInitialized()

        // 如果 SDK 已经就绪，可能不会有状态回调，直接检查
        if (RokidSdkManager.state == RokidSdkManager.SdkState.READY && !sdkCheckCompleted.get()) {
            postSdkState(SdkInitState.READY)
            sdkCheckCompleted.set(true)
            tryStartCameraWarmup()
            tryNotifyAllGuardsReady()
        } else if (RokidSdkManager.state == RokidSdkManager.SdkState.FAILED && !sdkCheckCompleted.get()) {
            postSdkState(SdkInitState.FAILED)
            sdkCheckCompleted.set(true)
            tryStartCameraWarmup()
            tryNotifyAllGuardsReady()
        }
    }

    // -------------------------------------------------------------------------
    // 相机预热
    // -------------------------------------------------------------------------

    private fun tryStartCameraWarmup() {
        if (released.get() || cameraCheckCompleted.get()) return
        Log.i(TAG, "startCameraWarmup")
        postCameraState(CameraWarmupState.WARMING_UP)
        InspectionCameraCoordinator.acquire(
            owner = CameraOwner.LOADING,
            needPreview = false,
        ) { success ->
            if (released.get()) return@acquire
            if (success) {
                InspectionCameraCoordinator.pause(
                    CameraOwner.LOADING,
                    reason = "main_menu_camera_warmup_ready",
                )
                postCameraState(CameraWarmupState.READY)
            } else {
                Log.w(TAG, "camera warmup failed")
                postCameraState(CameraWarmupState.FAILED)
            }
            cameraCheckCompleted.set(true)
            // 相机完成后启动更新检查
            startAutoUpdateCheck()
            tryNotifyAllGuardsReady()
        }
    }

    // -------------------------------------------------------------------------
    // 自动更新检查
    // -------------------------------------------------------------------------

    private fun startAutoUpdateCheck() {
        if (released.get() || updateCheckCompleted.get()) return
        Log.i(TAG, "startAutoUpdateCheck")
        updateExecutor.execute {
            try {
                val result = AppUpdateManager(context.applicationContext).checkForUpdate(ignoreSkipped = false)
                val json = result.info?.let { Gson().toJson(it) }
                uiHandler.post {
                    if (released.get()) return@post
                    if (result.hasUpdate && json != null) {
                        callback.onAutoUpdateAvailable(json)
                    }
                    callback.onAutoUpdateCheckComplete(result.hasUpdate)
                    updateCheckCompleted.set(true)
                    tryNotifyAllGuardsReady()
                }
            } catch (error: IOException) {
                Log.i(TAG, "auto update check skipped: ${error.message}")
                uiHandler.post {
                    if (released.get()) return@post
                    callback.onAutoUpdateCheckComplete(false)
                    updateCheckCompleted.set(true)
                    tryNotifyAllGuardsReady()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 就绪通知
    // -------------------------------------------------------------------------

    private fun tryNotifyAllGuardsReady() {
        if (released.get()) return
        // WiFi 必须完成，其他阶段独立失败不阻塞
        if (!wifiCheckCompleted.get()) return
        if (!sdkCheckCompleted.get()) return
        if (!cameraCheckCompleted.get()) return
        if (!updateCheckCompleted.get()) return
        Log.i(TAG, "all guards ready")
        postCallback { it.onAllGuardsReady() }
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private fun postSdkState(state: SdkInitState) {
        uiHandler.post {
            if (released.get()) return@post
            callback.onSdkStateChanged(state)
        }
    }

    private fun postCameraState(state: CameraWarmupState) {
        uiHandler.post {
            if (released.get()) return@post
            callback.onCameraStateChanged(state)
        }
    }

    private inline fun postCallback(crossinline action: (Callback) -> Unit) {
        uiHandler.post {
            if (released.get()) return@post
            action(callback)
        }
    }

    companion object {
        private const val TAG = "EntryGuardCoordinator"
        private const val WIFI_CONFIRM_INTERVAL_MS = 500L
        private const val WIFI_CONFIRM_MAX_ATTEMPTS = 10
    }
}
