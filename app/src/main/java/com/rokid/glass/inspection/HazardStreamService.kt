package com.rokid.glass.inspection

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
 * 隐患分析流式接口。
 * 当前使用模拟数据，后续替换为真实后端接口。
 *
 * 真实接口预期：
 * - POST 图片到后端
 * - 后端返回 SSE/流式 JSON，逐段返回隐患描述和整改方案
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
     * 提交图片并开始流式获取分析结果。
     * @param bitmap 拍摄的图片（当前模拟未使用，真实接口需要传给后端）
     * @param callback 流式回调，在主线程调用
     */
    fun analyze(bitmap: Bitmap?, callback: StreamCallback) {
        // 在 Activity 或其他地方使用

        sseUtil.connect(
            imageUrl = "base64_encoded_image_string",
            snCode = "1901092534052934",
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
     * 将隐患记录同步到后端/手机端。
     * @param analysisText 流式分析的完整文本
     * @param callback 同步结果回调，在主线程调用
     */
    fun syncToPhone(analysisText: String, callback: SyncCallback) {
        // 创建 HttpUtils 实例
        val httpUtils = HttpUtils()
// 调用上报接口
        httpUtils.reportSaveResult(
            snCode = "1901092534052934",
            authorization = "eyJhbGciOiJIUzI1NiIsInppcCI6IkdaSVAifQ.H4sIAAAAAAAAAFWMMQqAMAxF75K5HVIb03ibtLaggwhWEMS7G3DyDW_48P4Nx5lhAhRJBhvgYNEOEzJTSMQSHGy5_Ye1L1a1VLUhiY9jZlMir_MgXrGRRipcdLS7eu1fPXBACc8LKuQZRnUAAAA.ExvZFAtVR-0XMoheQ0UKoLAV5liwtnZI4Wbk_O7bEs0",
            isSave = "1", // 1-保存，0-不保存
            sessionId = AiInspectionActivity.sessionId,
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

    private fun simulateStream(callback: StreamCallback) {
        val handler = Handler(Looper.getMainLooper())
        val chunks = listOf(
            "【隐患描述】\n",
            "经AI识别，当前区域存在以下安全隐患：\n\n",
            "1. 液化石油气瓶与燃气灶之间的连接软管老化，",
            "存在破损风险，可能导致燃气泄漏。\n\n",
            "2. 气瓶调压阀未处于正常工作位置，",
            "阀门未完全关闭。\n\n",
            "【整改方案】\n",
            "1. 立即更换老化连接软管，",
            "使用符合国家标准的不锈钢波纹管。\n",
            "2. 调整气瓶调压阀至正确位置，",
            "确认阀门开关状态正常。\n",
            "3. 对周边区域进行燃气泄漏检测，",
            "确保安全后方可继续作业。",
        )

        val fullText = StringBuilder()
        chunks.forEachIndexed { index, chunk ->
            handler.postDelayed({
                fullText.append(chunk)
                callback.onChunk(fullText.toString())
                if (index == chunks.size - 1) {
                    callback.onComplete(fullText.toString())
                }
            }, (index + 1) * 200L)
        }
    }
}
