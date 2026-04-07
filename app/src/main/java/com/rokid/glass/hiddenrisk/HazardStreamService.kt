package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.blankj.utilcode.util.ThreadUtils.runOnUiThread
import com.google.gson.Gson
import com.rokid.glass.data.YXData
import com.rokid.glass.utils.HttpUtils
import com.rokid.glass.utils.SSEUtil
import okhttp3.Response
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * 隐患分析流式接口服务
 * 使用 SSE 进行流式数据传输，使用 HttpUtils 进行上报保存结果
 */
object HazardStreamService {

    interface StreamCallback {
        /** 收到一段流式文本 */
        fun onChunk(text: String)

        /** 流式传输完成 */
        fun onComplete(fullText: String)

        /** 出错 */
        fun onError(message: String)
    }

    val sseUtil = SSEUtil()
    val gson = Gson()

    /**
     * 提交图片并开始流式获取分析结果
     * @param bitmap 拍摄的图片
     * @param callback 流式回调，在主线程调用
     */
    fun analyze(bitmap: Bitmap?, callback: StreamCallback) {
        // 在 Activity 或其他地方使用
        sseUtil.connect(
            imageUrl = "base64_encoded_image_string",
            snCode = RokidSdkManager.getSerialNumber(),
            sessionId = System.currentTimeMillis().toString(),
            authorization = "eyJhbGciOiJIUzI1NiIsInppcCI6IkdaSVAifQ.H4sIAAAAAAAAAFWMMQqAMAxF75K5HVIb03ibtLaggwhWEMS7G3DyDW_48P4Nx5lhAhRJBhvgYNEOEzJTSMQSHGy5_Ye1L1a1VLUhiY9jZlMir_MgXrGRRipcdLS7eu1fPXBACc8LKuQZRnUAAAA.ExvZFAtVR-0XMoheQ0UKoLAV5liwtnZI4Wbk_O7bEs0",
            timestamp = System.currentTimeMillis().toString(),
            listener = object : SSEUtil.SSEListener {
                override fun onOpened() {
                    // 连接成功
                    runOnUiThread {
                    }
                }

                override fun onMessage(data: String) {
                    // 收到消息
                    runOnUiThread {
                        val data = gson.fromJson<YXData>(data, YXData::class.java)
                        if (data.end == 0) {
                            callback.onChunk(data.answer)
                        } else {
                            callback.onComplete(data.answer)
                        }
                    }
                }

                override fun onClosed() {
                    // 连接关闭
                    runOnUiThread {
                    }
                }

                override fun onFailure(t: Throwable?, response: Response?) {
                    // 连接失败
                    runOnUiThread {
                    }
                }

                override fun onEventSourceCreated(eventSource: EventSource) {
                    // 保存引用以便手动关闭
                    // this.currentEventSource = eventSource
                }
            }
        )
    }

    interface SyncCallback {
        fun onSuccess()
        fun onError(message: String)
    }

    /**
     * 将隐患记录同步到后端/手机端
     * @param analysisText 流式分析的完整文本
     * @param sessionId 本次拍照上传时生成的会话 ID，用于定位后端对应图片
     * @param callback 同步结果回调，在主线程调用
     */
    fun syncToPhone(analysisText: String, sessionId: String, callback: SyncCallback) {
        val httpUtils = HttpUtils()
        httpUtils.reportSaveResult(
            snCode = RokidSdkManager.getSerialNumber(),
            authorization = "eyJhbGciOiJIUzI1NiIsInppcCI6IkdaSVAifQ.H4sIAAAAAAAAAFWMMQqAMAxF75K5HVIb03ibtLaggwhWEMS7G3DyDW_48P4Nx5lhAhRJBhvgYNEOEzJTSMQSHGy5_Ye1L1a1VLUhiY9jZlMir_MgXrGRRipcdLS7eu1fPXBACc8LKuQZRnUAAAA.ExvZFAtVR-0XMoheQ0UKoLAV5liwtnZI4Wbk_O7bEs0",
            isSave = "1", // 1-保存，0-不保存
            sessionId = sessionId,
            callback = object : HttpUtils.SaveResultCallback {
                override fun onSuccess(response: HttpUtils.ApiResponse) {
                    runOnUiThread {
                        Log.d("SaveResult", "上报成功: code=${response.code}, msg=${response.msg}")
                        // 处理成功逻辑
                        if (response.code == 200) {
                            // 上报成功
                            callback.onSuccess()
                        } else {
                            // 业务逻辑错误
                            Log.e("SaveResult", "业务错误: ${response.msg}")
                        }
                    }
                }

                override fun onFailure(e: Exception) {
                    runOnUiThread {
                        Log.e("SaveResult", "上报失败", e)
                        // 处理失败逻辑
                        // 即使上报失败，也可以继续流程
                        callback.onSuccess()
                    }
                }
            }
        )
    }
}
