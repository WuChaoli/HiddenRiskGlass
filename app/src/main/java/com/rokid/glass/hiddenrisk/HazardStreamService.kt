package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

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

    /**
     * 提交图片并开始流式获取分析结果。
     * @param bitmap 拍摄的图片（当前模拟未使用，真实接口需要传给后端）
     * @param callback 流式回调，在主线程调用
     */
    fun analyze(bitmap: Bitmap?, callback: StreamCallback) {
        // TODO: 替换为真实接口调用
        simulateStream(callback)
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
        // TODO: 替换为真实后端接口
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            callback.onSuccess()
        }, 500L)
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
