package com.rokid.glass.component

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.rokid.glass.camera.RokidFrameSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 用于显示 Rokid SDK 共享预览纹理的轻量预览控件。
 */
class RokidCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = OesRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun startPreview(onReady: (Boolean) -> Unit = {}) {
        RokidFrameSource.startPreview(
            onReady = onReady,
            onFrameAvailable = { requestRender() },
        )
    }

    fun stopPreview() {
        RokidFrameSource.stopPreview()
    }

    private class OesRenderer : GLSurfaceView.Renderer {

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
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            )
        )

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var matrixHandle = 0
        private var textureHandle = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val textureId = RokidFrameSource.getTextureId()
            if (textureId == -1 || program == 0) {
                return
            }

            RokidFrameSource.updatePreviewTexture()
            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(
                matrixHandle,
                1,
                false,
                RokidFrameSource.getPreviewTransformMatrix(),
                0,
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
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
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
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
        }
    }
}
