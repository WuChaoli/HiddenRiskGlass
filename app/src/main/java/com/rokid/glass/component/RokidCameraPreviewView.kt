package com.rokid.glass.component

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.camera.SharedCameraViewportPolicy
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal data class PreviewVertexScale(
    val x: Float,
    val y: Float,
)

internal fun calculateAspectFitScale(
    sourceWidth: Int,
    sourceHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): PreviewVertexScale {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
        return PreviewVertexScale(1f, 1f)
    }
    val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
    val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
    return if (sourceAspect > viewportAspect) {
        PreviewVertexScale(1f, viewportAspect / sourceAspect)
    } else {
        PreviewVertexScale(sourceAspect / viewportAspect, 1f)
    }
}

/**
 * 统一显示与上传/推理同一取景 ROI 的共享相机预览。
 * 预览链路使用 SDK Surface 共享，避免走 NV21 -> CPU -> GL 的重复搬运。
 */
class RokidCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    enum class PreviewRenderMode {
        AUTO_SURFACE_SQUARE,
        RAW_ASPECT_FIT,
        SURFACE_BOTTOM_SQUARE,
        DEBUG_TEXTURE_CROP_FILL,
    }

    companion object {
        private const val TAG = "RokidCameraPreview"
        private const val HEALTH_CHECK_INTERVAL_MS = 300L
        private const val FIRST_FRAME_TIMEOUT_MS = 1500L
        private const val STALE_FRAME_TIMEOUT_MS = 1000L
        private const val MAX_CONSECUTIVE_HEALTH_ISSUES = 3
        private const val STOP_RELEASE_WAIT_TIMEOUT_MS = 400L
    }

    enum class PreviewHealthIssue {
        FIRST_FRAME_TIMEOUT,
        FRAME_STALLED,
    }

    enum class PreviewRecoveryState {
        SUCCESS,
        FAILED,
        ABANDONED,
    }

    interface PreviewHealthListener {
        fun onPreviewHealthIssue(issue: PreviewHealthIssue)

        fun onPreviewRecoveryStateChanged(state: PreviewRecoveryState) = Unit
    }

    private val renderer = SharedSurfaceRenderer(
        frameAvailableCallback = {
            requestRender()
        },
        frameDrawnCallback = {
            previewFrameDrawn = true
            synchronized(healthLock) {
                firstFrameReceived = true
                lastFrameReceivedAtElapsedMs = SystemClock.elapsedRealtime()
                if (!recoveryPending) {
                    lastHealthIssue = null
                    consecutiveHealthIssueCount = 0
                }
            }
        },
        surfaceErrorCallback = { code, message ->
            Log.e(TAG, "surface preview error code=$code msg=$message")
        },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val healthLock = Any()

    @Volatile
    private var previewStarted = false

    @Volatile
    private var previewFrameDrawn = false

    @Volatile
    private var healthCheckTask: ScheduledFuture<*>? = null

    @Volatile
    private var previewHealthListener: PreviewHealthListener? = null

    @Volatile
    private var healthMonitoringEnabled = false

    private var previewStartedAtElapsedMs = 0L
    private var firstFrameReceived = false
    private var lastFrameReceivedAtElapsedMs = 0L
    private var lastHealthIssue: PreviewHealthIssue? = null
    private var consecutiveHealthIssueCount = 0
    private var recoveryPending = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPreviewHealthListener(listener: PreviewHealthListener?) {
        previewHealthListener = listener
    }

    fun setPreviewHealthMonitoringEnabled(enabled: Boolean) {
        healthMonitoringEnabled = enabled
        if (!enabled) {
            synchronized(healthLock) {
                resetHealthStateLocked()
            }
        }
    }

    fun reportPreviewRecoveryState(state: PreviewRecoveryState) {
        synchronized(healthLock) {
            recoveryPending = false
            lastHealthIssue = null
            consecutiveHealthIssueCount = 0
            if (state != PreviewRecoveryState.SUCCESS && !previewStarted) {
                firstFrameReceived = false
                lastFrameReceivedAtElapsedMs = 0L
            }
        }
        previewHealthListener?.let { listener ->
            mainHandler.post { listener.onPreviewRecoveryStateChanged(state) }
        }
    }

    fun startPreview(onReady: (Boolean) -> Unit = {}) {
        if (previewStarted) {
            onReady(true)
            return
        }
        previewStarted = true
        previewFrameDrawn = false
        synchronized(healthLock) {
            previewStartedAtElapsedMs = SystemClock.elapsedRealtime()
            resetHealthStateLocked()
        }
        onResume()
        renderMode = RENDERMODE_CONTINUOUSLY
        Log.i(TAG, "startPreview requested")
        queueEvent {
            renderer.startSurfacePreview { success ->
                mainHandler.post {
                    if (!previewStarted) {
                        onReady(false)
                        return@post
                    }
                    onReady(success)
                }
            }
        }
        healthCheckTask?.cancel(true)
        healthCheckTask = healthExecutor.scheduleWithFixedDelay(
            {
                if (!previewStarted || !healthMonitoringEnabled) {
                    return@scheduleWithFixedDelay
                }
                val issue = detectPreviewHealthIssue() ?: return@scheduleWithFixedDelay
                if (!shouldDispatchHealthIssue(issue)) {
                    return@scheduleWithFixedDelay
                }
                Log.w(TAG, "preview health issue issue=$issue")
                previewHealthListener?.let { listener ->
                    mainHandler.post { listener.onPreviewHealthIssue(issue) }
                }
            },
            HEALTH_CHECK_INTERVAL_MS,
            HEALTH_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stopPreview() {
        stopPreviewInternal(releaseSharedSurface = true)
    }

    /**
     * 页面 View 脱附时只清理本地渲染资源，避免旧页面越权释放当前 owner 的共享 preview。
     */
    fun detachPreview() {
        stopPreviewInternal(releaseSharedSurface = false)
    }

    fun isPreviewStarted(): Boolean = previewStarted

    fun isPreviewFrameDrawn(): Boolean = previewFrameDrawn

    fun setPreviewRenderMode(mode: PreviewRenderMode) {
        renderer.setPreviewRenderMode(mode)
        requestRender()
    }

    fun setDebugTextureCrop(left: Float, top: Float, width: Float, height: Float) {
        queueEvent {
            renderer.setDebugTextureCrop(left, top, width, height)
        }
        requestRender()
    }

    override fun onDetachedFromWindow() {
        detachPreview()
        healthExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun stopPreviewInternal(releaseSharedSurface: Boolean) {
        if (!previewStarted) {
            runRendererStopAndWait(releaseSharedSurface)
            return
        }
        Log.i(TAG, "stopPreview requested releaseSharedSurface=$releaseSharedSurface")
        previewStarted = false
        previewFrameDrawn = false
        healthCheckTask?.cancel(true)
        healthCheckTask = null
        synchronized(healthLock) {
            previewStartedAtElapsedMs = 0L
            resetHealthStateLocked()
        }
        runRendererStopAndWait(releaseSharedSurface)
        renderMode = RENDERMODE_WHEN_DIRTY
        onPause()
    }

    private fun runRendererStopAndWait(releaseSharedSurface: Boolean) {
        val releaseLatch = CountDownLatch(1)
        queueEvent {
            try {
                renderer.stopSurfacePreview(releaseSharedSurface)
            } finally {
                releaseLatch.countDown()
            }
        }
        val released = runCatching {
            releaseLatch.await(STOP_RELEASE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!released) {
            Log.w(TAG, "timeout waiting shared surface release before pause")
        }
    }

    private fun detectPreviewHealthIssue(): PreviewHealthIssue? {
        val now = SystemClock.elapsedRealtime()
        synchronized(healthLock) {
            if (recoveryPending) {
                return null
            }
            return when {
                !firstFrameReceived && previewStartedAtElapsedMs > 0L &&
                    now - previewStartedAtElapsedMs >= FIRST_FRAME_TIMEOUT_MS -> {
                    PreviewHealthIssue.FIRST_FRAME_TIMEOUT
                }

                firstFrameReceived && lastFrameReceivedAtElapsedMs > 0L &&
                    now - lastFrameReceivedAtElapsedMs >= STALE_FRAME_TIMEOUT_MS -> {
                    PreviewHealthIssue.FRAME_STALLED
                }

                else -> null
            }
        }
    }

    private fun shouldDispatchHealthIssue(issue: PreviewHealthIssue): Boolean {
        synchronized(healthLock) {
            if (recoveryPending) {
                return false
            }
            if (lastHealthIssue != issue) {
                lastHealthIssue = issue
                consecutiveHealthIssueCount = 1
            } else {
                consecutiveHealthIssueCount++
            }
            Log.w(
                TAG,
                "preview health issue observed issue=$issue consecutive=$consecutiveHealthIssueCount",
            )
            if (consecutiveHealthIssueCount < MAX_CONSECUTIVE_HEALTH_ISSUES) {
                return false
            }
            recoveryPending = true
            consecutiveHealthIssueCount = 0
            return true
        }
    }

    private fun resetHealthStateLocked() {
        firstFrameReceived = false
        lastFrameReceivedAtElapsedMs = 0L
        lastHealthIssue = null
        consecutiveHealthIssueCount = 0
        recoveryPending = false
    }

    private class SharedSurfaceRenderer(
        private val frameAvailableCallback: () -> Unit,
        private val frameDrawnCallback: () -> Unit,
        private val surfaceErrorCallback: (Int, String) -> Unit,
    ) : GLSurfaceView.Renderer {
        private val vertexBuffer: FloatBuffer = allocateBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )
        private val texCoordBuffer: FloatBuffer = allocateBuffer(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            ),
        )
        private val textureMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val cropRect = FloatArray(4).apply {
            this[0] = 0f
            this[1] = 0f
            this[2] = 1f
            this[3] = 1f
        }

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureMatrixHandle = 0
        private var cropRectHandle = 0
        private var oesTextureHandle = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var pendingStartCallback: ((Boolean) -> Unit)? = null
        private val debugTextureCrop = FloatArray(4).apply {
            this[0] = 0f
            this[1] = 0f
            this[2] = 1f
            this[3] = 1f
        }
        @Volatile
        private var previewRenderMode = PreviewRenderMode.AUTO_SURFACE_SQUARE

        @Volatile
        private var surfaceActive = false

        @Volatile
        private var framePending = false

        @Volatile
        private var hasFrame = false

        @Volatile
        private var firstFrameLogged = false

        @Volatile
        private var firstDrawLogged = false

        @Volatile
        private var invalidTextureWarned = false

        @Volatile
        private var cropLogged = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            cropRectHandle = GLES20.glGetUniformLocation(program, "uCropRect")
            oesTextureHandle = GLES20.glGetUniformLocation(program, "uOesTexture")
            Log.i(TAG, "gl surface created program=$program")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceWidth = width
            surfaceHeight = height
            Log.i(TAG, "gl surface changed width=$width height=$height")
            val pendingCallback = pendingStartCallback
            if (pendingCallback != null && isGlSurfaceReady()) {
                pendingStartCallback = null
                Log.i(TAG, "resume deferred surface preview start width=$surfaceWidth height=$surfaceHeight")
                performStartSurfacePreview(pendingCallback)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!surfaceActive) {
                return
            }

            runCatching {
                RokidFrameSource.updateSurfaceTexture()
                val latestMatrix = RokidFrameSource.getSurfaceTransformMatrix()
                if (latestMatrix.size == 16) {
                    System.arraycopy(latestMatrix, 0, textureMatrix, 0, 16)
                } else {
                    Matrix.setIdentityM(textureMatrix, 0)
                }
                updateRenderState()
                hasFrame = true
            }.onFailure { error ->
                if (framePending || !hasFrame) {
                    Log.w(TAG, "update shared surface texture failed", error)
                }
            }
            framePending = false
            if (!hasFrame) {
                return
            }
            if (program == 0) {
                return
            }

            val textureId = RokidFrameSource.getSurfaceTextureId()
            if (textureId == 0) {
                if (!invalidTextureWarned) {
                    invalidTextureWarned = true
                    Log.w(TAG, "shared surface texture id still 0 after frame update")
                }
                return
            }

            if (!firstDrawLogged) {
                firstDrawLogged = true
                Log.i(
                    TAG,
                    "first preview draw textureId=$textureId crop=[${cropRect[0]},${cropRect[1]},${cropRect[2]},${cropRect[3]}]",
                )
            }

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
            GLES20.glUniform4fv(cropRectHandle, 1, cropRect, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(oesTextureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frameDrawnCallback.invoke()

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun startSurfacePreview(onReady: (Boolean) -> Unit) {
            stopSurfacePreview(releaseSharedSurface = true)
            surfaceActive = true
            firstFrameLogged = false
            firstDrawLogged = false
            invalidTextureWarned = false
            cropLogged = false
            if (!isGlSurfaceReady()) {
                pendingStartCallback = onReady
                Log.i(
                    TAG,
                    "defer surface preview start until gl ready program=$program width=$surfaceWidth height=$surfaceHeight",
                )
                return
            }
            performStartSurfacePreview(onReady)
        }

        fun setPreviewRenderMode(mode: PreviewRenderMode) {
            previewRenderMode = mode
            cropLogged = false
        }

        fun setDebugTextureCrop(left: Float, top: Float, width: Float, height: Float) {
            debugTextureCrop[0] = left
            debugTextureCrop[1] = top
            debugTextureCrop[2] = width
            debugTextureCrop[3] = height
            cropLogged = false
        }

        private fun performStartSurfacePreview(onReady: (Boolean) -> Unit) {
            val readyDispatched = AtomicBoolean(false)
            val started = RokidFrameSource.startSurfacePreview(
                object : CameraShareHelper.SurfaceCallback {
                    override fun onCameraOpened(width: Int, height: Int) {
                        RokidFrameSource.updateSurfacePreviewConfig(width, height)
                        Log.i(TAG, "surface preview camera opened width=$width height=$height")
                        if (readyDispatched.compareAndSet(false, true)) {
                            onReady(true)
                        }
                    }

                    override fun onFrameAvailable() {
                        framePending = true
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.i(TAG, "shared surface first frame available")
                        }
                        frameAvailableCallback.invoke()
                    }

                    override fun onCameraClosed() {
                        RokidFrameSource.clearSurfacePreviewConfig()
                        Log.i(TAG, "surface preview camera closed")
                        surfaceActive = false
                        framePending = false
                        hasFrame = false
                    }

                    override fun onError(code: Int, msg: String) {
                        RokidFrameSource.clearSurfacePreviewConfig()
                        surfaceActive = false
                        framePending = false
                        hasFrame = false
                        surfaceErrorCallback(code, msg)
                        if (readyDispatched.compareAndSet(false, true)) {
                            onReady(false)
                        }
                    }

                    override fun onSurfaceShareConfigChanged(
                        width: Int,
                        height: Int,
                        appliedPreviewFps: Int,
                        videoStabilizationEnabled: Boolean,
                    ) {
                        RokidFrameSource.updateSurfacePreviewConfig(
                            width = width,
                            height = height,
                            appliedPreviewFps = appliedPreviewFps,
                            videoStabilizationEnabled = videoStabilizationEnabled,
                        )
                        Log.i(
                            TAG,
                            "surface share config changed width=$width height=$height appliedPreviewFps=$appliedPreviewFps videoStabilizationEnabled=$videoStabilizationEnabled",
                        )
                    }

                    override fun onZoomLevelChanged(zoomLevel: Int) {
                        RokidFrameSource.updateSurfaceZoomLevel(zoomLevel)
                        Log.i(TAG, "surface share zoom changed zoomLevel=$zoomLevel")
                    }
                },
            )
            if (!started) {
                surfaceActive = false
                if (readyDispatched.compareAndSet(false, true)) {
                    onReady(false)
                }
            }
        }

        fun stopSurfacePreview(releaseSharedSurface: Boolean) {
            pendingStartCallback = null
            surfaceActive = false
            framePending = false
            hasFrame = false
            firstFrameLogged = false
            firstDrawLogged = false
            invalidTextureWarned = false
            cropLogged = false
            Matrix.setIdentityM(textureMatrix, 0)
            cropRect[0] = 0f
            cropRect[1] = 0f
            cropRect[2] = 1f
            cropRect[3] = 1f
            updateVertexScale(1f, 1f)
            if (releaseSharedSurface) {
                RokidFrameSource.stopSurfacePreview()
            }
        }

        private fun isGlSurfaceReady(): Boolean {
            return program != 0 && surfaceWidth > 0 && surfaceHeight > 0
        }

        private fun updateRenderState() {
            when (previewRenderMode) {
                PreviewRenderMode.AUTO_SURFACE_SQUARE -> updateValidatedSurfaceCrop()
                PreviewRenderMode.RAW_ASPECT_FIT -> updateRawAspectFit()
                PreviewRenderMode.SURFACE_BOTTOM_SQUARE -> updateSurfaceBottomSquare()
                PreviewRenderMode.DEBUG_TEXTURE_CROP_FILL -> updateDebugTextureCropFill()
            }
        }

        /**
         * 诊断模式完整展示 SDK Surface 内容，只做等比缩放，不参与业务 ROI 裁切。
         */
        private fun updateRawAspectFit() {
            cropRect[0] = 0f
            cropRect[1] = 0f
            cropRect[2] = 1f
            cropRect[3] = 1f
            val configuredWidth = RokidFrameSource.getSurfaceCameraWidth()
            val configuredHeight = RokidFrameSource.getSurfaceCameraHeight()
            if (
                configuredWidth <= 0 ||
                configuredHeight <= 0 ||
                surfaceWidth <= 0 ||
                surfaceHeight <= 0
            ) {
                updateVertexScale(1f, 1f)
                return
            }
            val matrixSwapped = isAxisSwapped(textureMatrix)
            val sourceWidth = if (matrixSwapped) configuredHeight else configuredWidth
            val sourceHeight = if (matrixSwapped) configuredWidth else configuredHeight
            val scale = calculateAspectFitScale(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                viewportWidth = surfaceWidth,
                viewportHeight = surfaceHeight,
            )
            updateVertexScale(scale.x, scale.y)
            if (firstFrameLogged && !cropLogged) {
                cropLogged = true
                Log.i(
                    TAG,
                    "preview raw aspect fit configured=${configuredWidth}x${configuredHeight} viewport=${surfaceWidth}x${surfaceHeight} matrixSwapped=$matrixSwapped scale=${scale.x}x${scale.y} matrix=${textureMatrixSummary(textureMatrix)}",
                )
            }
        }

        /** 旧 SDK Surface 异常的调试复测模式，不作为正式业务渲染策略。 */
        private fun updateSurfaceBottomSquare(logLabel: String = "surface bottom square") {
            val configuredWidth = RokidFrameSource.getSurfaceCameraWidth()
            val configuredHeight = RokidFrameSource.getSurfaceCameraHeight()
            if (configuredWidth <= 0 || configuredHeight <= 0) {
                cropRect[0] = 0f
                cropRect[1] = 0f
                cropRect[2] = 1f
                cropRect[3] = 1f
                updateVertexScale(1f, 1f)
                return
            }
            val side = minOf(configuredWidth, configuredHeight).toFloat()
            cropRect[0] = (1f - side / configuredWidth.toFloat()) / 2f
            cropRect[1] = 1f - side / configuredHeight.toFloat()
            cropRect[2] = side / configuredWidth.toFloat()
            cropRect[3] = side / configuredHeight.toFloat()
            updateVertexScale(1f, 1f)
            if (firstFrameLogged && !cropLogged) {
                cropLogged = true
                Log.i(
                    TAG,
                    "preview $logLabel configured=${configuredWidth}x${configuredHeight} viewport=${surfaceWidth}x${surfaceHeight} crop=[${cropRect[0]},${cropRect[1]},${cropRect[2]},${cropRect[3]}] matrix=${textureMatrixSummary(textureMatrix)}",
                )
            }
        }

        private fun updateVertexScale(scaleX: Float, scaleY: Float) {
            vertexBuffer.position(0)
            vertexBuffer.put(
                floatArrayOf(
                    -scaleX, -scaleY,
                    scaleX, -scaleY,
                    -scaleX, scaleY,
                    scaleX, scaleY,
                ),
            )
            vertexBuffer.position(0)
        }

        /**
         * 方形诊断模式按给定纹理坐标铺满 viewport，与 NV21 方形基准直接对照。
         */
        private fun updateDebugTextureCropFill() {
            cropRect[0] = debugTextureCrop[0]
            cropRect[1] = debugTextureCrop[1]
            cropRect[2] = debugTextureCrop[2]
            cropRect[3] = debugTextureCrop[3]
            updateVertexScale(1f, 1f)
            if (firstFrameLogged && !cropLogged) {
                cropLogged = true
                Log.i(
                    TAG,
                    "preview debug texture crop fill viewport=${surfaceWidth}x${surfaceHeight} crop=[${cropRect[0]},${cropRect[1]},${cropRect[2]},${cropRect[3]}] matrix=${textureMatrixSummary(textureMatrix)}",
                )
            }
        }

        /**
         * 正式预览与算法链统一采样 NV21 中心方形 ROI。
         */
        private fun updateValidatedSurfaceCrop() {
            val configuredWidth = RokidFrameSource.getSurfaceCameraWidth()
            val configuredHeight = RokidFrameSource.getSurfaceCameraHeight()
            val appliedPreviewFps = RokidFrameSource.getSurfaceAppliedPreviewFps()
            val videoStabilizationEnabled = RokidFrameSource.isSurfaceVideoStabilizationEnabled()
            if (configuredWidth <= 0 || configuredHeight <= 0) {
                cropRect[0] = 0f
                cropRect[1] = 0f
                cropRect[2] = 1f
                cropRect[3] = 1f
                return
            }
            val latestFrameSize = RokidFrameSource.getLatestFrameSize()
            val latestFrameWidth = latestFrameSize?.width ?: 0
            val latestFrameHeight = latestFrameSize?.height ?: 0
            val axisSwappedFromMatrix = isAxisSwapped(textureMatrix)
            if (latestFrameWidth <= 0 || latestFrameHeight <= 0) {
                cropRect[0] = 0f
                cropRect[1] = 0f
                cropRect[2] = 1f
                cropRect[3] = 1f
                return
            }
            val squareRect = SharedCameraViewportPolicy.calculateValidatedNv21SquareCropRect(
                latestFrameWidth,
                latestFrameHeight,
            )
            val mapping = RokidFrameSource.mapFrameCropToSurfaceTexture(
                surfaceWidth = configuredWidth,
                surfaceHeight = configuredHeight,
                frameWidth = latestFrameWidth,
                frameHeight = latestFrameHeight,
                frameCrop = RokidFrameSource.NormalizedCropRect(
                    left = squareRect.left.toFloat() / latestFrameWidth.toFloat(),
                    top = squareRect.top.toFloat() / latestFrameHeight.toFloat(),
                    width = squareRect.width().toFloat() / latestFrameWidth.toFloat(),
                    height = squareRect.height().toFloat() / latestFrameHeight.toFloat(),
                ),
                matrixSwapped = axisSwappedFromMatrix,
            ) ?: return
            cropRect[0] = mapping.textureCrop.left
            cropRect[1] = mapping.textureCrop.top
            cropRect[2] = mapping.textureCrop.width
            cropRect[3] = mapping.textureCrop.height
            if (firstFrameLogged && !cropLogged) {
                cropLogged = true
                Log.i(
                    TAG,
                    "preview validated center roi configured=${configuredWidth}x${configuredHeight} viewport=${surfaceWidth}x${surfaceHeight} latestFrame=${latestFrameWidth}x${latestFrameHeight} mode=${mapping.mode} matrixSwapped=$axisSwappedFromMatrix appliedPreviewFps=$appliedPreviewFps videoStabilizationEnabled=$videoStabilizationEnabled roi=$squareRect textureCrop=${mapping.textureCrop} matrix=${textureMatrixSummary(textureMatrix)}",
                )
            }
        }

        private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            return GLES20.glCreateProgram().also { programId ->
                GLES20.glAttachShader(programId, vertexShader)
                GLES20.glAttachShader(programId, fragmentShader)
                GLES20.glLinkProgram(programId)
            }
        }

        private fun compileShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }

        companion object {
            private const val TAG = "RokidCameraPreview"
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                uniform mat4 uTexMatrix;
                uniform vec4 uCropRect;
                void main() {
                    gl_Position = aPosition;
                    vec2 transformedTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                    vTexCoord = vec2(
                        uCropRect.x + transformedTexCoord.x * uCropRect.z,
                        uCropRect.y + transformedTexCoord.y * uCropRect.w
                    );
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uOesTexture;
                void main() {
                    gl_FragColor = texture2D(uOesTexture, vTexCoord);
                }
            """

            private fun allocateBuffer(data: FloatArray): FloatBuffer {
                return ByteBuffer.allocateDirect(data.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(data)
                        position(0)
                    }
            }

            private fun isAxisSwapped(matrix: FloatArray): Boolean {
                if (matrix.size < 16) {
                    return false
                }
                val xAxisX = matrix[0]
                val xAxisY = matrix[1]
                val yAxisX = matrix[4]
                val yAxisY = matrix[5]
                return kotlin.math.abs(xAxisY) > kotlin.math.abs(xAxisX) ||
                    kotlin.math.abs(yAxisX) > kotlin.math.abs(yAxisY)
            }

            private fun textureMatrixSummary(matrix: FloatArray): String {
                if (matrix.size < 16) {
                    return "invalid"
                }
                return "[${matrix[0]},${matrix[1]},${matrix[4]},${matrix[5]},${matrix[12]},${matrix[13]}]"
            }

        }
    }
}
