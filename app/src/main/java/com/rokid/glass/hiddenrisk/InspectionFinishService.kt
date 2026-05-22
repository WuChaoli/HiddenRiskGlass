package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.config.SaveResultApiConfig
import com.rokid.glass.utils.firstNonBlank
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glass.network.HttpClientProvider
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 结束巡检服务。
 * 使用 InspectionRetryExecutor 通过主 URL 提交，最多重试 4 次。
 */
object InspectionFinishService {
    private const val TAG = "InspectionFinishApi"
    private val mainHandler = Handler(Looper.getMainLooper())
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
        InspectionWorkflowSession.clearFinishSubmitProgress()
        val primaryUrl = runCatching { InspectionFinishApiProtocol.buildPrimaryRequestUrl(baseUrl) }.getOrElse { error ->
            Log.e(TAG, "buildPrimaryRequestUrl failed baseUrl=$baseUrl", error)
            null
        }
        val requestBodyJson = InspectionFinishApiProtocol.buildRequestBodyJson(
            gson = gson,
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
        )
        if (primaryUrl == null) {
            InspectionWorkflowSession.clearFinishSubmitProgress()
            deliverFailure(callback, InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
            return handle
        }
        val apiConfig = InspectionConfigRepository.get().network.saveResultApi
        if (apiConfig.enableBackupUpload) {
            submitDualEndpoint(
                primaryUrl = primaryUrl,
                backupUrl = InspectionFinishApiProtocol.buildBackupRequestUrl(apiConfig),
                requestBodyJson = requestBodyJson,
                handle = handle,
                callback = callback,
            )
            return handle
        }
        submitSingleEndpoint(
            label = "primary",
            requestUrl = primaryUrl,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            if (outcome.success) {
                Log.i(TAG, "finish success attempts=${outcome.attemptCount}")
                InspectionWorkflowSession.markFinishSubmitPrimaryDone()
                InspectionWorkflowSession.clearFinishSubmitProgress()
                mainHandler.post { callback.onSuccess() }
            } else {
                InspectionWorkflowSession.clearFinishSubmitProgress()
                Log.w(
                    TAG,
                    "finish failed attempts=${outcome.attemptCount} message=${outcome.message}",
                )
                deliverFailure(callback, outcome.message ?: InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE)
            }
        }
        return handle
    }

    private fun submitDualEndpoint(
        primaryUrl: String,
        backupUrl: String,
        requestBodyJson: String,
        handle: RetryRequestHandle,
        callback: Callback,
    ) {
        val coordinator = DualEndpointSubmitCoordinator(
            labels = listOf("primary", "backup"),
        ) { outcomes ->
            val primaryOutcome = outcomes.getValue("primary")
            val backupOutcome = outcomes.getValue("backup")
            InspectionWorkflowSession.clearFinishSubmitProgress()
            if (primaryOutcome.success && backupOutcome.success) {
                Log.i(
                    TAG,
                    "finish success primaryAttempts=${primaryOutcome.attemptCount} backupAttempts=${backupOutcome.attemptCount}",
                )
                mainHandler.post { callback.onSuccess() }
            } else {
                Log.w(
                    TAG,
                    "finish failed primarySuccess=${primaryOutcome.success} backupSuccess=${backupOutcome.success}",
                )
                deliverFailure(callback, firstFailureMessage(primaryOutcome, backupOutcome))
            }
        }
        submitSingleEndpoint(
            label = "primary",
            requestUrl = primaryUrl,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            if (outcome.success) {
                InspectionWorkflowSession.markFinishSubmitPrimaryDone()
            }
            coordinator.record(label = "primary", outcome = outcome)
        }
        submitSingleEndpoint(
            label = "backup",
            requestUrl = backupUrl,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            coordinator.record(label = "backup", outcome = outcome)
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
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody(InspectionFinishApiProtocol.JSON_MEDIA_TYPE))
                    .build()
                val client = createClient()
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

    private fun createClient() = HttpClientProvider.inspectionClient

    private fun firstFailureMessage(vararg outcomes: RetryOutcome): String {
        return outcomes.firstOrNull { !it.success }?.message
            ?: InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE
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

    fun buildBackupRequestUrl(apiConfig: SaveResultApiConfig): String {
        return apiConfig.backupFinishResultUrl
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

}
