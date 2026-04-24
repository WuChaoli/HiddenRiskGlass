package com.rokid.glass.hiddenrisk

import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * 结束巡检服务。
 * 负责调用企业侧结束巡检接口。
 */
object InspectionFinishService {
    private const val TAG = "InspectionFinishApi"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    interface Callback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun finishInspection(
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        callback: Callback,
    ) {
        val requestUrl = runCatching { InspectionFinishApiProtocol.buildRequestUrl(baseUrl) }.getOrElse { error ->
            Log.e(TAG, "buildRequestUrl failed baseUrl=$baseUrl", error)
            deliverFailure(callback, InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
            return
        }
        val requestBodyJson = gson.toJson(
            InspectionFinishApiProtocol.PushEndRequest(
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
            )
        )
        Log.i(
            TAG,
            "finishInspection request url=$requestUrl objectId=$objectId userId=$userId customParamLength=${customParam.length}",
        )
        val request = Request.Builder()
            .url(requestUrl)
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody(InspectionFinishApiProtocol.JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "finishInspection failed url=$requestUrl", e)
                deliverFailure(callback, InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "finishInspection httpFailed code=${response.code} message=${response.message} url=$requestUrl",
                        )
                        deliverFailure(callback, InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    Log.i(TAG, "finishInspection response code=${response.code} body=$body")
                    val parseResult = InspectionFinishApiProtocol.parseResponseBody(body, gson)
                    if (parseResult.success) {
                        mainHandler.post { callback.onSuccess() }
                    } else {
                        Log.w(
                            TAG,
                            "finishInspection businessFailed url=$requestUrl message=${parseResult.message} body=$body",
                        )
                        deliverFailure(
                            callback,
                            parseResult.message ?: InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE,
                        )
                    }
                }
            }
        })
    }

    private fun deliverFailure(
        callback: Callback,
        message: String,
    ) {
        mainHandler.post { callback.onError(message) }
    }
}

internal object InspectionFinishApiProtocol {
    internal const val DEFAULT_FAILURE_MESSAGE = "结束巡检失败，请重试"
    internal val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private const val IF_END_VALUE = "1"

    data class PushEndRequest(
        val authCode: String,
        val objectId: String,
        val userId: String,
        val customParam: String,
        val ifEnd: String = IF_END_VALUE,
    )

    data class PushEndResponse(
        val code: Int? = null,
        val message: String? = null,
        val msg: String? = null,
    )

    data class ParseResult(
        val success: Boolean,
        val message: String? = null,
    )

    fun buildRequestUrl(baseUrl: String): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        require(normalizedBaseUrl.isNotEmpty()) { "baseUrl is blank" }
        val hasSmartGlassesPath = normalizedBaseUrl.substringAfter("://", normalizedBaseUrl)
            .contains("/smartGlasses")
        return if (hasSmartGlassesPath) {
            "$normalizedBaseUrl/pushHidDangerEnd"
        } else {
            "$normalizedBaseUrl/smartGlasses/pushHidDangerEnd"
        }
    }

    fun parseResponseBody(
        body: String,
        gson: Gson = Gson(),
    ): ParseResult {
        val apiResponse = runCatching {
            gson.fromJson(body, PushEndResponse::class.java)
        }.getOrNull() ?: return ParseResult(success = false, message = DEFAULT_FAILURE_MESSAGE)

        if (apiResponse.code == 0) {
            return ParseResult(success = true)
        }
        return ParseResult(
            success = false,
            message = firstNonBlank(apiResponse.message, apiResponse.msg) ?: DEFAULT_FAILURE_MESSAGE,
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
