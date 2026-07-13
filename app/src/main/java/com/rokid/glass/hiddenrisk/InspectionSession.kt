package com.rokid.glass.hiddenrisk

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner

/**
 * 巡检会话管理单例。
 * 负责在当前 App 进程内缓存 HiddenRisk 模型，并独立管理 inspection 使用的相机帧流状态。
 */
object InspectionSession {
    // 当前 App 进程内复用的 HiddenRisk NCNN 模型实例
    @Volatile
    var hiddenRiskNcnn: HiddenRiskNcnn? = null
        private set

    // SDK 帧流是否已就绪
    var isFrameStreamReady: Boolean = false
        private set

    // NCNN 模型是否已完成加载
    @Volatile
    var isModelLoaded: Boolean = false
        private set

    // 初始化是否完成
    @Volatile
    var isInitialized: Boolean = false
        private set

    // 初始化错误信息（如果有）
    @Volatile
    var errorMessage: String? = null
        private set

    // 目标输入尺寸
    val targetInputSize = 640
    val backendGpu = 1
    val gpuProfile = 1
    internal interface CoordinatorGateway {
        fun ensureLoaded(assets: Any, callback: (LocalInferenceCoordinator.OperationResult) -> Unit)

        fun detect(
            assets: Any,
            bitmap: Bitmap,
            traceLabel: String = "",
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        )

        fun executeWithLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.NativeEngine?, LocalInferenceCoordinator.OperationResult) -> Unit,
        )

        fun release(callback: (LocalInferenceCoordinator.OperationResult) -> Unit = {})
    }

    private val productionCoordinator = NativeCoordinatorGateway(
        LocalInferenceCoordinator(
            executor = LocalInferenceCoordinator.executor(),
            engineFactory = { SessionNativeEngine() },
        ),
    )

    @Volatile
    private var coordinator: CoordinatorGateway = productionCoordinator

    /** 在进程级执行序列中确保模型已完成加载。 */
    fun ensureModelLoaded(assets: Any, callback: (Boolean) -> Unit) {
        coordinator.ensureLoaded(assets) { result ->
            isModelLoaded = result.success
            errorMessage = result.errorMessage.ifBlank { null }
            callback(result.success)
        }
    }

    /** 在与模型加载、释放相同的执行序列中完成一次 Bitmap 推理。 */
    internal fun detectLocal(
        assets: AssetManager,
        bitmap: Bitmap,
        traceLabel: String = "",
        callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
    ) {
        coordinator.detect(assets, bitmap, traceLabel, callback)
    }

    internal fun submitNv21Local(
        assets: AssetManager,
        nv21: ByteArray,
        width: Int,
        height: Int,
        callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
    ) {
        coordinator.executeWithLoaded(assets) { engine, loadResult ->
            if (!loadResult.success || engine !is SessionNativeEngine) {
                callback(
                    LocalInferenceCoordinator.DetectionOutcome(
                        success = false,
                        stats = null,
                        errorMessage = loadResult.errorMessage,
                    ),
                )
                return@executeWithLoaded
            }
            val success = engine.submitNv21(nv21, width, height)
            val stats = engine.latestStats()
            callback(
                LocalInferenceCoordinator.DetectionOutcome(
                    success = success,
                    stats = stats,
                    errorMessage = if (success) "" else engine.errorMessage().orEmpty(),
                ),
            )
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
    fun reset(callback: () -> Unit = {}) {
        stopFrameStream()
        isInitialized = false
        errorMessage = null
        coordinator.release {
            isModelLoaded = false
            hiddenRiskNcnn = null
            callback()
        }
    }

    /**
     * 显式彻底释放当前进程内缓存的模型与帧流。
     * 委托给 reset() 以消除重复代码。
     */
    fun release(callback: () -> Unit = {}) = reset(callback)

    internal fun installCoordinatorForTest(testCoordinator: CoordinatorGateway) {
        coordinator = testCoordinator
    }

    internal fun restoreCoordinatorForTest() {
        coordinator = productionCoordinator
        isModelLoaded = false
        isInitialized = false
        errorMessage = null
        hiddenRiskNcnn = null
    }

    private class NativeCoordinatorGateway(
        private val delegate: LocalInferenceCoordinator,
    ) : CoordinatorGateway {
        override fun ensureLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.OperationResult) -> Unit,
        ) {
            delegate.ensureLoaded(assets, callback)
        }

        override fun detect(
            assets: Any,
            bitmap: Bitmap,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            delegate.detect(assets, bitmap, traceLabel, callback)
        }

        override fun executeWithLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.NativeEngine?, LocalInferenceCoordinator.OperationResult) -> Unit,
        ) {
            delegate.executeWithLoaded(assets, callback)
        }

        override fun release(callback: (LocalInferenceCoordinator.OperationResult) -> Unit) {
            delegate.release(callback)
        }
    }

    /** 协调器线程独占的 JNI 适配器。 */
    private class SessionNativeEngine : LocalInferenceCoordinator.NativeEngine {
        override fun load(assets: Any): Boolean {
            val ncnn = hiddenRiskNcnn ?: try {
                HiddenRiskNcnn().also { hiddenRiskNcnn = it }
            } catch (e: Exception) {
                errorMessage = "NCNN 初始化失败: ${e.message}"
                return false
            }

            return try {
                ncnn.setDebugCompareEnabled(false)
                val loaded = ncnn.loadModel(
                    assets as AssetManager,
                    backendGpu,
                    gpuProfile,
                    targetInputSize,
                )
                isModelLoaded = loaded
                errorMessage = if (loaded) {
                    null
                } else {
                    ncnn.getLatestInferenceStats()?.errorMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "本地模型加载失败"
                }
                loaded
            } catch (e: Exception) {
                isModelLoaded = false
                errorMessage = "模型加载失败: ${e.message}"
                false
            }
        }

        override fun detect(bitmap: Any): Boolean {
            return hiddenRiskNcnn?.submitBitmap(bitmap as Bitmap) ?: false
        }

        override fun latestStats(): NativeInferenceStats? {
            return hiddenRiskNcnn?.getLatestInferenceStats()
        }

        fun submitNv21(nv21: ByteArray, width: Int, height: Int): Boolean {
            return hiddenRiskNcnn?.submitNv21(nv21, width, height) ?: false
        }

        override fun release() {
            hiddenRiskNcnn?.releaseModel()
            hiddenRiskNcnn = null
            isModelLoaded = false
        }

        override fun errorMessage(): String? = InspectionSession.errorMessage
    }

    private const val TAG = "InspectionSession"
}
