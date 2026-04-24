package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Base64
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
 * 本地隐患保存上报服务。
 * 负责将本地知识库命中的隐患按企业接口要求提交到后端。
 */
class LocalHazardPushService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {

    data class HidDangerItem(
        val indexNum: String,
        val descrip: String,
        val advice: String,
        val hidNum: String,
        val hidLevel: String,
    )

    data class PushRequest(
        val authCode: String,
        val objectId: String,
        val userId: String,
        val customParam: String,
        val image: String,
        val hidDanger: List<HidDangerItem>,
    )

    data class PushResponse(
        val code: Int? = null,
        val message: String? = null,
        val msg: String? = null,
    )

    interface Callback {
        fun onSuccess()
        fun onFailure(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun pushLocalHazard(
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        jpegBytes: ByteArray,
        hidDanger: List<HidDangerItem>,
        callback: Callback,
    ) {
        if (jpegBytes.isEmpty()) {
            callback.onFailure("隐患图片缺失")
            return
        }
        if (hidDanger.isEmpty()) {
            callback.onFailure("隐患信息缺失")
            return
        }
        val requestUrl = runCatching { buildRequestUrl(baseUrl) }.getOrElse { error ->
            Log.e(TAG, "buildRequestUrl failed baseUrl=$baseUrl", error)
            callback.onFailure(DEFAULT_FAILURE_MESSAGE)
            return
        }
        val requestBody = PushRequest(
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
            image = IMAGE_DATA_URI_PREFIX + Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
            hidDanger = hidDanger,
        )
        val requestBodyJson = gson.toJson(requestBody)
        Log.i(
            TAG,
            "pushLocalHazard request url=$requestUrl objectId=$objectId userId=$userId customParamLength=${customParam.length} imageBytes=${jpegBytes.size} hidDangerCount=${hidDanger.size}",
        )
        val request = Request.Builder()
            .url(requestUrl)
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "pushLocalHazard failed url=$requestUrl", e)
                deliverFailure(callback, DEFAULT_FAILURE_MESSAGE)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "pushLocalHazard httpFailed code=${response.code} message=${response.message} url=$requestUrl")
                        deliverFailure(callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    Log.i(TAG, "pushLocalHazard response code=${response.code} body=$body")
                    val apiResponse = runCatching {
                        gson.fromJson(body, PushResponse::class.java)
                    }.onFailure { error ->
                        Log.e(TAG, "pushLocalHazard parseFailed body=$body", error)
                    }.getOrNull()
                    if (apiResponse == null) {
                        deliverFailure(callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    if (apiResponse.code == 0) {
                        mainHandler.post { callback.onSuccess() }
                        return
                    }
                    val message = firstNonBlank(apiResponse.message, apiResponse.msg)
                        ?: DEFAULT_FAILURE_MESSAGE
                    Log.w(TAG, "pushLocalHazard businessFailed code=${apiResponse.code} message=$message body=$body")
                    deliverFailure(callback, normalizeFailureMessage(message))
                }
            }
        })
    }

    private fun deliverFailure(
        callback: Callback,
        message: String,
    ) {
        mainHandler.post { callback.onFailure(message) }
    }

    private fun buildRequestUrl(baseUrl: String): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        require(normalizedBaseUrl.isNotEmpty()) { "baseUrl is blank" }
        val hasSmartGlassesPath = normalizedBaseUrl.substringAfter("://", normalizedBaseUrl)
            .contains("/smartGlasses")
        return if (hasSmartGlassesPath) {
            "$normalizedBaseUrl/pushHidDanger"
        } else {
            "$normalizedBaseUrl/smartGlasses/pushHidDanger"
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun normalizeFailureMessage(message: String): String {
        val normalized = message.trim()
        val lowerCase = normalized.lowercase()
        return when {
            "img_base64" in lowerCase ||
                "base64" in lowerCase ||
                "data too long" in lowerCase ->
                "图片上传失败，请重试"
            normalized.isBlank() -> DEFAULT_FAILURE_MESSAGE
            else -> DEFAULT_FAILURE_MESSAGE
        }
    }

    companion object {
        private const val TAG = "LocalHazardPushApi"
        private const val DEFAULT_FAILURE_MESSAGE = "本地隐患保存失败，请重试"
        private const val IMAGE_DATA_URI_PREFIX = "data:image/jpg;base64,"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
