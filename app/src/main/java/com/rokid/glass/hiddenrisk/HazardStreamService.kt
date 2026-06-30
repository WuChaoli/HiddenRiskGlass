package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import com.blankj.utilcode.util.ThreadUtils.runOnUiThread
import com.google.gson.Gson
import com.rokid.glass.data.YXData
import com.rokid.glass.utils.SSEUtil
import okhttp3.Response
import okhttp3.sse.EventSource

/**
 * 隐患分析流式接口服务
 * 使用 SSE 进行流式数据传输。
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

}
