package com.rokid.glass

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.EnterpriseObjectApiConfig
import com.rokid.glass.config.InspectionConfigRepository
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * 企业对象信息查询服务。
 * 负责根据二维码中的 baseUrl、authCode、objectId 拉取对象详情与历史隐患。
 */
class EnterpriseObjectMessageService(
    private val apiConfig: EnterpriseObjectApiConfig =
        InspectionConfigRepository.get().network.enterpriseObjectApi,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(apiConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(apiConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(apiConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
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

    class RequestHandle internal constructor(
        val requestId: String,
    ) {
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
        val requestId = UUID.randomUUID().toString()
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        val normalizedBaseUrl = normalizeBaseUrlForLog(baseUrl)
        val maskedObjectId = maskTokenForLog(objectId)
        val maskedAuthCode = maskTokenForLog(authCode)
        val requestContext = buildRequestContextLog(
            requestId = requestId,
            normalizedBaseUrl = normalizedBaseUrl,
            objectId = maskedObjectId,
            authCode = maskedAuthCode,
        )
        val handle = RequestHandle(requestId = requestId)
        val requestUrl = runCatching { buildRequestUrl(baseUrl) }.getOrElse { error ->
            mainHandler.post {
                if (!handle.isCanceled()) {
                    callback.onFailure(DEFAULT_FAILURE_MESSAGE)
                }
            }
            Log.e(
                TAG,
                "fetchObjectMessage buildRequestUrlFailed $requestContext errorType=${error.javaClass.simpleName} errorMessage=${error.message}",
                error,
            )
            return handle
        }
        val requestBodyJson = gson.toJson(ObjectMessageRequest(authCode = authCode, objectId = objectId))
        Log.i(
            TAG,
            "fetchObjectMessage requestStart $requestContext requestUrl=$requestUrl requestBody=${buildMaskedRequestBodyLog(maskedAuthCode, maskedObjectId)}",
        )
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
                Log.e(
                    TAG,
                    "fetchObjectMessage requestFailed $requestContext requestUrl=$requestUrl elapsedMs=${elapsedSince(startedAtElapsedMs)} errorType=${e.javaClass.simpleName} errorMessage=${e.message}",
                    e,
                )
                deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (handle.isCanceled()) return
                    val elapsedMs = elapsedSince(startedAtElapsedMs)
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "fetchObjectMessage httpFailed $requestContext requestUrl=$requestUrl elapsedMs=$elapsedMs httpCode=${response.code} httpMessage=${response.message} body=$body",
                        )
                        deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    Log.i(
                        TAG,
                        "fetchObjectMessage responseReceived $requestContext requestUrl=$requestUrl elapsedMs=$elapsedMs httpCode=${response.code} bodyLength=${body.length}",
                    )
                    val apiResponse = runCatching {
                        gson.fromJson(body, ObjectMessageResponse::class.java)
                    }.onFailure { error ->
                        Log.e(
                            TAG,
                            "fetchObjectMessage parseFailed $requestContext requestUrl=$requestUrl elapsedMs=$elapsedMs errorType=${error.javaClass.simpleName} errorMessage=${error.message} body=$body",
                            error,
                        )
                    }.getOrNull()
                    if (apiResponse == null) {
                        deliverFailure(handle, callback, DEFAULT_FAILURE_MESSAGE)
                        return
                    }
                    val responseData = apiResponse.data
                    if (apiResponse.code != 0 || responseData == null) {
                        val message = firstNonBlank(apiResponse.message, apiResponse.msg)
                            ?: DEFAULT_FAILURE_MESSAGE
                        Log.w(
                            TAG,
                            "fetchObjectMessage businessFailed $requestContext requestUrl=$requestUrl elapsedMs=$elapsedMs businessCode=${apiResponse.code} businessMessage=$message dataNull=${responseData == null} body=$body",
                        )
                        deliverFailure(handle, callback, message)
                        return
                    }
                    logResponseShape(
                        requestContext = requestContext,
                        requestUrl = requestUrl,
                        elapsedMs = elapsedMs,
                        data = responseData,
                    )
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

    private fun logResponseShape(
        requestContext: String,
        requestUrl: String,
        elapsedMs: Long,
        data: ObjectMessageData,
    ) {
        val hazardList = data.hidDanger.orEmpty()
        val describedHazardCount = hazardList.count { !it.descrip.isNullOrBlank() }
        val missingFields = buildList {
            if (data.objectName.isNullOrBlank()) add("objectName")
            if (data.areaName.isNullOrBlank()) add("areaName")
            if (data.domain.isNullOrBlank()) add("domain")
            if (data.tags.isNullOrBlank()) add("tags")
            if (data.riskLevel.isNullOrBlank()) add("riskLevel")
            if (data.hidDanger == null) add("hidDanger")
        }
        val summary =
            "requestUrl=$requestUrl elapsedMs=$elapsedMs objectNameBlank=${data.objectName.isNullOrBlank()} areaNameBlank=${data.areaName.isNullOrBlank()} domainBlank=${data.domain.isNullOrBlank()} tagsBlank=${data.tags.isNullOrBlank()} riskLevelBlank=${data.riskLevel.isNullOrBlank()} hazardCount=${hazardList.size} hazardWithDescriptionCount=$describedHazardCount"
        if (missingFields.isNotEmpty()) {
            Log.w(
                TAG,
                "fetchObjectMessage responseShapeWarn $requestContext $summary missingFields=${missingFields.joinToString()}",
            )
        }
        Log.i(TAG, "fetchObjectMessage requestSuccess $requestContext $summary")
    }

    private fun buildRequestContextLog(
        requestId: String,
        normalizedBaseUrl: String,
        objectId: String,
        authCode: String,
    ): String {
        return "requestId=$requestId baseUrl=$normalizedBaseUrl objectId=$objectId authCode=$authCode"
    }

    private fun buildMaskedRequestBodyLog(
        maskedAuthCode: String,
        maskedObjectId: String,
    ): String {
        return """{"authCode":"$maskedAuthCode","objectId":"$maskedObjectId"}"""
    }

    private fun normalizeBaseUrlForLog(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    private fun maskTokenForLog(value: String?): String {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return "(blank)"
        }
        if (normalized.length <= TOKEN_LOG_VISIBLE_SUFFIX_LENGTH) {
            return "***$normalized"
        }
        return "***${normalized.takeLast(TOKEN_LOG_VISIBLE_SUFFIX_LENGTH)}"
    }

    private fun elapsedSince(startedAtElapsedMs: Long): Long {
        return SystemClock.elapsedRealtime() - startedAtElapsedMs
    }

    companion object {
        private const val TAG = "EnterpriseObjectApi"
        private const val DEFAULT_FAILURE_MESSAGE = "对象信息获取失败，请重试"
        private const val TOKEN_LOG_VISIBLE_SUFFIX_LENGTH = 6
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
