package com.rokid.glass.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.rokid.glass.MyApplication
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.SPUtil
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper

/**
 * 基于 Rokid CameraShareHelper 的统一 NV21 帧源。
 * 只保留最近一帧原始 NV21，业务侧按需裁切，避免每帧预处理和排队。
 */
object RokidFrameSource {

    enum class PreviewFramingMode {
        CENTER,
        BOTTOM,
        TARGET_CENTER,
    }

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

    private const val TAG = "RokidFrameSource"
    private const val PREF_KEY_PREVIEW_ZOOM_RATIO = "rokid_frame_source.preview_zoom_ratio"
    private const val DEFAULT_PREVIEW_ZOOM_RATIO = 2.0f
    private const val DEFAULT_TARGET_CENTER_X_RATIO = 0.50f
    private const val DEFAULT_TARGET_CENTER_Y_RATIO = 0.64f
    private const val CROPPED_TARGET_SIZE = 640
    private const val FRAME_STREAM_RESTART_RELEASE_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val frameReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

    private var nv21Helper: CameraShareHelper? = null

    @Volatile
    private var frameStreamOpened = false

    @Volatile
    private var frameSize: Size? = null

    @Volatile
    private var latestFrame: Nv21Frame? = null
    private var latestFrameBuffer: ByteArray? = null

    @Volatile
    private var currentZoomRatio = loadStoredPreviewZoomRatio()

    @Volatile
    private var currentFramingMode = PreviewFramingMode.CENTER

    @Volatile
    private var currentTargetCenterXRatio = DEFAULT_TARGET_CENTER_X_RATIO

    @Volatile
    private var currentTargetCenterYRatio = DEFAULT_TARGET_CENTER_Y_RATIO

    fun startFrameStream(onReady: (Boolean) -> Unit = {}) {
        synchronized(lock) {
            if (!GlassSdk.isReady()) {
                Log.w(TAG, "startFrameStream skipped sdkNotReady")
                mainHandler.post { onReady(false) }
                return
            }
            if (nv21Helper != null) {
                Log.i(TAG, "startFrameStream reuse helper opened=$frameStreamOpened")
                if (frameStreamOpened) {
                    mainHandler.post { onReady(true) }
                } else {
                    frameReadyCallbacks += onReady
                }
                return
            }
            Log.i(TAG, "startFrameStream create helper")
            frameReadyCallbacks += onReady
            latestFrame = null
            nv21Helper = CameraShareHelper().apply {
                initNv21Export(enableMix = false, callback = object : CameraShareHelper.Nv21Callback {
                    override fun onCameraOpened(width: Int, height: Int) {
                        frameStreamOpened = true
                        frameSize = Size(width, height)
                        applySdkZoom(currentZoomRatio)
                        notifyFrameReady(true)
                    }

                    override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
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
                        synchronized(lock) {
                            frameStreamOpened = false
                            frameSize = null
                            latestFrame = null
                            latestFrameBuffer = null
                            nv21Helper = null
                        }
                    }

                    override fun onError(code: Int, msg: String) {
                        Log.e(TAG, "frame stream error code=$code msg=$msg")
                        synchronized(lock) {
                            frameStreamOpened = false
                            frameSize = null
                            latestFrame = null
                            latestFrameBuffer = null
                            nv21Helper = null
                        }
                        notifyFrameReady(false)
                    }
                })
            }
        }
    }

    fun stopFrameStream() {
        val helper = synchronized(lock) {
            Log.i(TAG, "stopFrameStream helperExists=${nv21Helper != null}")
            frameReadyCallbacks.clear()
            frameStreamOpened = false
            frameSize = null
            latestFrame = null
            latestFrameBuffer = null
            nv21Helper.also { nv21Helper = null }
        }
        helper?.releaseNv21Export()
    }

    fun restartFrameStream(
        releaseDelayMs: Long = FRAME_STREAM_RESTART_RELEASE_DELAY_MS,
        onReady: (Boolean) -> Unit = {},
    ) {
        Log.i(TAG, "restartFrameStream begin releaseDelayMs=$releaseDelayMs")
        stopFrameStream()
        mainHandler.postDelayed(
            {
                Log.i(TAG, "restartFrameStream relaunch")
                startFrameStream { success ->
                    Log.i(TAG, "restartFrameStream finished success=$success")
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

    fun copyLatestCroppedFrame(targetSize: Int = CROPPED_TARGET_SIZE): CroppedNv21Frame? {
        return synchronized(lock) {
            val frame = latestFrame ?: return@synchronized null
            val croppedData = BitmapUtils.cropCenterNv21(
                nv21 = frame.data,
                width = frame.width,
                height = frame.height,
                targetSize = targetSize,
            ) ?: return@synchronized null
            CroppedNv21Frame(
                data = croppedData,
                width = targetSize,
                height = targetSize,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                timestamp = frame.timestamp,
                receivedAtElapsedMs = frame.receivedAtElapsedMs,
            )
        }
    }

    fun setPreviewZoomRatio(zoomRatio: Float): Float {
        val clamped = zoomRatio.coerceIn(1.0f, 3.0f)
        currentZoomRatio = clamped
        persistPreviewZoomRatio(clamped)
        applySdkZoom(clamped)
        return zoomLevelFor(clamped).toFloat()
    }

    fun getAppliedPreviewZoomRatio(): Float = zoomLevelFor(currentZoomRatio).toFloat()

    fun getPreferredPreviewZoomRatio(): Float = currentZoomRatio

    fun setPreviewFramingMode(framingMode: PreviewFramingMode) {
        currentFramingMode = framingMode
    }

    fun getPreviewFramingMode(): PreviewFramingMode = currentFramingMode

    fun setPreviewTargetCenter(xRatio: Float, yRatio: Float) {
        currentTargetCenterXRatio = xRatio.coerceIn(0.1f, 0.9f)
        currentTargetCenterYRatio = yRatio.coerceIn(0.1f, 0.9f)
    }

    fun getPreviewTargetCenterXRatio(): Float = currentTargetCenterXRatio

    fun getPreviewTargetCenterYRatio(): Float = currentTargetCenterYRatio

    fun getFrameSize(): Size? = frameSize

    private fun loadStoredPreviewZoomRatio(): Float {
        return runCatching {
            SPUtil.getInstance(MyApplication.getContext())
                .getFloat(PREF_KEY_PREVIEW_ZOOM_RATIO, DEFAULT_PREVIEW_ZOOM_RATIO)
                .coerceIn(1.0f, 3.0f)
        }.getOrDefault(DEFAULT_PREVIEW_ZOOM_RATIO)
    }

    private fun persistPreviewZoomRatio(zoomRatio: Float) {
        runCatching {
            SPUtil.getInstance(MyApplication.getContext())
                .putFloat(PREF_KEY_PREVIEW_ZOOM_RATIO, zoomRatio)
        }.onFailure { error ->
            Log.w(TAG, "persist preview zoom failed: ${error.message}")
        }
    }

    private fun applySdkZoom(zoomRatio: Float) {
        if (!GlassSdk.isReady()) {
            return
        }
        val level = zoomLevelFor(zoomRatio)
        runCatching {
            GlassSdk.getGlassMediaService()?.zoomCamera(level)
        }.onFailure { error ->
            Log.w(TAG, "set zoom failed level=$level error=${error.message}")
        }
    }

    private fun zoomLevelFor(zoomRatio: Float): Int {
        return when {
            zoomRatio < 1.9f -> 1
            zoomRatio < 2.5f -> 2
            else -> 3
        }
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
