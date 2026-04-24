package com.rokid.glass

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 企业对象信息查询服务。
 * 负责根据二维码中的 baseUrl、authCode、objectId 拉取对象详情与历史隐患。
 */
class EnterpriseObjectMessageService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {

    data class ObjectMessageRequest(
        val authCode: String,
        val objectId: String,
    )

    data class ObjectMessageResponse(
        val code: Int? = null,
        val message: String? = null,
        val msg: String? = null,
        val data: ObjectMessageData? = null,
    )

    data class ObjectMessageData(
        val objectName: String? = null,
        val areaName: String? = null,
        val domain: String? = null,
        val tags: String? = null,
        val riskLevel: String? = null,
        val hidDanger: List<ObjectMessageHazard>? = null,
    )

    data class ObjectMessageHazard(
        val indexNum: String? = null,
        val descrip: String? = null,
    )

    interface ObjectMessageCallback {
        fun onSuccess(data: ObjectMessageData)
        fun onFailure(message: String)
    }

    class RequestHandle internal constructor() {
        @Volatile
        private var canceled = false
        private val calls = mutableListOf<Call>()

        fun bind(call: Call) {
            synchronized(calls) {
                if (canceled) {
                    call.cancel()
                } else {
                    calls += call
                }
            }
        }

        fun cancel() {
            val snapshot = synchronized(calls) {
                canceled = true
                calls.toList()
            }
            snapshot.forEach(Call::cancel)
        }

        fun isCanceled(): Boolean = canceled
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchObjectMessage(
        baseUrl: String,
        authCode: String,
        objectId: String,
        callback: ObjectMessageCallback,
    ): RequestHandle {
        val handle = RequestHandle()
        val requestUrl = runCatching { buildRequestUrl(baseUrl) }.getOrElse { error ->
            mainHandler.post {
                if (!handle.isCanceled()) {
                    callback.onFailure(DEFAULT_FAILURE_MESSAGE)
                }
            }
            Log.e(TAG, "buildRequestUrl failed baseUrl=$baseUrl", error)
            return handle
        }
        val requestBodyJson = gson.toJson(ObjectMessageRequest(authCode = authCode, objectId = objectId))
        Log.i(TAG, "fetchObjectMessage request url=$requestUrl body=$requestBodyJson")
        val requestBody = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(requestUrl)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()
        val call = client.newCall(request)
        handle.bind(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (handle.isCanceled()) return
                Log.e(TAG, "fetchObjectMessage failed url=$requestUrl", e)
                deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (handle.isCanceled()) return
                    if (!response.isSuccessful) {
                        Log.w(TAG, "fetchObjectMessage httpFailed code=${response.code} message=${response.message} url=$requestUrl")
                        deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    Log.i(TAG, "fetchObjectMessage response code=${response.code} body=$body")
                    val apiResponse = runCatching {
                        gson.fromJson(body, ObjectMessageResponse::class.java)
                    }.onFailure { error ->
                        Log.e(TAG, "fetchObjectMessage parseFailed body=$body", error)
                    }.getOrNull()
                    if (apiResponse == null) {
                        deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    val responseData = apiResponse.data
                    if (apiResponse.code != 0 || responseData == null) {
                        val message = firstNonBlank(apiResponse.message, apiResponse.msg)
                            ?: DEFAULT_FAILURE_MESSAGE
                        Log.w(TAG, "fetchObjectMessage businessFailed code=${apiResponse.code} message=$message body=$body")
                        deliverFailure(handle, callback, message)
                        return
                    }
                    mainHandler.post {
                        if (handle.isCanceled()) return@post
                        callback.onSuccess(responseData)
                    }
                }
            }
        })
        return handle
    }

    private fun deliverFailure(
        handle: RequestHandle,
        callback: ObjectMessageCallback,
        message: String,
    ) {
        mainHandler.post {
            if (handle.isCanceled()) return@post
            callback.onFailure(message)
        }
    }

    private fun buildRequestUrl(baseUrl: String): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        require(normalizedBaseUrl.isNotEmpty()) { "baseUrl is blank" }
        val hasSmartGlassesPath = normalizedBaseUrl.substringAfter("://", normalizedBaseUrl)
            .contains("/smartGlasses")
        return if (hasSmartGlassesPath) {
            "$normalizedBaseUrl/getObjectMessage"
        } else {
            "$normalizedBaseUrl/smartGlasses/getObjectMessage"
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    companion object {
        private const val TAG = "EnterpriseObjectApi"
        private const val DEFAULT_FAILURE_MESSAGE = "对象信息获取失败，请重试"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
