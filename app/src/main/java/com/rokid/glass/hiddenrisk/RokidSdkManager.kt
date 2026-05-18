package com.rokid.glass.hiddenrisk

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.device.IDeviceService
import java.util.concurrent.CopyOnWriteArraySet

object RokidSdkManager {
    private const val CLIENT_ID = "com.rokid.glesse.hiddenrisk"
    private const val REBIND_DELAY_MS = 4000L

    enum class SdkState {
        IDLE,
        BINDING,
        READY,
        FAILED,
        REBINDING,
    }

    interface Listener {
        fun onSdkStateChanged(state: SdkState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val stateLock = Any()

    @Volatile
    private var application: Application? = null

    @Volatile
    private var deviceService: IDeviceService? = null

    @Volatile
    var state: SdkState = SdkState.IDLE
        private set

    @Volatile
    var lastErrorMessage: String? = null
        private set

    private val rebindRunnable = Runnable {
        bindSdk(force = true, rebinding = true)
    }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            val serviceResult = runCatching { GlassSdk.getGlassDeviceService() }
            if (serviceResult.isFailure) {
                deviceService = null
                updateState(SdkState.FAILED, serviceResult.exceptionOrNull()?.message ?: "get glass device service failed")
                return
            }

            deviceService = serviceResult.getOrNull()
            updateState(SdkState.READY, null)
        }
    }

    private val serviceConnectionCallback = object : IServiceConnectionCallback {
        override fun onServiceConnected() {
            runCatching {
                GlassSdk.registerClient(CLIENT_ID, clientCallback)
            }.onFailure { error ->
                deviceService = null
                updateState(SdkState.FAILED, error.message ?: "register client failed")
            }
        }

        override fun onServiceDisconnected() {
            deviceService = null
            updateState(SdkState.FAILED, "security service disconnected")
        }

        override fun onBindingDied() {
            deviceService = null
            updateState(SdkState.REBINDING, "security service binding died")
            mainHandler.removeCallbacks(rebindRunnable)
            mainHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS)
        }
    }

    fun initialize(application: Application) {
        this.application = application
        bindSdk(force = false, rebinding = false)
    }

    fun ensureInitialized() {
        bindSdk(force = false, rebinding = false)
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        mainHandler.post { listener.onSdkStateChanged(state) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getSerialNumber(): String {
        val localDeviceService = deviceService
            ?: runCatching { GlassSdk.getGlassDeviceService() }.getOrNull()
            ?: return ""
        return runCatching { localDeviceService.serialNumber }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun getDeviceSummary(): String? {
        val localDeviceService = deviceService
            ?: runCatching { GlassSdk.getGlassDeviceService() }.getOrNull()
            ?: return null
        val parts = mutableListOf<String>()

        runCatching { localDeviceService.serialNumber }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.add("SN:$it") }

        runCatching { localDeviceService.deviceStatusInfo?.powerValue }
            .getOrNull()
            ?.let { parts.add("Power:${it}%") }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun release() {
        mainHandler.removeCallbacks(rebindRunnable)
        runCatching { GlassSdk.release() }
        deviceService = null
        updateState(SdkState.IDLE, null)
        listeners.clear()
    }

    private fun bindSdk(force: Boolean, rebinding: Boolean) {
        val app = application ?: return

        synchronized(stateLock) {
            val sdkReadyResult = runCatching { GlassSdk.isReady() }
            if (sdkReadyResult.isFailure) {
                deviceService = null
                updateState(SdkState.FAILED, sdkReadyResult.exceptionOrNull()?.message ?: "GlassSdk.isReady failed")
                return
            }

            if (sdkReadyResult.getOrDefault(false)) {
                val deviceServiceResult = runCatching { GlassSdk.getGlassDeviceService() }
                if (deviceServiceResult.isFailure) {
                    deviceService = null
                    updateState(
                        SdkState.FAILED,
                        deviceServiceResult.exceptionOrNull()?.message ?: "get glass device service failed",
                    )
                    return
                }

                deviceService = deviceServiceResult.getOrNull()
                updateState(SdkState.READY, null)
                return
            }

            if (!force && (state == SdkState.BINDING || state == SdkState.READY)) {
                return
            }

            updateState(if (rebinding) SdkState.REBINDING else SdkState.BINDING, null)
        }

        runCatching {
            GlassSdk.bindSecurityService(app, serviceConnectionCallback)
        }.onFailure { error ->
            deviceService = null
            updateState(SdkState.FAILED, error.message ?: "bind sdk failed")
        }
    }

    private fun updateState(newState: SdkState, errorMessage: String?) {
        state = newState
        lastErrorMessage = errorMessage
        mainHandler.post {
            listeners.forEach { it.onSdkStateChanged(newState) }
        }
    }
}
