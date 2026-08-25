package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.rokid.glass.config.AiArApiConfig
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.network.HttpClientProvider
import okhttp3.Call
import okhttp3.Callback as OkHttpCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface DeepV2ClientError {
    data class Http(val statusCode: Int) : DeepV2ClientError
    data class Network(val cause: IOException) : DeepV2ClientError
    data class Protocol(val cause: DeepV2ProtocolException) : DeepV2ClientError
}

internal class DeepV2Client(
    private val apiConfig: AiArApiConfig,
    httpClient: OkHttpClient,
    private val taskIdFactory: () -> String,
    private val base64Encoder: (ByteArray) -> String,
    private val callbackExecutor: Executor,
) {
    private val client = httpClient.newBuilder()
        .connectTimeout(apiConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(apiConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(apiConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(apiConfig.detectTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    interface Callback {
        fun onSuccess(requestId: Long, response: DeepV2Response)
        fun onFailure(requestId: Long, error: DeepV2ClientError)
    }

    interface RequestHandle {
        fun cancel()
    }

    fun request(
        requestId: Long,
        imageBytes: ByteArray,
        scene: String,
        callback: Callback,
    ): RequestHandle {
        val cancelled = AtomicBoolean(false)
        val requestBody = DeepV2Protocol.buildRequestJson(
            DeepV2Request(
                taskId = taskIdFactory(),
                scene = scene,
                temp = DEFAULT_TEMPERATURE,
                image = base64Encoder(imageBytes),
            ),
        ).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(apiConfig.url)
            .post(requestBody)
            .build()
        val call = client.newCall(request)
        call.enqueue(object : OkHttpCallback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (cancelled.get()) return
                    if (!response.isSuccessful) {
                        deliver(cancelled) {
                            callback.onFailure(requestId, DeepV2ClientError.Http(response.code))
                        }
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    try {
                        val parsed = DeepV2Protocol.parseResponse(body)
                        deliver(cancelled) { callback.onSuccess(requestId, parsed) }
                    } catch (error: DeepV2ProtocolException) {
                        deliver(cancelled) {
                            callback.onFailure(requestId, DeepV2ClientError.Protocol(error))
                        }
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                deliver(cancelled) {
                    callback.onFailure(requestId, DeepV2ClientError.Network(e))
                }
            }
        })
        return object : RequestHandle {
            override fun cancel() {
                cancelled.set(true)
                call.cancel()
            }
        }
    }

    private fun deliver(cancelled: AtomicBoolean, block: () -> Unit) {
        if (cancelled.get()) return
        callbackExecutor.execute {
            if (!cancelled.get()) block()
        }
    }

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun create(): DeepV2Client {
            val mainHandler = Handler(Looper.getMainLooper())
            return DeepV2Client(
                apiConfig = InspectionConfigRepository.get().network.aiDeepV2Api,
                httpClient = HttpClientProvider.inspectionClient,
                taskIdFactory = { UUID.randomUUID().toString() },
                base64Encoder = { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) },
                callbackExecutor = Executor { command -> mainHandler.post(command) },
            )
        }
    }
}
