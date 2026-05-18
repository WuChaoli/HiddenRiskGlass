package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.config.SaveResultApiConfig
import com.rokid.glass.utils.AppFileLogger
import com.rokid.glass.utils.firstNonBlank
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 本地隐患保存上报服务。
 * 使用 InspectionRetryExecutor 通过主 URL 提交，最多重试 4 次。
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
            AppFileLogger.e(TAG, "buildPrimaryRequestUrl failed baseUrl=$baseUrl", error)
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
        AppFileLogger.i(
            TAG,
            "pushLocalHazard start objectId=$objectId jpegBytes=${jpegBytes.size} hazardCount=${hidDanger.size} url=$primaryUrl",
        )
        if (primaryUrl == null) {
            callback.onFailure("本地隐患保存失败，请重试")
            return handle
        }
        val requestContext = RequestContext(
            jpegBytesSize = jpegBytes.size,
            hazardCount = hidDanger.size,
            requestBodyBytes = requestBodyJson.toByteArray(Charsets.UTF_8).size,
        )
        submitSingleEndpoint(
            label = "primary",
            requestUrl = primaryUrl,
            requestBodyJson = requestBodyJson,
            handle = handle,
            requestContext = requestContext,
        ) { outcome ->
            if (outcome.success) {
                AppFileLogger.i(TAG, "pushLocalHazard success attempts=${outcome.attemptCount}")
                mainHandler.post { callback.onSuccess() }
            } else {
                AppFileLogger.w(
                    TAG,
                    "pushLocalHazard failed attempts=${outcome.attemptCount} message=${outcome.message}",
                )
                deliverFailure(callback, normalizeFailureMessage(outcome.message ?: DEFAULT_FAILURE_MESSAGE))
            }
        }
        return handle
    }

    private fun submitSingleEndpoint(
        label: String,
        requestUrl: String,
        requestBodyJson: String,
        handle: RetryRequestHandle,
        requestContext: RequestContext,
        onComplete: (RetryOutcome) -> Unit,
    ) {
        InspectionRetryExecutor.execute(
            label = "local-hazard-$label",
            handle = handle,
            attemptBlock = { attempt, completion ->
                AppFileLogger.i(
                    TAG,
                    "pushLocalHazard attemptStart endpoint=$label attempt=$attempt requestBodyBytes=${requestContext.requestBodyBytes} jpegBytes=${requestContext.jpegBytesSize} hazardCount=${requestContext.hazardCount} canceled=${handle.isCanceled()} url=$requestUrl",
                )
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody(LocalHazardPushApiProtocol.JSON_MEDIA_TYPE))
                    .build()
                val call = client.newCall(request)
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val failureType = classifyFailure(e, call.isCanceled(), handle.isCanceled())
                        AppFileLogger.e(
                            TAG,
                            "pushLocalHazard failed endpoint=$label attempt=$attempt callCanceled=${call.isCanceled()} handleCanceled=${handle.isCanceled()} failureType=$failureType requestBodyBytes=${requestContext.requestBodyBytes} jpegBytes=${requestContext.jpegBytesSize} url=$requestUrl",
                            e,
                        )
                        completion(
                            RetryAttemptResult(
                                success = false,
                                message = "$DEFAULT_FAILURE_MESSAGE[$failureType]",
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                AppFileLogger.w(
                                    TAG,
                                    "pushLocalHazard httpFailed endpoint=$label attempt=$attempt code=${response.code} message=${response.message} callCanceled=${call.isCanceled()} requestBodyBytes=${requestContext.requestBodyBytes} url=$requestUrl",
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
                            AppFileLogger.i(
                                TAG,
                                "pushLocalHazard response endpoint=$label attempt=$attempt code=${response.code} bodyLength=${body.length} body=$body",
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

    companion object {
        private const val TAG = "LocalHazardPushApi"
        private const val DEFAULT_FAILURE_MESSAGE = "本地隐患保存失败，请重试"
    }

    private data class RequestContext(
        val jpegBytesSize: Int,
        val hazardCount: Int,
        val requestBodyBytes: Int,
    )

    private fun classifyFailure(
        error: IOException,
        callCanceled: Boolean,
        handleCanceled: Boolean,
    ): String {
        if (callCanceled || handleCanceled) {
            return "client_canceled"
        }
        return when (error) {
            is SocketTimeoutException -> "socket_timeout"
            is SocketException -> {
                if (error.message?.contains("Socket closed", ignoreCase = true) == true) {
                    "socket_closed"
                } else {
                    "socket_exception"
                }
            }
            else -> error.javaClass.simpleName.ifBlank { "io_exception" }
        }
    }
}

internal object LocalHazardPushApiProtocol {
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

}
