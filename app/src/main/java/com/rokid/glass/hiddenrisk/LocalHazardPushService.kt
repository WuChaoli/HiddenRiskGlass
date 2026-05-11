package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.config.SaveResultApiConfig
import com.rokid.glass.utils.HttpUtils
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 本地隐患保存上报服务。
 * 主备接口并行发送，两个端点都完成后再统一汇总结果。
 */
class LocalHazardPushService(
    private val apiConfig: SaveResultApiConfig =
        InspectionConfigRepository.get().network.saveResultApi,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(apiConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(apiConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(apiConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {

    data class HidDangerItem(
        val indexNum: String,
        val descrip: String,
        val advice: String,
        val hidNum: String,
        val hidLevel: String,
        val lawBasis: String,
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
        backupOnly: Boolean = false,
        callback: Callback,
    ): RetryRequestHandle {
        val handle = RetryRequestHandle()
        if (jpegBytes.isEmpty()) {
            callback.onFailure("隐患图片缺失")
            return handle
        }
        if (hidDanger.isEmpty()) {
            callback.onFailure("隐患信息缺失")
            return handle
        }

        val primaryUrl = runCatching { LocalHazardPushApiProtocol.buildPrimaryRequestUrl(baseUrl) }.getOrElse { error ->
            Log.e(TAG, "buildPrimaryRequestUrl failed baseUrl=$baseUrl", error)
            null
        }
        val requestBodyJson = LocalHazardPushApiProtocol.buildRequestBodyJson(
            gson = gson,
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
            jpegBytes = jpegBytes,
            hidDanger = hidDanger,
        )
        if (backupOnly) {
            submitSingleEndpoint(
                label = "backup",
                requestUrl = LocalHazardPushApiProtocol.BACKUP_REQUEST_URL,
                requestBodyJson = requestBodyJson,
                handle = handle,
            ) { outcome ->
                if (outcome.success) {
                    Log.i(TAG, "pushLocalHazard backup success attempts=${outcome.attemptCount}")
                    mainHandler.post { callback.onSuccess() }
                } else {
                    deliverFailure(callback, normalizeFailureMessage(outcome.message ?: DEFAULT_FAILURE_MESSAGE))
                }
            }
            return handle
        }
        val coordinator = DualEndpointSubmitCoordinator(
            labels = listOf("primary", "backup"),
        ) { outcomes ->
            val primaryOutcome = outcomes.getValue("primary")
            val backupOutcome = outcomes.getValue("backup")
            if (primaryOutcome.success && backupOutcome.success) {
                mainHandler.post { callback.onSuccess() }
            } else {
                Log.w(
                    TAG,
                    "pushLocalHazard final failed primarySuccess=${primaryOutcome.success} backupSuccess=${backupOutcome.success}",
                )
                deliverFailure(
                    callback,
                    normalizeFailureMessage(
                        firstFailureMessage(primaryOutcome, backupOutcome),
                    ),
                )
            }
        }

        if (primaryUrl == null) {
            coordinator.record(
                label = "primary",
                outcome = RetryOutcome(
                    success = false,
                    message = DEFAULT_FAILURE_MESSAGE,
                    attemptCount = 0,
                ),
            )
        } else {
            submitSingleEndpoint(
                label = "primary",
                requestUrl = primaryUrl,
                requestBodyJson = requestBodyJson,
                handle = handle,
            ) { outcome ->
                if (outcome.success) {
                    Log.i(TAG, "pushLocalHazard primary success attempts=${outcome.attemptCount}")
                }
                coordinator.record(label = "primary", outcome = outcome)
            }
        }
        submitSingleEndpoint(
            label = "backup",
            requestUrl = LocalHazardPushApiProtocol.BACKUP_REQUEST_URL,
            requestBodyJson = requestBodyJson,
            handle = handle,
        ) { outcome ->
            if (outcome.success) {
                Log.i(TAG, "pushLocalHazard backup success attempts=${outcome.attemptCount}")
            }
            coordinator.record(label = "backup", outcome = outcome)
        }
        return handle
    }

    private fun submitSingleEndpoint(
        label: String,
        requestUrl: String,
        requestBodyJson: String,
        handle: RetryRequestHandle,
        onComplete: (RetryOutcome) -> Unit,
    ) {
        InspectionRetryExecutor.execute(
            label = "local-hazard-$label",
            handle = handle,
            attemptBlock = { attempt, completion ->
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody(LocalHazardPushApiProtocol.JSON_MEDIA_TYPE))
                    .build()
                val call = client.newCall(request)
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "pushLocalHazard failed endpoint=$label attempt=$attempt url=$requestUrl", e)
                        completion(
                            RetryAttemptResult(
                                success = false,
                                message = DEFAULT_FAILURE_MESSAGE,
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                Log.w(
                                    TAG,
                                    "pushLocalHazard httpFailed endpoint=$label attempt=$attempt code=${response.code} message=${response.message} url=$requestUrl",
                                )
                                completion(
                                    RetryAttemptResult(
                                        success = false,
                                        message = DEFAULT_FAILURE_MESSAGE,
                                    ),
                                )
                                return
                            }
                            val body = response.body?.string().orEmpty()
                            Log.i(
                                TAG,
                                "pushLocalHazard response endpoint=$label attempt=$attempt code=${response.code} body=$body",
                            )
                            val parseResult = LocalHazardPushApiProtocol.parseResponseBody(
                                body = body,
                                gson = gson,
                            )
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
        mainHandler.post { callback.onFailure(message) }
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

    private fun firstFailureMessage(vararg outcomes: RetryOutcome): String {
        return outcomes.firstOrNull { !it.success }?.message ?: DEFAULT_FAILURE_MESSAGE
    }

    companion object {
        private const val TAG = "LocalHazardPushApi"
        private const val DEFAULT_FAILURE_MESSAGE = "本地隐患保存失败，请重试"
    }
}

internal object LocalHazardPushApiProtocol {
    internal val BACKUP_REQUEST_URL: String
        get() = "${HttpUtils.BACKUP_BASE_URL.trimEnd('/')}/hxy/apis/hazardCheckRecord/saveHazard"

    internal val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private const val IMAGE_DATA_URI_PREFIX = "data:image/jpg;base64,"

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
            "$normalizedBaseUrl/pushHidDanger"
        } else {
            "$normalizedBaseUrl/smartGlasses/pushHidDanger"
        }
    }

    fun buildRequestBodyJson(
        gson: Gson,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        jpegBytes: ByteArray,
        hidDanger: List<LocalHazardPushService.HidDangerItem>,
    ): String {
        return gson.toJson(
            LocalHazardPushService.PushRequest(
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                image = IMAGE_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(jpegBytes),
                hidDanger = hidDanger,
            ),
        )
    }

    fun parseResponseBody(
        body: String,
        gson: Gson = Gson(),
    ): ParseResult {
        val apiResponse = runCatching {
            gson.fromJson(body, LocalHazardPushService.PushResponse::class.java)
        }.getOrNull() ?: return ParseResult(success = false, message = "本地隐患保存失败，请重试")

        if (apiResponse.code == 0 || apiResponse.code == 200) {
            return ParseResult(success = true)
        }
        return ParseResult(
            success = false,
            message = firstNonBlank(apiResponse.message, apiResponse.msg) ?: "本地隐患保存失败，请重试",
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
