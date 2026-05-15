package com.rokid.glass.hiddenrisk

import android.util.Log
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner

/**
 * 巡检会话管理单例。
 * 负责在当前 App 进程内缓存 HiddenRisk 模型，并独立管理 inspection 使用的相机帧流状态。
 */
object InspectionSession {
    // 当前 App 进程内复用的 HiddenRisk NCNN 模型实例
    var hiddenRiskNcnn: HiddenRiskNcnn? = null
        private set

    // SDK 帧流是否已就绪
    var isFrameStreamReady: Boolean = false
        private set

    // NCNN 模型是否已完成加载
    var isModelLoaded: Boolean = false
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
     * 创建 NCNN 实例（不加载模型）。
     * 若当前进程中已有缓存实例，则直接复用，避免重复创建 native 对象。
     */
    fun createNcnnInstance(): Boolean {
        hiddenRiskNcnn?.let {
            errorMessage = null
            return true
        }
        return try {
            hiddenRiskNcnn = HiddenRiskNcnn()
            errorMessage = null
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
            isModelLoaded = ncnn.loadModel(
                assets,
                backendGpu,
                gpuProfile,
                targetInputSize
            )
            errorMessage = null
            isModelLoaded
        } catch (e: Exception) {
            isModelLoaded = false
            errorMessage = "模型加载失败: ${e.message}"
            false
        }
    }

    /**
     * 初始化 SDK NV21 帧流
     */
    fun initFrameStream(callback: (Boolean) -> Unit) {
        Log.i(
            TAG,
            "initFrameStream start initialized=$isInitialized frameReady=$isFrameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
        )
        if (InspectionCameraCoordinator.isFrameStreamReady() && RokidFrameSource.isFrameStreamWarm()) {
            isFrameStreamReady = true
            callback(true)
            return
        }
        InspectionCameraCoordinator.acquire(
            owner = CameraOwner.LOADING,
            needPreview = false,
        ) { success ->
            isFrameStreamReady = success
            if (!success) {
                errorMessage = "相机帧流初始化失败"
            }
            Log.i(
                TAG,
                "initFrameStream end success=$success frameReady=$isFrameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} error=$errorMessage",
            )
            callback(success)
        }
    }

    /**
     * 停止 inspection 相关的 SDK 帧源，占用相机的外部页面进入前调用。
     */
    fun stopFrameStream() {
        Log.i(
            TAG,
            "stopFrameStream start initialized=$isInitialized frameReady=$isFrameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
        )
        InspectionCameraCoordinator.releaseAppCamera(reason = "compat_stop_frame_stream")
        isFrameStreamReady = false
        Log.i(
            TAG,
            "stopFrameStream end initialized=$isInitialized frameReady=$isFrameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
        )
    }

    /**
     * 标记初始化完成
     */
    fun markInitialized() {
        isInitialized = true
        errorMessage = null
    }

    /**
     * 标记初始化错误
     */
    fun markError(message: String) {
        errorMessage = message
    }

    /**
     * 重置会话（用于初始化失败后的重试）。
     * 会彻底释放模型缓存，并清理 inspection 相机帧流。
     */
    fun reset() {
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        isModelLoaded = false
        stopFrameStream()
        isInitialized = false
        errorMessage = null
    }

    /**
     * 显式彻底释放当前进程内缓存的模型与帧流。
     * 仅在初始化失败、主动重试等明确需要完全释放时调用。
     */
    fun release() {
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        isModelLoaded = false
        stopFrameStream()
        isInitialized = false
        errorMessage = null
    }

    private const val TAG = "InspectionSession"
}
