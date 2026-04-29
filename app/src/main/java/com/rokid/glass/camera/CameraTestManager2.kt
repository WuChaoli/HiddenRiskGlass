package com.rokid.glass.camera


import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

class CameraTestManager2(private val context: Context) {

    companion object {
        private const val TAG = "CameraTestManager"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isInitialized = false
    private var currentCameraId: String? = null

    // 回调接口
    interface ICameraPreviewCallback {
        fun onPreviewFrame(data: ByteArray, width: Int, height: Int)
    }
    private var previewCallback: ICameraPreviewCallback? = null

    fun setPreviewCallback(callback: ICameraPreviewCallback) {
        previewCallback = callback
    }

    fun initializeCamera(callback: (Boolean) -> Unit) {
        if (isInitialized) {
            callback(true)
            return
        }

        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.e(TAG, "未找到后置相机")
                callback(false)
                return
            }
            currentCameraId = cameraId

            // 检查权限
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.CAMERA
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "没有 CAMERA 权限")
                callback(false)
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        val previewSize = Size(1280, 720)

                        // TextureView 预览可选
                        val surfaceTexture = SurfaceTexture(10)
                        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                        previewSurface = Surface(surfaceTexture)

                        // ImageReader 用于 NV21 预览数据回调
                        imageReader = ImageReader.newInstance(
                            previewSize.width,
                            previewSize.height,
                            ImageFormat.YUV_420_888,
                            2
                        )

                        imageReader?.setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                            val plane = image.planes[0]
                            val buffer: ByteBuffer = plane.buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            previewCallback?.onPreviewFrame(data, image.width, image.height)

                            image.close()
                        }, backgroundHandler)

                        val surfaces = listOfNotNull(previewSurface, imageReader?.surface)

                        device.createCaptureSession(
                            surfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val previewRequest =
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(previewSurface!!)
                                                addTarget(imageReader!!.surface)
                                            }
                                        session.setRepeatingRequest(
                                            previewRequest.build(),
                                            null,
                                            backgroundHandler
                                        )
                                        isInitialized = true
                                        callback(true)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "启动预览失败", e)
                                        callback(false)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    callback(false)
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "初始化失败", e)
                        callback(false)
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    callback(false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    callback(false)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "打开相机失败", e)
            callback(false)
        }
    }

    fun release() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            previewSurface = null
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }
}
