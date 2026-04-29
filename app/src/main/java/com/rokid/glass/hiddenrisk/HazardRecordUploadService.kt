package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 隐患录入上传服务。
 * 当前后端未提供正式接口，先用 mock 结果打通 UI。
 */
object HazardRecordUploadService {
    private const val MOCK_DELAY_MS = 500L
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UploadResult(
        val sessionId: String,
    )

    interface Callback {
        fun onSuccess(result: UploadResult)
        fun onError(message: String)
    }

    fun uploadHazardRecord(
        imageFile: File,
        snCode: String,
        callback: Callback,
    ) {
        if (!imageFile.exists()) {
            callback.onError("录入图片不存在")
            return
        }
        val sessionId = InspectionBackendSessionId.create(snCode, prefix = "record")
        mainHandler.postDelayed(
            { callback.onSuccess(UploadResult(sessionId = sessionId)) },
            MOCK_DELAY_MS,
        )
    }
}
