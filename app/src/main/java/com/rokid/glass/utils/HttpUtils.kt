package com.rokid.glass.utils

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpUtils {

    companion object {
        private const val TAG = "HttpUtils"
        const val PRIMARY_SAVE_RESULT_URL = "http://183.147.142.133:7443/hxy/apis/third/smartGlasses/isSave"
        const val BACKUP_BASE_URL = "http://183.147.142.133:7443"
        const val BACKUP_SAVE_RESULT_URL = "$BACKUP_BASE_URL/hxy/apis/hazardCheckRecord/saveHazard"

        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private val gson = com.google.gson.Gson()
    }

    /**
     * 智能眼镜保存结果上报
     * @param snCode 设备序列号（必填）
     * @param isSave 是否保存：1-保存，0-不保存（可选）
     * @param sessionId 对话 ID（可选）
     * @param authorization Token（可选）
     * @param callback 回调接口
     */
    fun reportSaveResult(
        snCode: String,
        isSave: String? = null,
        sessionId: String? = null,
        authorization: String? = null,
        requestUrl: String = PRIMARY_SAVE_RESULT_URL,
        callback: SaveResultCallback
    ): Call {
        val requestBody = buildSaveRequestBody(snCode, isSave, sessionId)
        val jsonBody = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(requestUrl)
            .header("Content-Type", "application/json")
            .post(jsonBody)
            .apply {
                if (!authorization.isNullOrBlank()) {
                    addHeader("Authorization", authorization)
                }
            }
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "上报保存结果失败", e)
                callback.onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "上报保存结果成功: $responseBody")

                        try {
                            val apiResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            callback.onSuccess(apiResponse)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析响应数据失败", e)
                            callback.onFailure(Exception("解析响应数据失败: ${e.message}"))
                        }
                    } else {
                        val errorMsg = "HTTP 错误: ${response.code} - ${response.message}"
                        Log.e(TAG, errorMsg)
                        callback.onFailure(Exception(errorMsg))
                    }
                }
            }
        })
        return call
    }

    /**
     * 构建保存请求体
     */
    private fun buildSaveRequestBody(
        snCode: String,
        isSave: String?,
        sessionId: String?
    ): Map<String, Any?> {
        return mapOf(
            "snCode" to snCode,
            "isSave" to isSave,
            "sessionId" to sessionId
        ).filterValues { it != null }
    }

    /**
     * API 响应数据结构
     */
    data class ApiResponse(
        val code: Int?,
        val data: Any?,
        val msg: String?
    ) {
        fun isSuccess(): Boolean = code == 0 || code == 200
    }

    /**
     * 保存结果回调接口
     */
    interface SaveResultCallback {
        fun onSuccess(response: ApiResponse)
        fun onFailure(e: Exception)
    }
}
