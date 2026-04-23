package com.rokid.glass.hiddenrisk

import com.rokid.glass.camera.RokidFrameSource

/**
 * 巡检会话管理单例。
 * 负责跨 Activity 共享初始化状态：NCNN 模型实例和 SDK 帧流状态。
 */
object InspectionSession {
    // NCNN 模型实例
    var hiddenRiskNcnn: HiddenRiskNcnn? = null
        private set

    // SDK 帧流是否已就绪
    var isFrameStreamReady: Boolean = false
        private set

    // 初始化是否完成
    var isInitialized: Boolean = false
        private set

    // 初始化错误信息（如果有）
    var errorMessage: String? = null
        private set

    // 目标输入尺寸
    val targetInputSize = 640
    val backendGpu = 1
    val gpuProfile = 1
    /**
     * 创建 NCNN 实例（不加载模型）
     */
    fun createNcnnInstance(): Boolean {
        return try {
            hiddenRiskNcnn = HiddenRiskNcnn()
            true
        } catch (e: Exception) {
            errorMessage = "NCNN 初始化失败: ${e.message}"
            false
        }
    }

    /**
     * 加载模型
     */
    fun loadModel(assets: android.content.res.AssetManager): Boolean {
        val ncnn = hiddenRiskNcnn ?: return false
        return try {
            ncnn.setDebugCompareEnabled(false)
            ncnn.loadModel(
                assets,
                backendGpu,
                gpuProfile,
                targetInputSize
            )
        } catch (e: Exception) {
            errorMessage = "模型加载失败: ${e.message}"
            false
        }
    }

    /**
     * 初始化 SDK NV21 帧流
     */
    fun initFrameStream(callback: (Boolean) -> Unit) {
        if (isFrameStreamReady && RokidFrameSource.isFrameStreamWarm()) {
            callback(true)
            return
        }
        RokidFrameSource.startFrameStream { success ->
            isFrameStreamReady = success
            if (!success) {
                errorMessage = "相机帧流初始化失败"
            }
            callback(success)
        }
    }

    /**
     * 停止 inspection 相关的 SDK 帧源，占用相机的外部页面进入前调用。
     */
    fun stopFrameStream() {
        RokidFrameSource.releaseAll()
        isFrameStreamReady = false
    }

    /**
     * 标记初始化完成
     */
    fun markInitialized() {
        isInitialized = true
    }

    /**
     * 标记初始化错误
     */
    fun markError(message: String) {
        errorMessage = message
    }

    /**
     * 重置会话（用于重新初始化）
     */
    fun reset() {
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        stopFrameStream()
        isInitialized = false
        errorMessage = null
    }

    /**
     * 清理资源（页面销毁时调用）
     */
    fun release() {
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        stopFrameStream()
        isInitialized = false
        errorMessage = null
    }
}
