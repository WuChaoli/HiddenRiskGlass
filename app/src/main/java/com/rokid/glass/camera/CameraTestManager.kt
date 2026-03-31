package com.rokid.glass.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class CameraTestManager(private val context: Context) {

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
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * 初始化相机，并把预览画面输出到外部传入的 SurfaceTexture
     */
    fun initialize(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        callback: (Boolean) -> Unit
    ) {
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
            // ✅ 检查权限
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "没有 CAMERA 权限")
                callback(false)
                return
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        // ✅ 选择最佳预览尺寸
                        val previewSize = getBestPreviewSize(manager, cameraId, width, height)
                        Log.e(TAG, "-----preSize----width=${previewSize.width},height=${previewSize.height}")
                        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                        previewSurface = Surface(surfaceTexture)
                        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 2)
                        val surfaces = listOf(previewSurface!!, imageReader!!.surface)
                        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(previewSurface!!)
                                    }
                                    session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler)
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
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "初始化预览失败", e)
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
        } catch (e: SecurityException) {
            Log.e(TAG, "没有相机权限: ${e.message}")
            callback(false)
        } catch (e: Exception) {
            Log.e(TAG, "打开相机失败", e)
            callback(false)
        }
    }

    /**
     * 选择最佳预览尺寸（优先 1920x1080 -> 1280x720 -> 最大可用分辨率）
     */
    private fun getBestPreviewSize(
        manager: CameraManager,
        cameraId: String,
        targetWidth: Int,
        targetHeight: Int
    ): Size {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(targetWidth, targetHeight)
//        previewSizes.forEach {
//            Log.e(TAG, "-----width=${it.width},height=${it.height}")
//        }
        return previewSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: previewSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: previewSizes.maxByOrNull { it.width * it.height }
            ?: Size(targetWidth, targetHeight)
    }

    /**
     * 拍照并保存到文件（自动修正旋转角度）
     */
    fun takePicture(outputFile: File, callback: (Boolean) -> Unit) {
        val device = cameraDevice ?: run {
            Log.e(TAG, "相机未初始化")
            callback(false)
            return
        }
        val session = captureSession ?: run {
            Log.e(TAG, "捕获会话未初始化")
            callback(false)
            return
        }
        val reader = imageReader ?: run {
            Log.e(TAG, "ImageReader 未初始化")
            callback(false)
            return
        }

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val orientation = getJpegOrientation(manager, currentCameraId!!)

            val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                // ✅ 设置照片方向
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
            }

            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    try {
                        // 解码成 Bitmap
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        // 逆时针旋转 90°
                        val matrix = android.graphics.Matrix().apply {
                            postRotate(-90f) // 逆时针 90°
                        }
                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )

                        // 保存到文件
                        FileOutputStream(outputFile).use { fos ->
                            rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos)
                        }

                        bitmap.recycle()
                        rotatedBitmap.recycle()

                        Log.d(TAG, "照片保存成功(已旋转): ${outputFile.absolutePath}")
                        callback(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "保存旋转照片失败", e)
                        callback(false)
                    }
                }
            }, backgroundHandler)

            session.capture(captureRequest.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "拍照失败", e)
            callback(false)
        }
    }

    /**
     * 获取当前照片需要的方向（根据传感器和设备旋转角度计算）
     */
    private fun getJpegOrientation(manager: CameraManager, cameraId: String): Int {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val deviceDegree = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegree) % 360
        } else {
            (sensorOrientation - deviceDegree + 360) % 360
        }
    }

    /**
     * 释放资源
     */
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
