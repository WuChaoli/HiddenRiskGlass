package com.rokid.glass.component

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.rokid.glass.camera.RokidFrameSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 统一显示 RokidFrameSource 的中心裁剪 NV21 画面。
 * 预览只拉取最新帧并走 GPU YUV 渲染，避免实时 JPEG/Bitmap 转换。
 */
class RokidCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val PREVIEW_PULL_INTERVAL_MS = 66L
    }

    private val renderer = Nv21Renderer()
    private val framePullExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val latestSubmittedTimestamp = AtomicLong(-1L)

    @Volatile
    private var previewStarted = false

    @Volatile
    private var framePullTask: ScheduledFuture<*>? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun startPreview(onReady: (Boolean) -> Unit = {}) {
        if (previewStarted) {
            onReady(true)
            return
        }
        previewStarted = true
        latestSubmittedTimestamp.set(-1L)
        onResume()
        framePullTask = framePullExecutor.scheduleWithFixedDelay(
            {
                if (!previewStarted) {
                    return@scheduleWithFixedDelay
                }
                val frame = RokidFrameSource.copyLatestCroppedFrame() ?: return@scheduleWithFixedDelay
                if (latestSubmittedTimestamp.get() == frame.timestamp) {
                    return@scheduleWithFixedDelay
                }
                latestSubmittedTimestamp.set(frame.timestamp)
                queueEvent {
                    renderer.updateFrame(frame.data, frame.width, frame.height)
                }
                requestRender()
            },
            0L,
            PREVIEW_PULL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        onReady(true)
    }

    fun stopPreview() {
        if (!previewStarted) {
            queueEvent { renderer.clearFrame() }
            requestRender()
            return
        }
        previewStarted = false
        latestSubmittedTimestamp.set(-1L)
        framePullTask?.cancel(true)
        framePullTask = null
        queueEvent { renderer.clearFrame() }
        requestRender()
        onPause()
    }

    override fun onDetachedFromWindow() {
        stopPreview()
        framePullExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private class Nv21Renderer : GLSurfaceView.Renderer {
        private val vertexBuffer: FloatBuffer = allocateBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )
        )
        private val texCoordBuffer: FloatBuffer = allocateBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
            )
        )

        private val frameLock = Any()
        private val textures = IntArray(2)

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var yTextureHandle = 0
        private var uvTextureHandle = 0

        private var pendingFrameData: ByteArray? = null
        private var pendingWidth = 0
        private var pendingHeight = 0

        private var activeWidth = 0
        private var activeHeight = 0
        private var hasFrame = false
        private var texturesAllocated = false

        fun updateFrame(data: ByteArray, width: Int, height: Int) {
            synchronized(frameLock) {
                pendingFrameData = data
                pendingWidth = width
                pendingHeight = height
            }
        }

        fun clearFrame() {
            synchronized(frameLock) {
                pendingFrameData = null
                pendingWidth = 0
                pendingHeight = 0
            }
            hasFrame = false
            activeWidth = 0
            activeHeight = 0
            texturesAllocated = false
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            yTextureHandle = GLES20.glGetUniformLocation(program, "uYTexture")
            uvTextureHandle = GLES20.glGetUniformLocation(program, "uVUTexture")
            GLES20.glGenTextures(2, textures, 0)
            configureTexture(textures[0])
            configureTexture(textures[1])
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            uploadPendingFrameIfNeeded()
            if (!hasFrame || activeWidth <= 0 || activeHeight <= 0 || program == 0) {
                return
            }

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glUniform1i(yTextureHandle, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glUniform1i(uvTextureHandle, 1)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        private fun uploadPendingFrameIfNeeded() {
            val frameData: ByteArray
            val width: Int
            val height: Int
            synchronized(frameLock) {
                frameData = pendingFrameData ?: return
                width = pendingWidth
                height = pendingHeight
                pendingFrameData = null
            }

            if (width <= 0 || height <= 0) {
                return
            }

            val ySize = width * height
            val uvSize = width * height / 2
            if (frameData.size < ySize + uvSize) {
                return
            }

            val yBuffer = ByteBuffer.wrap(frameData, 0, ySize)
            val uvBuffer = ByteBuffer.wrap(frameData, ySize, uvSize)
            val sizeChanged = !texturesAllocated || width != activeWidth || height != activeHeight

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            if (sizeChanged) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
            }
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                yBuffer,
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            if (sizeChanged) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE_ALPHA,
                    width / 2,
                    height / 2,
                    0,
                    GLES20.GL_LUMINANCE_ALPHA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
            }
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width / 2,
                height / 2,
                GLES20.GL_LUMINANCE_ALPHA,
                GLES20.GL_UNSIGNED_BYTE,
                uvBuffer,
            )

            activeWidth = width
            activeHeight = height
            hasFrame = true
            texturesAllocated = true
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

        private fun configureTexture(textureId: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        companion object {
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uYTexture;
                uniform sampler2D uVUTexture;
                void main() {
                    float y = texture2D(uYTexture, vTexCoord).r;
                    vec4 vu = texture2D(uVUTexture, vTexCoord);
                    float v = vu.r - 0.5;
                    float u = vu.a - 0.5;
                    float yy = 1.1643 * (y - 0.0625);
                    float r = yy + 1.5958 * v;
                    float g = yy - 0.39173 * u - 0.81290 * v;
                    float b = yy + 2.017 * u;
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
        }
    }
}
