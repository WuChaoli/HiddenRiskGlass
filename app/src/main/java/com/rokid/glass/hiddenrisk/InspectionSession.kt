package com.rokid.glass.hiddenrisk

import android.util.Size
import com.rokid.glass.camera.QuickCameraManager

/**
 * 巡检会话管理单例。
 * 负责跨 Activity 共享初始化状态：NCNN 模型实例和相机状态。
 */
object InspectionSession {
    private val quickCaptureSize = Size(640, 640)

    // NCNN 模型实例
    var hiddenRiskNcnn: HiddenRiskNcnn? = null
        private set

    // 相机是否已就绪
    var isCameraReady: Boolean = false
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
     * 初始化相机
     */
    fun initCamera(callback: (Boolean) -> Unit) {
        if (isCameraReady && !QuickCameraManager.isGpuCaptureWarm()) {
            isCameraReady = false
        }
        if (isCameraReady) {
            callback(true)
            return
        }
        QuickCameraManager.initialize(
            size = quickCaptureSize,
            quickCapture = true,
        ) { success ->
            isCameraReady = success
            if (!success) {
                errorMessage = "相机初始化失败"
            }
            callback(success)
        }
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
        QuickCameraManager.releaseCamera()
        isCameraReady = false
        isInitialized = false
        errorMessage = null
    }

    /**
     * 清理资源（页面销毁时调用）
     */
    fun release() {
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        QuickCameraManager.releaseCamera()
        isCameraReady = false
        isInitialized = false
        errorMessage = null
    }
}
