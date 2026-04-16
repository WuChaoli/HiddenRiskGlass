package com.rokid.glass.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import com.rokid.glass.MyApplication
import com.rokid.glass.utils.SPUtil
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper

/**
 * 基于 Rokid CameraShareHelper 的统一帧源。
 * 统一管理预览纹理和 NV21 视频帧，避免业务页面直接操作 SDK 细节。
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
    )

    private const val TAG = "RokidFrameSource"
    private const val PREF_KEY_PREVIEW_ZOOM_RATIO = "rokid_frame_source.preview_zoom_ratio"
    private const val DEFAULT_PREVIEW_ZOOM_RATIO = 2.0f
    private const val DEFAULT_TARGET_CENTER_X_RATIO = 0.50f
    private const val DEFAULT_TARGET_CENTER_Y_RATIO = 0.64f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val previewReadyCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val frameReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

    private var surfaceHelper: CameraShareHelper? = null
    private var nv21Helper: CameraShareHelper? = null
    private var previewFrameListener: (() -> Unit)? = null

    @Volatile
    private var previewOpened = false

    @Volatile
    private var frameStreamOpened = false

    @Volatile
    private var previewSize: Size? = null

    @Volatile
    private var frameSize: Size? = null

    @Volatile
    private var latestFrame: Nv21Frame? = null

    @Volatile
    private var currentZoomRatio = loadStoredPreviewZoomRatio()

    @Volatile
    private var currentFramingMode = PreviewFramingMode.CENTER

    @Volatile
    private var currentTargetCenterXRatio = DEFAULT_TARGET_CENTER_X_RATIO

    @Volatile
    private var currentTargetCenterYRatio = DEFAULT_TARGET_CENTER_Y_RATIO

    fun startPreview(
        onReady: (Boolean) -> Unit = {},
        onFrameAvailable: () -> Unit = {},
    ) {
        synchronized(lock) {
            previewFrameListener = onFrameAvailable
            if (!GlassSdk.isReady()) {
                mainHandler.post { onReady(false) }
                return
            }
            if (surfaceHelper != null) {
                if (previewOpened) {
                    mainHandler.post { onReady(true) }
                } else {
                    previewReadyCallbacks += onReady
                }
                return
            }
            previewReadyCallbacks += onReady
            surfaceHelper = CameraShareHelper().apply {
                initSurface(object : CameraShareHelper.SurfaceCallback {
                    override fun onCameraOpened(width: Int, height: Int) {
                        previewOpened = true
                        previewSize = Size(width, height)
                        applySdkZoom(currentZoomRatio)
                        notifyPreviewReady(true)
                    }

                    override fun onFrameAvailable() {
                        previewFrameListener?.invoke()
                    }

                    override fun onCameraClosed() {
                        synchronized(lock) {
                            previewOpened = false
                            previewSize = null
                            surfaceHelper = null
                        }
                    }

                    override fun onError(code: Int, msg: String) {
                        Log.e(TAG, "preview error code=$code msg=$msg")
                        synchronized(lock) {
                            previewOpened = false
                            previewSize = null
                            surfaceHelper = null
                        }
                        notifyPreviewReady(false)
                    }
                })
            }
        }
    }

    fun stopPreview() {
        val helper = synchronized(lock) {
            previewReadyCallbacks.clear()
            previewFrameListener = null
            previewOpened = false
            previewSize = null
            surfaceHelper.also { surfaceHelper = null }
        }
        helper?.releaseSurface()
    }

    fun startFrameStream(onReady: (Boolean) -> Unit = {}) {
        synchronized(lock) {
            if (!GlassSdk.isReady()) {
                mainHandler.post { onReady(false) }
                return
            }
            if (nv21Helper != null) {
                if (frameStreamOpened) {
                    mainHandler.post { onReady(true) }
                } else {
                    frameReadyCallbacks += onReady
                }
                return
            }
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
                        latestFrame = Nv21Frame(
                            data = nv21.copyOf(),
                            width = width,
                            height = height,
                            timestamp = timestamp,
                        )
                    }

                    override fun onCameraClosed() {
                        synchronized(lock) {
                            frameStreamOpened = false
                            frameSize = null
                            latestFrame = null
                            nv21Helper = null
                        }
                    }

                    override fun onError(code: Int, msg: String) {
                        Log.e(TAG, "frame stream error code=$code msg=$msg")
                        synchronized(lock) {
                            frameStreamOpened = false
                            frameSize = null
                            latestFrame = null
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
            frameReadyCallbacks.clear()
            frameStreamOpened = false
            frameSize = null
            latestFrame = null
            nv21Helper.also { nv21Helper = null }
        }
        helper?.releaseNv21Export()
    }

    fun releaseAll() {
        stopPreview()
        stopFrameStream()
    }

    fun isFrameStreamWarm(): Boolean = frameStreamOpened && latestFrame != null

    fun copyLatestFrame(): Nv21Frame? {
        val frame = latestFrame ?: return null
        return frame.copy(data = frame.data.copyOf())
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

    fun getPreviewSize(): Size? = previewSize

    fun getFrameSize(): Size? = frameSize

    fun getTextureId(): Int = surfaceHelper?.getTextureId() ?: -1

    fun updatePreviewTexture() {
        surfaceHelper?.updateTexture()
    }

    fun getPreviewTransformMatrix(): FloatArray = surfaceHelper?.getTransformMatrix() ?: IDENTITY_MATRIX

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

    private fun notifyPreviewReady(success: Boolean) {
        val callbacks = synchronized(lock) {
            previewReadyCallbacks.toList().also { previewReadyCallbacks.clear() }
        }
        callbacks.forEach { callback ->
            mainHandler.post { callback(success) }
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

    private val IDENTITY_MATRIX = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
