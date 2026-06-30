package com.rokid.glass.component

import android.content.Context
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 复用 Glass3 SDK Demo NV21 导出及 GL 渲染方式的诊断预览。
 *
 * 该组件只服务于 Surface / NV21 原始视野对比页，不参与业务采帧和识别链路。
 */
class RokidDemoNv21PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = Nv21Renderer()
    private var helper: CameraShareHelper? = null
    private var frameCount = 0L
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var appliedPreviewFps = 0
    private var videoStabilizationEnabled = false
    private var zoomLevel = 0
    private var active = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun startDemoPreview(onReady: (Boolean) -> Unit = {}) {
        if (active) {
            onReady(true)
            return
        }
        if (!GlassSdk.isReady()) {
            Log.e(TAG, "GlassSdk not ready for demo nv21")
            onReady(false)
            return
        }
        onResume()
        frameCount = 0L
        cameraWidth = 0
        cameraHeight = 0
        appliedPreviewFps = 0
        videoStabilizationEnabled = false
        zoomLevel = 0
        active = true
        var readyDispatched = false
        val config = CameraShareConfig(
            previewWidth = PREVIEW_WIDTH,
            previewHeight = PREVIEW_HEIGHT,
            previewTargetFps = PREVIEW_FPS,
            enableVideoStabilization = false,
            zoomLevel = PREVIEW_ZOOM,
        )
        helper = CameraShareHelper().apply {
            initNv21ExportWithConfig(
                false,
                config,
                object : CameraShareHelper.Nv21Callback {
                    override fun onCameraOpened(width: Int, height: Int) {
                        cameraWidth = width
                        cameraHeight = height
                        Log.i(TAG, "NV21 camera opened: ${width}x$height")
                        if (!readyDispatched) {
                            readyDispatched = true
                            onReady(true)
                        }
                    }

                    override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                        frameCount++
                        renderer.setPreviewData(nv21, width, height)
                        requestRender()
                    }

                    override fun onCameraClosed() {
                        active = false
                        Log.i(TAG, "NV21 camera closed")
                    }

                    override fun onError(code: Int, msg: String) {
                        active = false
                        Log.e(TAG, "NV21 error: code=$code, msg=$msg")
                        if (!readyDispatched) {
                            readyDispatched = true
                            onReady(false)
                        }
                    }

                    override fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {
                        cameraWidth = width
                        cameraHeight = height
                        this@RokidDemoNv21PreviewView.appliedPreviewFps = appliedPreviewFps
                        Log.i(TAG, "NV21 config changed: ${width}x$height, fps=$appliedPreviewFps")
                    }

                    override fun onNv21ExportRuntimeParamsChanged(
                        appliedPreviewFps: Int,
                        videoStabilizationEnabled: Boolean,
                    ) {
                        this@RokidDemoNv21PreviewView.appliedPreviewFps = appliedPreviewFps
                        this@RokidDemoNv21PreviewView.videoStabilizationEnabled = videoStabilizationEnabled
                    }

                    override fun onZoomLevelChanged(zoomLevel: Int) {
                        this@RokidDemoNv21PreviewView.zoomLevel = zoomLevel
                    }
                },
            )
        }
    }

    fun stopDemoPreview() {
        if (active) {
            runCatching { helper?.releaseNv21Export() }
                .onFailure { error -> Log.e(TAG, "releaseNv21Export failed", error) }
        }
        helper = null
        active = false
        renderer.releasePreview()
        onPause()
    }

    fun diagnosticsText(): String {
        return "Demo NV21: ${cameraWidth}x$cameraHeight  FPS: $appliedPreviewFps  " +
            "EIS: $videoStabilizationEnabled  Zoom: $zoomLevel  Frames: $frameCount  Active: $active"
    }

    override fun onDetachedFromWindow() {
        stopDemoPreview()
        super.onDetachedFromWindow()
    }

    private class Nv21Renderer : Renderer {
        private val vertexBuffer = allocateBuffer(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
        )
        private val textureBuffer = allocateBuffer(
            floatArrayOf(
                CENTER_CROP_LEFT, 1f,
                CENTER_CROP_RIGHT, 1f,
                CENTER_CROP_LEFT, 0f,
                CENTER_CROP_RIGHT, 0f,
            ),
        )

        private var program = 0
        private var positionHandle = 0
        private var texturePositionHandle = 0
        private var yTextureHandle = 0
        private var uvTextureHandle = 0
        private val textureIds = IntArray(2)

        @Volatile
        private var yBuffer: ByteBuffer? = null

        @Volatile
        private var uvBuffer: ByteBuffer? = null

        @Volatile
        private var width = 0

        @Volatile
        private var height = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = createProgram(VERTEX_SHADER, NV21_FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "av_Position")
            texturePositionHandle = GLES20.glGetAttribLocation(program, "af_Position")
            yTextureHandle = GLES20.glGetUniformLocation(program, "yTexture")
            uvTextureHandle = GLES20.glGetUniformLocation(program, "uvTexture")
            GLES20.glGenTextures(2, textureIds, 0)
            textureIds.forEach { textureId ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val y = yBuffer ?: return
            val uv = uvBuffer ?: return
            val frameWidth = width
            val frameHeight = height
            if (frameWidth <= 0 || frameHeight <= 0) return

            GLES20.glUseProgram(program)
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            textureBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texturePositionHandle)
            GLES20.glVertexAttribPointer(texturePositionHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            y.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                frameWidth,
                frameHeight,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                y,
            )
            GLES20.glUniform1i(yTextureHandle, 0)

            uv.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[1])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE_ALPHA,
                frameWidth / 2,
                frameHeight / 2,
                0,
                GLES20.GL_LUMINANCE_ALPHA,
                GLES20.GL_UNSIGNED_BYTE,
                uv,
            )
            GLES20.glUniform1i(uvTextureHandle, 1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texturePositionHandle)
        }

        @Synchronized
        fun setPreviewData(data: ByteArray, width: Int, height: Int) {
            val ySize = width * height
            val uvSize = ySize / 2
            if (data.size < ySize + uvSize) return
            val newY = yBuffer?.takeIf { it.capacity() == ySize } ?: ByteBuffer.allocateDirect(ySize)
            val newUv = uvBuffer?.takeIf { it.capacity() == uvSize } ?: ByteBuffer.allocateDirect(uvSize)
            newY.position(0)
            newY.put(data, 0, ySize)
            newY.position(0)
            newUv.position(0)
            newUv.put(data, ySize, uvSize)
            newUv.position(0)
            yBuffer = newY
            uvBuffer = newUv
            this.width = width
            this.height = height
        }

        @Synchronized
        fun releasePreview() {
            yBuffer = null
            uvBuffer = null
            width = 0
            height = 0
        }
    }

    companion object {
        private const val TAG = "RokidDemoNv21Preview"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val PREVIEW_FPS = 15
        private const val PREVIEW_ZOOM = 1
        private const val CENTER_CROP_LEFT = 420f / 1920f
        private const val CENTER_CROP_RIGHT = 1500f / 1920f
        private const val VERTEX_SHADER = """
            attribute vec4 av_Position;
            attribute vec2 af_Position;
            varying vec2 v_texPo;
            void main() {
                v_texPo = af_Position;
                gl_Position = av_Position;
            }
        """
        private const val NV21_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D yTexture;
            uniform sampler2D uvTexture;
            varying highp vec2 v_texPo;
            void main() {
                float y = texture2D(yTexture, v_texPo).r;
                float u = texture2D(uvTexture, v_texPo).a - 0.5;
                float v = texture2D(uvTexture, v_texPo).r - 0.5;
                float r = y + 1.57481 * v;
                float g = y - 0.18732 * u - 0.46813 * v;
                float b = y + 1.8556 * u;
                gl_FragColor = vec4(r, g, b, 1.0);
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

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            return GLES20.glCreateProgram().also { programId ->
                GLES20.glAttachShader(programId, vertexShader)
                GLES20.glAttachShader(programId, fragmentShader)
                GLES20.glLinkProgram(programId)
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
            }
        }
    }
}
