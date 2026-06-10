package com.rokid.glass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.utils.AppFileLogger
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 入口守卫协调器。
 * 封装后台静默初始化流程：WiFi 检测与连接、SDK 初始化、自动更新检查。
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
        /** 有可用更新（JSON 字符串） */
        fun onAutoUpdateAvailable(updateInfoJson: String)
        /** 自动更新检查完成 */
        fun onAutoUpdateCheckComplete(hasUpdate: Boolean)
        /** 所有守卫就绪 */
        fun onAllGuardsReady()
    }

    enum class SdkInitState { IDLE, INITIALIZING, READY, FAILED }

    interface UpdateCheckListener {
        fun onComplete(hasUpdate: Boolean, updateInfoJson: String?)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateExecutor = Executors.newSingleThreadExecutor()

    // 线程安全的状态标记
    private val released = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val allGuardsReadyFired = AtomicBoolean(false)
    private val wifiCheckCompleted = AtomicBoolean(false)
    private val sdkCheckCompleted = AtomicBoolean(false)
    private val updateCheckCompleted = AtomicBoolean(false)

    // WiFi 相关状态（线程安全）
    private val wifiScannerLaunching = AtomicBoolean(false)
    private val wifiConnectInProgress = AtomicBoolean(false)

    // 懒加载的 AppUpdateManager，避免重复创建
    private val updateManager: AppUpdateManager by lazy {
        AppUpdateManager(context.applicationContext)
    }

    // SDK 监听器
    private val sdkListener = object : RokidSdkManager.Listener {
        override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
            if (released.get()) return
            when (state) {
                RokidSdkManager.SdkState.READY -> {
                    postSdkState(SdkInitState.READY)
                    sdkCheckCompleted.set(true)
                    startAutoUpdateCheck()
                    tryNotifyAllGuardsReady()
                }
                RokidSdkManager.SdkState.FAILED -> {
                    postSdkState(SdkInitState.FAILED)
                    sdkCheckCompleted.set(true)
                    startAutoUpdateCheck()
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
     * 顺序：WiFi -> SDK -> 更新检查（各阶段独立）。
     * 幂等：多次调用只有第一次生效。
     */
    fun startBackgroundGuards() {
        if (released.get()) {
            AppFileLogger.w(TAG, "startBackgroundGuards called after release")
            return
        }
        if (!started.compareAndSet(false, true)) {
            AppFileLogger.w(TAG, "startBackgroundGuards already started, ignoring")
            return
        }
        AppFileLogger.i(TAG, "startBackgroundGuards")
        startWifiCheck()
    }

    /**
     * 启动 WiFi QR 扫码。
     * 由 Activity 在用户触发时调用。
     */
    fun launchWifiScanner(activity: Activity) {
        if (released.get() || wifiScannerLaunching.get() || wifiConnectInProgress.get()) return
        wifiScannerLaunching.set(true)
        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
        runCatching {
            GlassScanner.launch(
                activity,
                WifiScanConfigFactory.create(activity),
                object : GlassScanCallback {
                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        wifiScannerLaunching.set(false)
                        if (content == null) {
                            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
                        } else {
                            handleWifiQrContent(content)
                        }
                    }

                    override fun onScanFailure(error: String) {
                        wifiScannerLaunching.set(false)
                        postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
                    }

                    override fun onScanCancelled() {
                        wifiScannerLaunching.set(false)
                        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
                    }
                },
            )
        }.onFailure { error ->
            wifiScannerLaunching.set(false)
            AppFileLogger.e(TAG, "launch wifi scanner failed", error)
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
        }
    }

    /**
     * 重新验证 WiFi 状态。
     * 当 Activity 从后台返回时调用，如果之前已就绪但 WiFi 已断开，
     * 重置 WiFi 状态并触发重新检查。
     * @return true 表示 WiFi 仍然正常或不需要处理；false 表示 WiFi 已断开，需要重新连接
     */
    fun revalidateWifiState(): Boolean {
        if (released.get()) return true
        if (!allGuardsReadyFired.get()) return true
        if (SystemStateUtils.getCurrentWifiSsid(context) != null) return true

        AppFileLogger.w(TAG, "wifi disconnected after all guards ready, resetting wifi state")
        // WiFi 已断开，重置相关状态
        allGuardsReadyFired.set(false)
        wifiCheckCompleted.set(false)
        postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
        return false
    }

    /**
     * 手动检查更新。
     */
    fun checkUpdateManually(listener: UpdateCheckListener) {
        if (released.get()) return
        updateExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = true)
                val json = result.info?.let { Gson().toJson(it) }
                uiHandler.post { listener.onComplete(result.hasUpdate, json) }
            } catch (error: IOException) {
                AppFileLogger.e(TAG, "manual update check failed", error)
                uiHandler.post { listener.onComplete(false, null) }
            }
        }
    }

    /**
     * 释放资源。
     * 优雅关闭：先 shutdown() 等待任务完成，超时后再 shutdownNow()。
     * 同时清除所有 pending 回调并强制设置各阶段完成标记。
     */
    fun release() {
        if (released.getAndSet(true)) return
        AppFileLogger.i(TAG, "release")

        // 清除所有 pending 的 UI 回调
        uiHandler.removeCallbacksAndMessages(null)

        // 移除 SDK 监听器
        RokidSdkManager.removeListener(sdkListener)

        // 强制设置所有完成标记，避免 release() 中间卡住其他流程
        wifiCheckCompleted.set(true)
        sdkCheckCompleted.set(true)
        updateCheckCompleted.set(true)

        // 优雅关闭线程池
        updateExecutor.shutdown()
        try {
            if (!updateExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppFileLogger.w(TAG, "updateExecutor did not terminate in time, forcing shutdown")
                updateExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            updateExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // -------------------------------------------------------------------------
    // WiFi
    // -------------------------------------------------------------------------

    private fun startWifiCheck() {
        if (SystemStateUtils.getCurrentWifiSsid(context) != null) {
            AppFileLogger.i(TAG, "wifi already connected")
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
                AppFileLogger.w(TAG, "wifi qr rejected reason=${result.reason}")
                postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
            }
            is WifiQrParseResult.Success -> {
                wifiConnectInProgress.set(true)
                postCallback { it.onWifiConnecting() }
                RokidSdkManager.connectWifi(result.payload) { success, errorMessage ->
                    wifiConnectInProgress.set(false)
                    if (!success) {
                        AppFileLogger.w(TAG, "wifi connect failed message=$errorMessage")
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
            AppFileLogger.i(TAG, "wifi connected confirmed")
            wifiCheckCompleted.set(true)
            postCallback { it.onWifiConnected() }
            startSdkInit()
            return
        }
        if (attempt >= getWifiConfirmMaxAttempts()) {
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_connect_failed) }
            return
        }
        uiHandler.postDelayed({ confirmWifiConnected(attempt + 1) }, getWifiConfirmIntervalMs())
    }

    // -------------------------------------------------------------------------
    // SDK 初始化
    // -------------------------------------------------------------------------

    private fun startSdkInit() {
        if (released.get()) return
        AppFileLogger.i(TAG, "startSdkInit")
        postSdkState(SdkInitState.INITIALIZING)

        // 先注册监听器，再 ensureInitialized，避免错过状态变更
        RokidSdkManager.addListener(sdkListener)
        val app = context.applicationContext as? Application
            ?: error("context.applicationContext is not an Application")
        RokidSdkManager.initialize(app)
        RokidSdkManager.ensureInitialized()

        // 如果 SDK 已经就绪，可能不会有状态回调，直接检查
        if (RokidSdkManager.state == RokidSdkManager.SdkState.READY && !sdkCheckCompleted.get()) {
            postSdkState(SdkInitState.READY)
            sdkCheckCompleted.set(true)
            startAutoUpdateCheck()
            tryNotifyAllGuardsReady()
        } else if (RokidSdkManager.state == RokidSdkManager.SdkState.FAILED && !sdkCheckCompleted.get()) {
            postSdkState(SdkInitState.FAILED)
            sdkCheckCompleted.set(true)
            startAutoUpdateCheck()
            tryNotifyAllGuardsReady()
        }
    }

    // -------------------------------------------------------------------------
    // 自动更新检查
    // -------------------------------------------------------------------------

    private fun startAutoUpdateCheck() {
        if (released.get() || updateCheckCompleted.get()) return
        AppFileLogger.i(TAG, "startAutoUpdateCheck")
        updateExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = false)
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
                AppFileLogger.i(TAG, "auto update check skipped: ${error.message}")
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
        if (allGuardsReadyFired.get()) return
        // WiFi 必须完成，其他阶段独立失败不阻塞
        // 自动更新检查是后台独立执行，不阻塞入口
        if (!wifiCheckCompleted.get()) return
        if (!sdkCheckCompleted.get()) return
        AppFileLogger.i(TAG, "all guards ready")
        allGuardsReadyFired.set(true)
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

    private inline fun postCallback(crossinline action: (Callback) -> Unit) {
        uiHandler.post {
            if (released.get()) return@post
            action(callback)
        }
    }

    /**
     * 获取 WiFi 确认间隔，优先从配置读取，否则使用默认值。
     */
    private fun getWifiConfirmIntervalMs(): Long {
        return runCatching {
            InspectionConfigRepository.get().aiInspection.wifiConfirmIntervalMs
        }.getOrDefault(DEFAULT_WIFI_CONFIRM_INTERVAL_MS)
    }

    /**
     * 获取 WiFi 确认最大重试次数，优先从配置读取，否则使用默认值。
     */
    private fun getWifiConfirmMaxAttempts(): Int {
        return runCatching {
            InspectionConfigRepository.get().aiInspection.wifiConfirmMaxAttempts
        }.getOrDefault(DEFAULT_WIFI_CONFIRM_MAX_ATTEMPTS)
    }

    companion object {
        private const val TAG = "EntryGuardCoordinator"
        private const val DEFAULT_WIFI_CONFIRM_INTERVAL_MS = 500L
        private const val DEFAULT_WIFI_CONFIRM_MAX_ATTEMPTS = 10
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_MS = 3000L
    }
}
