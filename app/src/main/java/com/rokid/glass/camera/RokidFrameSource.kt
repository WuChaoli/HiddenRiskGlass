package com.rokid.glass.camera

import android.graphics.Rect
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.utils.AppFileLogger
import com.rokid.glass.utils.BitmapUtils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig

/**
 * 基于 Rokid CameraShareHelper 的统一 NV21 帧源。
 * 只保留最近一帧原始 NV21，业务侧按需裁切，避免每帧预处理和排队。
 */
object RokidFrameSource {

    internal data class NormalizedCropRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    internal data class SurfaceTextureCropMapping(
        val mode: String,
        val textureCrop: NormalizedCropRect,
    )

    data class Nv21Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
    )

    data class CroppedNv21Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
    )

    data class SquareNv21Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
    )

    private const val TAG = "RokidFrameSource"
    private const val CROPPED_TARGET_SIZE = 640
    private const val FRAME_STREAM_RESTART_RELEASE_DELAY_MS = 500L
    private const val SHARED_PREVIEW_WIDTH = 1920
    private const val SHARED_PREVIEW_HEIGHT = 1080
    private const val SHARED_PREVIEW_TARGET_FPS = 15

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val frameReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

    private var nv21Helper: CameraShareHelper? = null
    private var helperGenerationCounter = 0L
    private var activeHelperGeneration = 0L

    @Volatile
    private var frameStreamOpened = false

    @Volatile
    private var frameSize: Size? = null

    @Volatile
    private var surfacePreviewSize: Size? = null

    @Volatile
    private var surfaceAppliedPreviewFps: Int? = null

    @Volatile
    private var surfaceVideoStabilizationEnabled: Boolean? = null

    @Volatile
    private var surfaceZoomLevel: Int? = null

    @Volatile
    private var nv21ExportAppliedPreviewFps: Int? = null

    @Volatile
    private var nv21ExportVideoStabilizationEnabled: Boolean? = null

    @Volatile
    private var nv21ExportZoomLevel: Int? = null

    @Volatile
    private var latestFrame: Nv21Frame? = null
    private var latestFrameBuffer: ByteArray? = null

    @Volatile
    private var currentZoomRatio = 1.0f

    fun startFrameStream(onReady: (Boolean) -> Unit = {}) {
        synchronized(lock) {
            if (!GlassSdk.isReady()) {
                AppFileLogger.w(TAG, "startFrameStream skipped sdkNotReady")
                mainHandler.post { onReady(false) }
                return
            }
            syncCachedZoomRatio()
            if (nv21Helper != null) {
                AppFileLogger.i(TAG, "startFrameStream reuse helper opened=$frameStreamOpened")
                if (frameStreamOpened) {
                    syncCachedZoomRatio()
                    mainHandler.post { onReady(true) }
                } else {
                    frameReadyCallbacks += onReady
                }
                return
            }
            AppFileLogger.i(TAG, "startFrameStream create helper")
            frameReadyCallbacks += onReady
            latestFrame = null
            val helperGeneration = ++helperGenerationCounter
            activeHelperGeneration = helperGeneration
            nv21Helper = CameraShareHelper().apply {
                initNv21ExportWithConfig(
                    false,
                    sharedCameraConfig(),
                    object : CameraShareHelper.Nv21Callback {
                        override fun onCameraOpened(width: Int, height: Int) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                AppFileLogger.i(
                                    TAG,
                                    "ignore stale onCameraOpened callbackGeneration=$helperGeneration activeGeneration=$activeHelperGeneration",
                                )
                                return
                            }
                            frameStreamOpened = true
                            frameSize = Size(width, height)
                            nv21ExportAppliedPreviewFps = null
                            nv21ExportVideoStabilizationEnabled = null
                            syncCachedZoomRatio()
                            notifyFrameReady(true)
                        }

                        override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                return
                            }
                            synchronized(lock) {
                                val buffer = latestFrameBuffer
                                    ?.takeIf { it.size == nv21.size }
                                    ?: ByteArray(nv21.size).also { latestFrameBuffer = it }
                                System.arraycopy(nv21, 0, buffer, 0, nv21.size)
                                latestFrame = Nv21Frame(
                                    data = buffer,
                                    width = width,
                                    height = height,
                                    timestamp = timestamp,
                                    receivedAtElapsedMs = SystemClock.elapsedRealtime(),
                                )
                            }
                        }

                        override fun onCameraClosed() {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                AppFileLogger.i(
                                    TAG,
                                    "ignore stale onCameraClosed callbackGeneration=$helperGeneration activeGeneration=$activeHelperGeneration",
                                )
                                return
                            }
                            synchronized(lock) {
                                frameStreamOpened = false
                                frameSize = null
                                latestFrame = null
                                latestFrameBuffer = null
                                nv21Helper = null
                                activeHelperGeneration = 0L
                            }
                        }

                        override fun onError(code: Int, msg: String) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                AppFileLogger.i(
                                    TAG,
                                    "ignore stale onError callbackGeneration=$helperGeneration activeGeneration=$activeHelperGeneration code=$code",
                                )
                                return
                            }
                            AppFileLogger.e(TAG, "frame stream error code=$code msg=$msg")
                            synchronized(lock) {
                                frameStreamOpened = false
                                frameSize = null
                                surfacePreviewSize = null
                                surfaceAppliedPreviewFps = null
                                surfaceVideoStabilizationEnabled = null
                                surfaceZoomLevel = null
                                nv21ExportAppliedPreviewFps = null
                                nv21ExportVideoStabilizationEnabled = null
                                nv21ExportZoomLevel = null
                                latestFrame = null
                                latestFrameBuffer = null
                                nv21Helper = null
                                activeHelperGeneration = 0L
                            }
                            notifyFrameReady(false)
                        }

                        override fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                return
                            }
                            frameSize = Size(width, height)
                            nv21ExportAppliedPreviewFps = appliedPreviewFps
                            AppFileLogger.i(
                                TAG,
                                "nv21 export resolution changed width=$width height=$height appliedPreviewFps=$appliedPreviewFps",
                            )
                        }

                        override fun onNv21ExportRuntimeParamsChanged(
                            appliedPreviewFps: Int,
                            videoStabilizationEnabled: Boolean,
                        ) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                return
                            }
                            nv21ExportAppliedPreviewFps = appliedPreviewFps
                            nv21ExportVideoStabilizationEnabled = videoStabilizationEnabled
                            AppFileLogger.i(
                                TAG,
                                "nv21 export params changed appliedPreviewFps=$appliedPreviewFps videoStabilizationEnabled=$videoStabilizationEnabled",
                            )
                        }

                        override fun onZoomLevelChanged(zoomLevel: Int) {
                            if (isHelperCallbackStale(activeHelperGeneration, helperGeneration)) {
                                return
                            }
                            nv21ExportZoomLevel = zoomLevel
                            AppFileLogger.i(TAG, "nv21 export zoom changed zoomLevel=$zoomLevel")
                        }
                    },
                )
            }
        }
    }

    fun stopFrameStream() {
        val helper = synchronized(lock) {
            AppFileLogger.i(TAG, "stopFrameStream helperExists=${nv21Helper != null}")
            frameReadyCallbacks.clear()
            frameStreamOpened = false
            frameSize = null
            surfacePreviewSize = null
            surfaceAppliedPreviewFps = null
            surfaceVideoStabilizationEnabled = null
            surfaceZoomLevel = null
            nv21ExportAppliedPreviewFps = null
            nv21ExportVideoStabilizationEnabled = null
            nv21ExportZoomLevel = null
            latestFrame = null
            latestFrameBuffer = null
            activeHelperGeneration = 0L
            nv21Helper.also { nv21Helper = null }
        }
        helper?.releaseNv21Export()
    }

    fun restartFrameStream(
        releaseDelayMs: Long = FRAME_STREAM_RESTART_RELEASE_DELAY_MS,
        onReady: (Boolean) -> Unit = {},
    ) {
        AppFileLogger.i(TAG, "restartFrameStream begin releaseDelayMs=$releaseDelayMs")
        stopFrameStream()
        mainHandler.postDelayed(
            {
                AppFileLogger.i(TAG, "restartFrameStream relaunch")
                startFrameStream { success ->
                    AppFileLogger.i(TAG, "restartFrameStream finished success=$success")
                    onReady(success)
                }
            },
            releaseDelayMs.coerceAtLeast(0L),
        )
    }

    fun releaseAll() {
        stopFrameStream()
    }

    fun isFrameStreamOpen(): Boolean = frameStreamOpened

    fun isFrameStreamWarm(): Boolean = frameStreamOpened && latestFrame != null

    fun isCroppedFrameStreamWarm(): Boolean = isFrameStreamWarm()

    fun copyLatestRawFrame(): Nv21Frame? {
        return synchronized(lock) {
            val frame = latestFrame ?: return@synchronized null
            frame.copy(data = frame.data.copyOf())
        }
    }

    fun copyLatestFrame(): Nv21Frame? = copyLatestRawFrame()

    fun getLatestFrameSize(): Size? {
        return synchronized(lock) {
            latestFrame?.let { Size(it.width, it.height) } ?: frameSize
        }
    }

    fun copyLatestValidatedSquareFrame(): SquareNv21Frame? {
        return copyLatestSquareFrame(SharedCameraViewportPolicy::calculateValidatedNv21SquareCropRect)
    }

    fun copyLatestValidatedFrame(targetSize: Int = CROPPED_TARGET_SIZE): CroppedNv21Frame? {
        return copyLatestResizedSquareFrame(
            targetSize = targetSize,
            cropRectProvider = SharedCameraViewportPolicy::calculateValidatedNv21SquareCropRect,
        )
    }

    private fun copyLatestSquareFrame(cropRectProvider: (Int, Int) -> Rect): SquareNv21Frame? {
        return synchronized(lock) {
            val frame = latestFrame ?: return@synchronized null
            val cropRect = cropRectProvider(frame.width, frame.height)
            if (cropRect.width() <= 0 || cropRect.height() <= 0) {
                return@synchronized null
            }
            val croppedData = BitmapUtils.cropNv21Rect(
                nv21 = frame.data,
                width = frame.width,
                height = frame.height,
                cropRect = cropRect,
            ) ?: return@synchronized null
            SquareNv21Frame(
                data = croppedData,
                width = cropRect.width(),
                height = cropRect.height(),
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                cropRect = Rect(cropRect),
                timestamp = frame.timestamp,
                receivedAtElapsedMs = frame.receivedAtElapsedMs,
            )
        }
    }

    private fun copyLatestResizedSquareFrame(
        targetSize: Int,
        cropRectProvider: (Int, Int) -> Rect,
    ): CroppedNv21Frame? {
        if (targetSize <= 0) {
            return null
        }
        val squareFrame = copyLatestSquareFrame(cropRectProvider) ?: return null
        val outputData = if (squareFrame.width == targetSize && squareFrame.height == targetSize) {
            squareFrame.data.copyOf()
        } else {
            BitmapUtils.resizeSquareNv21(
                nv21 = squareFrame.data,
                width = squareFrame.width,
                height = squareFrame.height,
                targetSize = targetSize,
            ) ?: return null
        }
        return CroppedNv21Frame(
            data = outputData,
            width = targetSize,
            height = targetSize,
            sourceWidth = squareFrame.sourceWidth,
            sourceHeight = squareFrame.sourceHeight,
            timestamp = squareFrame.timestamp,
            receivedAtElapsedMs = squareFrame.receivedAtElapsedMs,
        )
    }

    fun updateSurfaceTexture() {
        synchronized(lock) {
            nv21Helper
        }?.updateTexture()
    }

    fun getSurfaceTextureId(): Int {
        return synchronized(lock) {
            nv21Helper
        }?.getTextureId() ?: 0
    }

    fun getSurfaceTransformMatrix(): FloatArray {
        return synchronized(lock) {
            nv21Helper
        }?.getTransformMatrix() ?: FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    fun getSurfaceCameraWidth(): Int {
        return surfacePreviewSize?.width ?: synchronized(lock) {
            nv21Helper
        }?.getCameraWidth() ?: 0
    }

    fun getSurfaceCameraHeight(): Int {
        return surfacePreviewSize?.height ?: synchronized(lock) {
            nv21Helper
        }?.getCameraHeight() ?: 0
    }

    fun getSurfaceAppliedPreviewFps(): Int? = surfaceAppliedPreviewFps

    fun isSurfaceVideoStabilizationEnabled(): Boolean? = surfaceVideoStabilizationEnabled

    fun updateSurfacePreviewConfig(
        width: Int,
        height: Int,
        appliedPreviewFps: Int? = null,
        videoStabilizationEnabled: Boolean? = null,
    ) {
        if (width > 0 && height > 0) {
            surfacePreviewSize = Size(width, height)
        }
        surfaceAppliedPreviewFps = appliedPreviewFps
        surfaceVideoStabilizationEnabled = videoStabilizationEnabled
    }

    fun updateSurfaceZoomLevel(zoomLevel: Int) {
        surfaceZoomLevel = zoomLevel
    }

    fun clearSurfacePreviewConfig() {
        surfacePreviewSize = null
        surfaceAppliedPreviewFps = null
        surfaceVideoStabilizationEnabled = null
        surfaceZoomLevel = null
    }

    fun stopSurfacePreview() {
        synchronized(lock) {
            nv21Helper
        }?.releaseSurface()
    }

    fun startSurfacePreview(callback: CameraShareHelper.SurfaceCallback): Boolean {
        val helper = synchronized(lock) {
            nv21Helper
        } ?: return false
        return runCatching {
            helper.initSurfaceWithConfig(sharedCameraConfig(), callback)
            true
        }.onFailure { error ->
            Log.e(TAG, "startSurfacePreview failed: ${error.message}", error)
        }.getOrDefault(false)
    }

    private fun sharedCameraConfig(): CameraShareConfig {
        val zoomRatio = runCatching {
            InspectionConfigRepository.get().aiInspection.sharedCameraZoomRatio
        }.getOrDefault(1.0f)
        return CameraShareConfig(
            previewWidth = SHARED_PREVIEW_WIDTH,
            previewHeight = SHARED_PREVIEW_HEIGHT,
            previewTargetFps = SHARED_PREVIEW_TARGET_FPS,
            enableVideoStabilization = false,
            zoomLevel = sdkZoomLevelFor(zoomRatio),
        )
    }

    /**
     * 将 NV21 使用的 ROI 映射到共享 Surface 的纹理坐标。
     * SurfaceTexture 坐标保持原始 camera 画幅，直接采样 NV21 使用的方形 ROI。
     * 不能按方形 viewport 再计算归一化正方形，否则会将 16:9 内容压进方形 View。
     */
    internal fun mapFrameCropToSurfaceTexture(
        surfaceWidth: Int,
        surfaceHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        frameCrop: NormalizedCropRect,
        matrixSwapped: Boolean,
    ): SurfaceTextureCropMapping? {
        if (
            surfaceWidth <= 0 ||
            surfaceHeight <= 0 ||
            frameWidth <= 0 ||
            frameHeight <= 0
        ) {
            return null
        }
        val orientationMismatch =
            (surfaceWidth > surfaceHeight) != (frameWidth > frameHeight)
        if (!orientationMismatch || matrixSwapped) {
            return SurfaceTextureCropMapping(
                mode = "direct",
                textureCrop = frameCrop,
            )
        }

        return SurfaceTextureCropMapping(
            mode = "frame_roi",
            textureCrop = frameCrop,
        )
    }

    fun setPreviewZoomRatio(zoomRatio: Float): Float {
        val configuredZoom = syncCachedZoomRatio()
        if (kotlin.math.abs(zoomRatio - configuredZoom) > 0.001f) {
            Log.i(
                TAG,
                "ignore custom preview zoom request requested=$zoomRatio configured=$configuredZoom",
            )
        }
        return configuredZoom
    }

    fun getAppliedPreviewZoomRatio(): Float = currentZoomRatio

    fun getPreferredPreviewZoomRatio(): Float = currentZoomRatio

    fun getFrameSize(): Size? = frameSize

    // zoom 的唯一配置入口是 sharedCameraConfig()，此处仅同步缓存值供外部查询
    private fun syncCachedZoomRatio(): Float {
        val zoomRatio = runCatching {
            InspectionConfigRepository.get().aiInspection.sharedCameraZoomRatio.coerceIn(1.0f, 3.0f)
        }.getOrDefault(1.0f)
        currentZoomRatio = zoomRatio
        return zoomRatio
    }

    internal fun sdkZoomLevelFor(zoomRatio: Float): Int {
        return when {
            zoomRatio < 1.9f -> 1
            zoomRatio < 2.5f -> 2
            else -> 3
        }
    }

    internal fun isHelperCallbackStale(activeGeneration: Long, callbackGeneration: Long): Boolean {
        return activeGeneration != callbackGeneration
    }

    private fun notifyFrameReady(success: Boolean) {
        val callbacks = synchronized(lock) {
            frameReadyCallbacks.toList().also { frameReadyCallbacks.clear() }
        }
        callbacks.forEach { callback ->
            mainHandler.post { callback(success) }
        }
    }
}
