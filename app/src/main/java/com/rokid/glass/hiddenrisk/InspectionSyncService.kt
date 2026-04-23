package com.rokid.glass.hiddenrisk

import android.util.Log
import com.rokid.glass.utils.HttpUtils

/**
 * 巡检结果同步到手机端服务。
 */
object InspectionSyncService {
    private const val TAG = "InspectionSyncService"

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun syncAnalysisToPhone(
        sessionId: String,
        callback: Callback,
    ) {
        if (sessionId.isBlank()) {
            callback.onError("缺少分析会话 ID")
            return
        }
        HttpUtils().reportSaveResult(
            snCode = RokidSdkManager.getSerialNumber(),
            isSave = "1",
            sessionId = sessionId,
            callback = object : HttpUtils.SaveResultCallback {
                override fun onSuccess(response: HttpUtils.ApiResponse) {
                    if (response.code == 200) {
                        callback.onSuccess()
                    } else {
                        val message = response.msg ?: "同步失败"
                        Log.e(TAG, "sync business failure code=${response.code} msg=$message")
                        callback.onError(message)
                    }
                }

                override fun onFailure(e: Exception) {
                    Log.e(TAG, "sync request failure", e)
                    callback.onError(e.message ?: "同步失败")
                }
            },
        )
    }
}
