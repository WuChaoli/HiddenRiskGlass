package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper

/**
 * 结束巡检服务。
 * 当前后端接口未提供前，先保留独立的 mock 语义，避免与 isSave 接口混用。
 */
object InspectionFinishService {
    private const val MOCK_DELAY_MS = 300L
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun finishInspection(
        sessionId: String?,
        callback: Callback,
    ) {
        mainHandler.postDelayed(
            {
                if (sessionId.isNullOrBlank()) {
                    callback.onSuccess()
                } else {
                    callback.onSuccess()
                }
            },
            MOCK_DELAY_MS,
        )
    }
}
