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

/**
 * 统一显示与上传/推理同一取景 ROI 的共享相机预览。
 * 预览链路使用 SDK Surface 共享，避免走 NV21 -> CPU -> GL 的重复搬运。
 */
class RokidCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

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
        if (!previewStarted) {
            runRendererStopAndWait()
            return
        }
        Log.i(TAG, "stopPreview requested")
        previewStarted = false
        healthCheckTask?.cancel(true)
        healthCheckTask = null
        synchronized(healthLock) {
            previewStartedAtElapsedMs = 0L
            resetHealthStateLocked()
        }
        runRendererStopAndWait()
        renderMode = RENDERMODE_WHEN_DIRTY
        onPause()
    }

    fun isPreviewStarted(): Boolean = previewStarted

    override fun onDetachedFromWindow() {
        stopPreview()
        healthExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun runRendererStopAndWait() {
        val releaseLatch = CountDownLatch(1)
        queueEvent {
            try {
                renderer.stopSurfacePreview()
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

            var frameAdvanced = false
            runCatching {
                RokidFrameSource.updateSurfaceTexture()
                val latestMatrix = RokidFrameSource.getSurfaceTransformMatrix()
                if (latestMatrix.size == 16) {
                    System.arraycopy(latestMatrix, 0, textureMatrix, 0, 16)
                } else {
                    Matrix.setIdentityM(textureMatrix, 0)
                }
                updateCropRect()
                hasFrame = true
                frameAdvanced = true
            }.onFailure { error ->
                if (framePending || !hasFrame) {
                    Log.w(TAG, "update shared surface texture failed", error)
                }
            }
            framePending = false
            if (frameAdvanced) {
                frameDrawnCallback.invoke()
            }

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

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun startSurfacePreview(onReady: (Boolean) -> Unit) {
            stopSurfacePreview()
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

        private fun performStartSurfacePreview(onReady: (Boolean) -> Unit) {
            val readyDispatched = AtomicBoolean(false)
            val started = RokidFrameSource.startSurfacePreview(
                object : CameraShareHelper.SurfaceCallback {
                    override fun onCameraOpened(width: Int, height: Int) {
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
                        Log.i(TAG, "surface preview camera closed")
                        surfaceActive = false
                        framePending = false
                        hasFrame = false
                    }

                    override fun onError(code: Int, msg: String) {
                        surfaceActive = false
                        framePending = false
                        hasFrame = false
                        surfaceErrorCallback(code, msg)
                        if (readyDispatched.compareAndSet(false, true)) {
                            onReady(false)
                        }
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

        fun stopSurfacePreview() {
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
            RokidFrameSource.stopSurfacePreview()
        }

        private fun isGlSurfaceReady(): Boolean {
            return program != 0 && surfaceWidth > 0 && surfaceHeight > 0
        }

        private fun updateCropRect() {
            val sourceWidth = RokidFrameSource.getSurfaceCameraWidth()
            val sourceHeight = RokidFrameSource.getSurfaceCameraHeight()
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                cropRect[0] = 0f
                cropRect[1] = 0f
                cropRect[2] = 1f
                cropRect[3] = 1f
                return
            }
            val latestFrameSize = RokidFrameSource.getLatestFrameSize()
            val latestFrameWidth = latestFrameSize?.width ?: 0
            val latestFrameHeight = latestFrameSize?.height ?: 0
            val axisSwappedFromFrameSize =
                latestFrameWidth > 0 &&
                    latestFrameHeight > 0 &&
                    (sourceWidth > sourceHeight) != (latestFrameWidth > latestFrameHeight)
            val axisSwapped = axisSwappedFromFrameSize || isAxisSwapped(textureMatrix)
            val previewWidth = if (axisSwapped) sourceHeight else sourceWidth
            val previewHeight = if (axisSwapped) sourceWidth else sourceHeight
            val squareRect = RokidFrameSource.calculateSquareCropRect(previewWidth, previewHeight)
            cropRect[0] = squareRect.left.toFloat() / previewWidth.toFloat()
            cropRect[1] = squareRect.top.toFloat() / previewHeight.toFloat()
            cropRect[2] = squareRect.width().toFloat() / previewWidth.toFloat()
            cropRect[3] = squareRect.height().toFloat() / previewHeight.toFloat()
            if (firstFrameLogged && !cropLogged) {
                cropLogged = true
                Log.i(
                    TAG,
                    "preview crop updated source=${sourceWidth}x${sourceHeight} latestFrame=${latestFrameWidth}x${latestFrameHeight} preview=${previewWidth}x${previewHeight} swapped=$axisSwapped crop=$squareRect matrix=${textureMatrixSummary(textureMatrix)}",
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
