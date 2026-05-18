package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.config.MayHazardVerifyApiConfig
import com.rokid.glass.network.HttpClientProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private const val TAG = "MayHazardVerify"

/**
 * MayHazard 深度识别服务。
 */
class MayHazardDeepVerifyService(
    private val apiConfig: MayHazardVerifyApiConfig =
        InspectionConfigRepository.get().network.mayHazardVerifyApi,
) {
    data class VerifyMetrics(
        val answerMs: Long = -1L,
        val httpTotalMs: Long = -1L,
    )

    interface VerifyCallback {
        fun onSuccess(hasHazard: Boolean, metrics: VerifyMetrics)
        fun onFailure(message: String, metrics: VerifyMetrics)
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

    private val client = HttpClientProvider.inspectionClient
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun verify(base64Image: String, callback: VerifyCallback): RequestHandle {
        val verifyStartMs = SystemClock.elapsedRealtime()
        val handle = RequestHandle()
        val requestBody = gson.toJson(MayHazardDeepVerifyProtocol.AnswerRequest(image_url = base64Image))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(MayHazardDeepVerifyProtocol.ANSWER_URL)
            .post(requestBody)
            .build()
        val answerCallStartMs = SystemClock.elapsedRealtime()
        val call = client.newCall(request)
        handle.bind(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (handle.isCanceled()) return
                val answerMs = SystemClock.elapsedRealtime() - answerCallStartMs
                deliverFailure(
                    handle,
                    callback,
                    e.message ?: "has_hazard_answer 请求失败",
                    VerifyMetrics(
                        answerMs = answerMs,
                        httpTotalMs = SystemClock.elapsedRealtime() - verifyStartMs,
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (handle.isCanceled()) return
                    val answerMs = SystemClock.elapsedRealtime() - answerCallStartMs
                    val totalMs = SystemClock.elapsedRealtime() - verifyStartMs
                    if (!response.isSuccessful) {
                        deliverFailure(
                            handle,
                            callback,
                            "has_hazard_answer HTTP ${response.code}",
                            VerifyMetrics(
                                answerMs = answerMs,
                                httpTotalMs = totalMs,
                            )
                        )
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    Log.i(TAG, "answer raw body=$body")
                    val hasHazard = runCatching {
                        MayHazardDeepVerifyProtocol.parseHasHazardAnswer(body)
                    }.getOrElse { error ->
                        deliverFailure(
                            handle,
                            callback,
                            error.message ?: "has_hazard_answer 响应非法",
                            VerifyMetrics(
                                answerMs = answerMs,
                                httpTotalMs = totalMs,
                            )
                        )
                        return
                    }
                    Log.i(TAG, "answer done ms=$answerMs httpTotalMs=$totalMs")
                    mainHandler.post {
                        if (handle.isCanceled()) return@post
                        callback.onSuccess(
                            hasHazard,
                            VerifyMetrics(
                                answerMs = answerMs,
                                httpTotalMs = totalMs,
                            )
                        )
                    }
                }
            }
        })
        return handle
    }

    private fun deliverFailure(
        handle: RequestHandle,
        callback: VerifyCallback,
        message: String,
        metrics: VerifyMetrics,
    ) {
        Log.w(TAG, "$message answerMs=${metrics.answerMs} httpTotalMs=${metrics.httpTotalMs}")
        mainHandler.post {
            if (handle.isCanceled()) return@post
            callback.onFailure(message, metrics)
        }
    }
}
