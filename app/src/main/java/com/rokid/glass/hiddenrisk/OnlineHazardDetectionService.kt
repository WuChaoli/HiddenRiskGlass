package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.rokid.glass.utils.SSEUtil
import okhttp3.Response
import okhttp3.sse.EventSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线检测服务。
 * 复用现有 SSE 接口，仅取首包作为“本帧是否需要弹窗”的依据。
 */
class OnlineHazardDetectionService {

    data class DetectionHandle(
        val requestId: Long,
        var eventSource: EventSource? = null,
        var firstPacketReceived: Boolean = false,
        var canceled: Boolean = false,
        var timeoutRunnable: Runnable? = null,
    ) {
        fun cancel() {
            canceled = true
            eventSource?.cancel()
        }
    }

    interface Callback {
        fun onFirstPacket(handle: DetectionHandle, hasHazard: Boolean, message: String)
        fun onTimeout(handle: DetectionHandle)
        fun onFailure(handle: DetectionHandle, message: String)
        fun onClosed(handle: DetectionHandle)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sseUtil = SSEUtil()

    fun detect(
        requestId: Long,
        jpegBytes: ByteArray,
        snCode: String,
        timeoutMs: Long,
        callback: Callback,
    ): DetectionHandle {
        val handle = DetectionHandle(requestId = requestId)
        val timeoutRunnable = Runnable {
            if (handle.canceled || handle.firstPacketReceived) {
                return@Runnable
            }
            Log.w(TAG, "online detect timeout requestId=$requestId")
            handle.cancel()
            callback.onTimeout(handle)
        }
        handle.timeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        encodeExecutor.execute {
            if (handle.canceled) {
                return@execute
            }
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            mainHandler.post {
                if (handle.canceled) {
                    return@post
                }
                sseUtil.connect(
                    imageUrl = base64Image,
                    snCode = snCode,
                    sessionId = "${System.currentTimeMillis()}_$snCode",
                    listener = object : SSEUtil.SSEListener {
                        override fun onOpened() = Unit

                        override fun onMessage(data: String) {
                            if (handle.canceled || handle.firstPacketReceived) {
                                return
                            }
                            handle.firstPacketReceived = true
                            handle.timeoutRunnable?.let(mainHandler::removeCallbacks)
                            callback.onFirstPacket(handle, inferHasHazard(data), data)
                            handle.eventSource?.cancel()
                        }

                        override fun onClosed() {
                            handle.timeoutRunnable?.let(mainHandler::removeCallbacks)
                            callback.onClosed(handle)
                        }

                        override fun onFailure(t: Throwable?, response: Response?) {
                            handle.timeoutRunnable?.let(mainHandler::removeCallbacks)
                            if (!handle.canceled) {
                                callback.onFailure(handle, t?.message ?: response?.message ?: "在线识别失败")
                            }
                        }

                        override fun onEventSourceCreated(eventSource: EventSource) {
                            handle.eventSource = eventSource
                        }
                    },
                )
            }
        }
        return handle
    }

    private fun inferHasHazard(message: String): Boolean {
        val normalized = message.lowercase()
        if (normalized.contains("未发现") || normalized.contains("安全") || normalized.contains("正常")) {
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "OnlineHazardDetect"
    }
}
