package com.rokid.glass.hiddenrisk

import android.util.Base64
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.network.HttpClientProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class AlignmentAutoDetectionClient(
    private val endpoint: String = InspectionConfigRepository.get().network.aiAutoApi.url,
) {
    private val client = HttpClientProvider.inspectionClient.newBuilder()
        .callTimeout(AlignmentDetectionCadence.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun detect(jpegBytes: ByteArray, callback: ResultCallback): Call {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val request = Request.Builder()
            .url(endpoint)
            .post(AlignmentDetectionProtocol.buildRequestJson(base64Image).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            callback.onFailure("HTTP ${response.code} ${response.message}")
                            return
                        }
                        val body = response.body?.string().orEmpty()
                        runCatching { AlignmentDetectionProtocol.parseResponse(body) }
                            .onSuccess(callback::onSuccess)
                            .onFailure { callback.onFailure(it.message ?: "自动隐患响应解析失败") }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    callback.onFailure(e.message ?: "自动隐患请求失败")
                }
            })
        }
    }

    interface ResultCallback {
        fun onSuccess(response: AlignmentDetectionResponse)
        fun onFailure(message: String)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
