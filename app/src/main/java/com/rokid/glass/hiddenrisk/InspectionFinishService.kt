package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.workflow.InspectionWorkflowSession
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 结束巡检服务。
 * 负责依次调用企业侧主接口与固定备份接口。
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
    ): RetryRequestHandle {
        val handle = RetryRequestHandle()
        val progress = InspectionWorkflowSession.finishSubmitProgress
        if (!progress.primaryDone) {
            submitPrimary(
                baseUrl = baseUrl,
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                handle = handle,
                callback = callback,
            )
        } else {
            submitBackup(
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                handle = handle,
                callback = callback,
            )
        }
        return handle
    }

    private fun submitPrimary(
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        handle: RetryRequestHandle,
        callback: Callback,
    ) {
        val requestUrl = runCatching { InspectionFinishApiProtocol.buildPrimaryRequestUrl(baseUrl) }.getOrElse { error ->
            Log.e(TAG, "buildPrimaryRequestUrl failed baseUrl=$baseUrl", error)
            InspectionWorkflowSession.clearFinishSubmitProgress()
            deliverFailure(callback, InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
            return
        }
        val requestBodyJson = InspectionFinishApiProtocol.buildRequestBodyJson(
            gson = gson,
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
        )
        submitSingleEndpoint(
            label = "primary",
            requestUrl = requestUrl,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            if (!outcome.success) {
                InspectionWorkflowSession.clearFinishSubmitProgress()
                deliverFailure(
                    callback,
                    outcome.message ?: InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE,
                )
                return@submitSingleEndpoint
            }
            Log.i(TAG, "finish primary success attempts=${outcome.attemptCount}")
            InspectionWorkflowSession.markFinishSubmitPrimaryDone()
            submitBackup(
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                handle = handle,
                callback = callback,
            )
        }
    }

    private fun submitBackup(
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        handle: RetryRequestHandle,
        callback: Callback,
    ) {
        val requestBodyJson = InspectionFinishApiProtocol.buildRequestBodyJson(
            gson = gson,
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
        )
        submitSingleEndpoint(
            label = "backup",
            requestUrl = InspectionFinishApiProtocol.BACKUP_REQUEST_URL,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            if (!outcome.success) {
                InspectionWorkflowSession.markFinishSubmitPrimaryDone()
                deliverFailure(
                    callback,
                    outcome.message ?: InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE,
                )
                return@submitSingleEndpoint
            }
            Log.i(TAG, "finish backup success attempts=${outcome.attemptCount}")
            InspectionWorkflowSession.markFinishSubmitBackupDone()
            InspectionWorkflowSession.clearFinishSubmitProgress()
            mainHandler.post { callback.onSuccess() }
        }
    }

    private fun submitSingleEndpoint(
        label: String,
        requestUrl: String,
        requestBodyJson: String,
        handle: RetryRequestHandle,
        onComplete: (RetryOutcome) -> Unit,
    ) {
        InspectionRetryExecutor.execute(
            label = "finish-$label",
            handle = handle,
            attemptBlock = { attempt, completion ->
                Log.i(TAG, "finish request endpoint=$label attempt=$attempt url=$requestUrl")
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody(InspectionFinishApiProtocol.JSON_MEDIA_TYPE))
                    .build()
                val call = client.newCall(request)
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "finish request failed endpoint=$label attempt=$attempt url=$requestUrl", e)
                        completion(
                            RetryAttemptResult(
                                success = false,
                                message = InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE,
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                Log.w(
                                    TAG,
                                    "finish request httpFailed endpoint=$label attempt=$attempt code=${response.code} url=$requestUrl",
                                )
                                completion(
                                    RetryAttemptResult(
                                        success = false,
                                        message = InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE,
                                    ),
                                )
                                return
                            }
                            val body = response.body?.string().orEmpty()
                            Log.i(
                                TAG,
                                "finish request response endpoint=$label attempt=$attempt code=${response.code} body=$body",
                            )
                            val parseResult = InspectionFinishApiProtocol.parseResponseBody(body, gson)
                            completion(
                                RetryAttemptResult(
                                    success = parseResult.success,
                                    message = parseResult.message,
                                ),
                            )
                        }
                    }
                })
                call
            },
            onComplete = onComplete,
        )
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
    internal const val BACKUP_REQUEST_URL = "http://183.147.142.133:7443/hxy/apis/hazardCheckRecord/hazardIsEnd"
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

    fun buildPrimaryRequestUrl(baseUrl: String): String {
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

    fun buildRequestBodyJson(
        gson: Gson,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
    ): String {
        return gson.toJson(
            PushEndRequest(
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
            ),
        )
    }

    fun parseResponseBody(
        body: String,
        gson: Gson = Gson(),
    ): ParseResult {
        val apiResponse = runCatching {
            gson.fromJson(body, PushEndResponse::class.java)
        }.getOrNull() ?: return ParseResult(success = false, message = DEFAULT_FAILURE_MESSAGE)

        if (apiResponse.code == 0 || apiResponse.code == 200) {
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
