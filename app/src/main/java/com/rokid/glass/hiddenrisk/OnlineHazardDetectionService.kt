package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线隐患识别调度服务。
 * ctype=1 检测阶段保持单飞，最多保留一个最新 pending；
 * ctype=0 详情阶段按需单次拉取。
 */
class OnlineHazardDetectionService(
    private val callback: Callback,
    private val aiArSseService: AiArSseService = AiArSseService(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    data class DetectionRequest(
        val epoch: Long,
        val cycleId: Long,
        val jpegBytes: ByteArray,
    )

    data class DetailRequest(
        val epoch: Long,
        val cycleId: Long,
        val jpegBytes: ByteArray,
    )

    interface Callback {
        fun onDetectionResult(request: DetectionRequest, hasHazard: Boolean, rawText: String)
        fun onDetectionFailure(request: DetectionRequest, message: String)
        fun onDetectionDropped(request: DetectionRequest, reason: String)
        fun onDetailSuccess(request: DetailRequest, fullText: String)
        fun onDetailFailure(request: DetailRequest, message: String)
    }

    private val encodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var activeDetectionRequest: DetectionRequest? = null
    private var activeDetectionHandle: AiArSseService.RequestHandle? = null
    private var pendingDetectionRequest: DetectionRequest? = null
    private var activeDetailRequest: DetailRequest? = null
    private var activeDetailHandle: AiArSseService.RequestHandle? = null
    private var lastDetectionStartedElapsedMs = 0L

    private val pendingStartRunnable = Runnable {
        flushPendingDetectionIfPossible()
    }

    fun submitDetection(request: DetectionRequest) {
        mainHandler.post {
            if (activeDetectionRequest != null || !canStartDetectionNow()) {
                pendingDetectionRequest?.let {
                    callback.onDetectionDropped(it, "replaced_by_newer_cycle")
                }
                pendingDetectionRequest = request
                schedulePendingDetectionIfNeeded()
                return@post
            }
            startDetection(request)
        }
    }

    fun fetchHazardDetails(request: DetailRequest) {
        mainHandler.post {
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = request
            encodeExecutor.execute {
                val base64Image = Base64.encodeToString(request.jpegBytes, Base64.NO_WRAP)
                mainHandler.post detailPost@{
                    if (activeDetailRequest != request) {
                        return@detailPost
                    }
                    activeDetailHandle = aiArSseService.fetchHazardDetails(
                        base64Image = base64Image,
                        callback = object : AiArSseService.DetailCallback {
                            override fun onOpened(handle: AiArSseService.RequestHandle) {
                                Log.i(TAG, "detail opened taskId=${handle.taskId} cycleId=${request.cycleId}")
                            }

                            override fun onSuccess(
                                handle: AiArSseService.RequestHandle,
                                fullText: String,
                            ) {
                                if (activeDetailRequest != request) {
                                    return
                                }
                                activeDetailRequest = null
                                activeDetailHandle = null
                                callback.onDetailSuccess(request, fullText)
                            }

                            override fun onFailure(
                                handle: AiArSseService.RequestHandle,
                                message: String,
                            ) {
                                if (activeDetailRequest != request) {
                                    return
                                }
                                activeDetailRequest = null
                                activeDetailHandle = null
                                callback.onDetailFailure(request, message)
                            }
                        },
                    )
                }
            }
        }
    }

    fun cancelAll() {
        mainHandler.post {
            mainHandler.removeCallbacks(pendingStartRunnable)
            activeDetectionHandle?.cancel()
            activeDetectionHandle = null
            activeDetectionRequest = null
            pendingDetectionRequest = null
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = null
        }
    }

    fun shutdown() {
        cancelAll()
        encodeExecutor.shutdownNow()
    }

    private fun startDetection(request: DetectionRequest) {
        activeDetectionRequest = request
        lastDetectionStartedElapsedMs = SystemClock.elapsedRealtime()
        encodeExecutor.execute {
            val base64Image = Base64.encodeToString(request.jpegBytes, Base64.NO_WRAP)
            mainHandler.post detectPost@{
                if (activeDetectionRequest != request) {
                    return@detectPost
                }
                activeDetectionHandle = aiArSseService.detectHasHazard(
                    base64Image = base64Image,
                    callback = object : AiArSseService.DetectCallback {
                        override fun onOpened(handle: AiArSseService.RequestHandle) {
                            Log.i(TAG, "detect opened taskId=${handle.taskId} cycleId=${request.cycleId}")
                        }

                        override fun onSuccess(
                            handle: AiArSseService.RequestHandle,
                            hasHazard: Boolean,
                            fullText: String,
                        ) {
                            if (activeDetectionRequest != request) {
                                return
                            }
                            clearActiveDetection()
                            callback.onDetectionResult(request, hasHazard, fullText)
                            flushPendingDetectionIfPossible()
                        }

                        override fun onFailure(
                            handle: AiArSseService.RequestHandle,
                            message: String,
                        ) {
                            if (activeDetectionRequest != request) {
                                return
                            }
                            clearActiveDetection()
                            callback.onDetectionFailure(request, message)
                            flushPendingDetectionIfPossible()
                        }
                    },
                )
            }
        }
    }

    private fun flushPendingDetectionIfPossible() {
        if (activeDetectionRequest != null) {
            return
        }
        val pending = pendingDetectionRequest ?: return
        if (!canStartDetectionNow()) {
            schedulePendingDetectionIfNeeded()
            return
        }
        pendingDetectionRequest = null
        startDetection(pending)
    }

    private fun schedulePendingDetectionIfNeeded() {
        if (activeDetectionRequest != null) {
            return
        }
        mainHandler.removeCallbacks(pendingStartRunnable)
        val delayMs = (lastDetectionStartedElapsedMs + MIN_DETECT_INTERVAL_MS - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        mainHandler.postDelayed(pendingStartRunnable, delayMs)
    }

    private fun canStartDetectionNow(): Boolean {
        return SystemClock.elapsedRealtime() - lastDetectionStartedElapsedMs >= MIN_DETECT_INTERVAL_MS
    }

    private fun clearActiveDetection() {
        activeDetectionHandle = null
        activeDetectionRequest = null
    }

    companion object {
        private const val TAG = "OnlineHazardDetect"
        private const val MIN_DETECT_INTERVAL_MS = 1000L
    }
}
