package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线检测服务。
 * 调用 has_hazard_answer 接口，判断当前帧是否需要弹窗。
 */
class OnlineHazardDetectionService {

    data class DetectionHandle(
        val requestId: Long,
        var requestHandle: MayHazardDeepVerifyService.RequestHandle? = null,
        var firstPacketReceived: Boolean = false,
        var canceled: Boolean = false,
        var timeoutRunnable: Runnable? = null,
    ) {
        fun cancel() {
            canceled = true
            requestHandle?.cancel()
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
    private val verifyService = MayHazardDeepVerifyService()

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
                Log.i(TAG, "detect requestId=$requestId snCode=$snCode endpoint=${MayHazardDeepVerifyProtocol.ANSWER_URL}")
                val requestHandle = verifyService.verify(
                    base64Image = base64Image,
                    callback = object : MayHazardDeepVerifyService.VerifyCallback {
                        override fun onSuccess(
                            hasHazard: Boolean,
                            metrics: MayHazardDeepVerifyService.VerifyMetrics,
                        ) {
                            if (handle.canceled || handle.firstPacketReceived) {
                                return
                            }
                            handle.firstPacketReceived = true
                            handle.timeoutRunnable?.let(mainHandler::removeCallbacks)
                            Log.i(
                                TAG,
                                "detect success requestId=$requestId hasHazard=$hasHazard answerMs=${metrics.answerMs} totalMs=${metrics.httpTotalMs}",
                            )
                            callback.onFirstPacket(handle, hasHazard, "has_hazard=$hasHazard")
                        }

                        override fun onFailure(
                            message: String,
                            metrics: MayHazardDeepVerifyService.VerifyMetrics,
                        ) {
                            handle.timeoutRunnable?.let(mainHandler::removeCallbacks)
                            if (!handle.canceled) {
                                Log.w(
                                    TAG,
                                    "detect failure requestId=$requestId message=$message answerMs=${metrics.answerMs} totalMs=${metrics.httpTotalMs}",
                                )
                                callback.onFailure(handle, message)
                            }
                        }
                    },
                )
                handle.requestHandle = requestHandle
            }
        }
        return handle
    }

    companion object {
        private const val TAG = "OnlineHazardDetect"
    }
}
