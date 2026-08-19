package com.rokid.glass.component

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class SurfaceStartGate {
    private var ready = false
    private var pendingStart: (() -> Unit)? = null

    fun runWhenReady(start: () -> Unit) {
        if (ready) {
            start()
        } else {
            pendingStart = start
        }
    }

    fun markReady() {
        ready = true
        pendingStart?.also { pendingStart = null }?.invoke()
    }

    fun markUnavailable() {
        ready = false
        pendingStart = null
    }
}

/**
 * 纯 Demo 版 Surface 共享预览。
 *
 * 该 View 刻意不复用业务侧 RokidFrameSource / ROI 裁剪逻辑，
 * 用于对照 Glass3 SDK demo 的原始 Surface 纹理输出行为。
 */
class RokidDemoSurfacePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = DemoSurfaceRenderer(frameAvailableCallback = { requestRender() })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun startDemoPreview(onReady: (Boolean) -> Unit = {}) {
        onResume()
        queueEvent {
            renderer.startSurfaceShare(onReady)
        }
    }

    fun setCenterSquareCropEnabled(enabled: Boolean) {
        renderer.setCenterSquareCropEnabled(enabled)
        requestRender()
    }

    fun setPreviewConfig(
        width: Int,
        height: Int,
        targetFps: Int,
        zoomLevel: Int,
    ) {
        renderer.setPreviewConfig(width, height, targetFps, zoomLevel)
    }

    fun setCustomTextureCrop(left: Float, top: Float, width: Float, height: Float) {
        renderer.setCustomTextureCrop(left, top, width, height)
        requestRender()
    }

    fun cameraSize(): Pair<Int, Int>? = renderer.cameraSize()

    fun stopDemoPreview() {
        val latch = CountDownLatch(1)
        queueEvent {
            try {
                renderer.releaseSurfaceShare()
                renderer.markSurfaceUnavailable()
            } finally {
                latch.countDown()
            }
        }
        val released = runCatching {
            latch.await(RELEASE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!released) {
            Log.w(TAG, "timeout waiting demo surface release before pause")
        }
        onPause()
    }

    fun diagnosticsText(): String = renderer.diagnosticsText()

    override fun onDetachedFromWindow() {
        stopDemoPreview()
        super.onDetachedFromWindow()
    }

    private class DemoSurfaceRenderer(
        private val frameAvailableCallback: () -> Unit,
    ) : Renderer {
        private val vertexBuffer = allocateBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )
        private val texCoordBuffer = allocateBuffer(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            ),
        )

        private val helper = CameraShareHelper()
        private val startGate = SurfaceStartGate()
        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureHandle = 0
        private var matrixHandle = 0
        private var cropRectHandle = 0
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var cameraWidth = 0
        private var cameraHeight = 0
        private var appliedPreviewFps = 0
        private var videoStabilizationEnabled = false
        private var zoomLevel = 0
        private var frameCount = 0L
        private var firstDrawLogged = false
        private var firstFrameLogged = false
        private var startCallback: ((Boolean) -> Unit)? = null
        private var surfaceActive = false

        @Volatile
        private var previewConfig = PreviewConfig()

        @Volatile
        private var customTextureCrop: TextureCrop? = null

        @Volatile
        private var matrixSummary = "-"

        @Volatile
        private var centerSquareCropEnabled = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
            cropRectHandle = GLES20.glGetUniformLocation(program, "uCropRect")
            Log.i(TAG, "demo gl surface created program=$program")
            startGate.markReady()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            GLES20.glViewport(0, 0, width, height)
            Log.i(TAG, "demo gl surface changed width=$width height=$height")
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!helper.isSurfaceActive()) return
            val textureId = helper.getTextureId()
            if (textureId <= 0) {
                return
            }

            helper.updateTexture()
            val matrix = helper.getTransformMatrix()
            matrixSummary = textureMatrixSummary(matrix)

            if (!firstDrawLogged) {
                firstDrawLogged = true
                Log.i(
                    TAG,
                    "first demo preview draw textureId=$textureId roi=${roiSummary()} matrix=$matrixSummary",
                )
            }

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)
            val customCrop = customTextureCrop
            if (customCrop != null) {
                GLES20.glUniform4f(
                    cropRectHandle,
                    customCrop.left,
                    customCrop.top,
                    customCrop.width,
                    customCrop.height,
                )
            } else if (centerSquareCropEnabled) {
                GLES20.glUniform4f(cropRectHandle, CENTER_CROP_LEFT, 0f, CENTER_CROP_WIDTH, 1f)
            } else {
                GLES20.glUniform4f(cropRectHandle, 0f, 0f, 1f, 1f)
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frameCount++

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun startSurfaceShare(onReady: (Boolean) -> Unit) {
            startGate.runWhenReady { startSurfaceShareWhenReady(onReady) }
        }

        private fun startSurfaceShareWhenReady(onReady: (Boolean) -> Unit) {
            if (!GlassSdk.isReady()) {
                Log.e(TAG, "GlassSdk not ready for demo surface")
                onReady(false)
                return
            }
            if (helper.isSurfaceActive()) {
                onReady(true)
                return
            }
            startCallback = onReady
            surfaceActive = true
            firstFrameLogged = false
            firstDrawLogged = false
            frameCount = 0L
            cameraWidth = 0
            cameraHeight = 0
            appliedPreviewFps = 0
            videoStabilizationEnabled = false
            zoomLevel = 0
            matrixSummary = "-"

            val requestedConfig = previewConfig
            val config = CameraShareConfig(
                previewWidth = requestedConfig.width,
                previewHeight = requestedConfig.height,
                previewTargetFps = requestedConfig.targetFps,
                enableVideoStabilization = false,
                zoomLevel = requestedConfig.zoomLevel,
            )
            helper.initSurfaceWithConfig(config, object : CameraShareHelper.SurfaceCallback {
                    override fun onCameraOpened(width: Int, height: Int) {
                        cameraWidth = width
                        cameraHeight = height
                        Log.i(TAG, "Surface camera opened: ${width}x${height}")
                        dispatchReady(true)
                    }

                    override fun onFrameAvailable() {
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.i(TAG, "demo shared surface first frame available")
                        }
                        frameAvailableCallback.invoke()
                    }

                    override fun onCameraClosed() {
                        Log.i(TAG, "Surface camera closed")
                        surfaceActive = false
                    }

                    override fun onError(code: Int, msg: String) {
                        Log.e(TAG, "Surface error: code=$code, msg=$msg")
                        surfaceActive = false
                        dispatchReady(false)
                    }

                    override fun onSurfaceShareConfigChanged(
                        width: Int,
                        height: Int,
                        appliedPreviewFps: Int,
                        videoStabilizationEnabled: Boolean,
                    ) {
                        cameraWidth = width
                        cameraHeight = height
                        this@DemoSurfaceRenderer.appliedPreviewFps = appliedPreviewFps
                        this@DemoSurfaceRenderer.videoStabilizationEnabled = videoStabilizationEnabled
                        Log.i(
                            TAG,
                            "Config changed: ${width}x${height}, fps=$appliedPreviewFps, eis=$videoStabilizationEnabled",
                        )
                    }

                    override fun onZoomLevelChanged(zoomLevel: Int) {
                        this@DemoSurfaceRenderer.zoomLevel = zoomLevel
                        Log.i(TAG, "Zoom level changed: $zoomLevel")
                    }
                })
        }

        fun setCenterSquareCropEnabled(enabled: Boolean) {
            centerSquareCropEnabled = enabled
        }

        fun setPreviewConfig(width: Int, height: Int, targetFps: Int, zoomLevel: Int) {
            check(!surfaceActive) { "Preview config must be set before starting the Surface session" }
            previewConfig = PreviewConfig(width, height, targetFps, zoomLevel)
        }

        fun setCustomTextureCrop(left: Float, top: Float, width: Float, height: Float) {
            customTextureCrop = TextureCrop(left, top, width, height)
            firstDrawLogged = false
        }

        fun cameraSize(): Pair<Int, Int>? {
            return if (cameraWidth > 0 && cameraHeight > 0) cameraWidth to cameraHeight else null
        }

        fun releaseSurfaceShare() {
            startCallback = null
            surfaceActive = false
            runCatching {
                if (helper.isSurfaceActive()) {
                    helper.releaseSurface()
                }
            }.onFailure { error ->
                Log.e(TAG, "releaseSurface on GL thread failed", error)
            }
        }

        fun release() {
            releaseSurfaceShare()
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        fun diagnosticsText(): String {
            return "Demo Surface\n" +
                "View: ${viewportWidth}x${viewportHeight}  Surface: ${cameraWidth}x${cameraHeight}\n" +
                "FPS: $appliedPreviewFps  EIS: $videoStabilizationEnabled  Zoom: $zoomLevel\n" +
                "Frames: $frameCount  Active: $surfaceActive\n" +
                "ROI: ${roiSummary()}  Matrix: $matrixSummary"
        }

        fun markSurfaceUnavailable() {
            startGate.markUnavailable()
        }

        private fun roiSummary(): String {
            val crop = customTextureCrop
            return when {
                crop != null -> "custom [${crop.left},${crop.top},${crop.width},${crop.height}]"
                centerSquareCropEnabled -> "center 1080x1080"
                else -> "raw"
            }
        }

        private fun dispatchReady(success: Boolean) {
            val callback = startCallback ?: return
            startCallback = null
            callback(success)
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
    }

    companion object {
        private const val TAG = "RokidDemoSurfacePreview"
        private const val RELEASE_WAIT_TIMEOUT_MS = 500L
        private const val CENTER_CROP_LEFT = 420f / 1920f
        private const val CENTER_CROP_WIDTH = 1080f / 1920f

        private data class PreviewConfig(
            val width: Int = 1920,
            val height: Int = 1080,
            val targetFps: Int = 15,
            val zoomLevel: Int = 1,
        )

        private data class TextureCrop(
            val left: Float,
            val top: Float,
            val width: Float,
            val height: Float,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMatrix;
            uniform vec4 uCropRect;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 transformedTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord = vec2(
                    uCropRect.x + transformedTexCoord.x * uCropRect.z,
                    uCropRect.y + transformedTexCoord.y * uCropRect.w
                );
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
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

        private fun textureMatrixSummary(matrix: FloatArray): String {
            if (matrix.size < 16) {
                return "invalid"
            }
            return "[${matrix[0]}, ${matrix[1]}, ${matrix[4]}, ${matrix[5]}, ${matrix[12]}, ${matrix[13]}]"
        }
    }
}
