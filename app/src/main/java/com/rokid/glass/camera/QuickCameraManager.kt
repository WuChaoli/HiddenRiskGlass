package com.rokid.glass.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.rokid.glass.MyApplication
import com.rokid.glass.utils.DeviceUtil
import com.rokid.glass.utils.Scopes.mainScope
import com.rokid.glass.utils.call
import com.rokid.security.glass3.open.sdk.uitls.log.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException


object QuickCameraManager {
    private const val TAG = "QuickCameraManager"
    private const val VIDEO_FRAME_RATE = 24
    private const val VIDEO_BIT_RATE = 5_000_000
    private const val GPU_FRAME_FALLBACK_INITIAL_DELAY_MS = 120L
    private const val GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS = 50L
    private const val GPU_FRAME_FALLBACK_TIMEOUT_MS = 1_500L

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private val sessionLock = Any()

    private var imageReader: ImageReader? = null
    private var gpuImageReader: ImageReader? = null
    private var quickCaptureGpuFrameSize: Size? = null
    private var mediaRecorder: MediaRecorder? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var cameraId: String? = null
    private var isInitialized = false
    private var isRecording = false
    private var videoFile: File? = null

    private var previewSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var imgCallback: WeakReference<((File?) -> Unit)>? = null
    private var gpuFrameCallback: WeakReference<((GpuFrame?) -> Unit)>? = null
    private var isQuickCapture = false

    @Volatile
    private var isCameraClosed = true // 初始状态为关闭

    val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            L.d(TAG, "相机可用: $cameraId")
            // 相机可用
        }

        override fun onCameraUnavailable(cameraId: String) {
            // 相机不可用（可能已被关闭或占用）
            isCameraClosed = true
            L.d(TAG, "相机不可用: $cameraId")
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize(size: Size? = null, quickCapture: Boolean = false, onInitialized: (Boolean) -> Unit) {

        val weakCallback = WeakReference(onInitialized)
        this.isQuickCapture = quickCapture
        if (quickCapture && isGpuCaptureWarm()) {
            Log.i(TAG, "initialize reuse warm gpu capture session")
            weakCallback.get()?.invoke(true)
            return
        }
        if (!quickCapture && isInitialized && cameraDevice != null && imageReader != null && !isCameraClosed) {
            Log.i(TAG, "initialize reuse camera state")
            weakCallback.get()?.invoke(true)
            return
        }
        releaseCamera()

        if (!hasCameraPermission()) {
            L.e(TAG, "没有相机权限")
//            onInitialized(false)
            weakCallback.get()?.invoke(false)
            return
        }

        try {
            cameraManager = MyApplication.getContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull()

            if (cameraId == null) {
                L.e(TAG, "没有可用相机")
//                onInitialized(false)
                weakCallback.get()?.invoke(false)
                return
            }
            L.d(TAG, "相机ID: $cameraId")
            startBackgroundThread()

            cameraManager?.openCamera(
                cameraId!!,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        L.d(TAG, "->相机已打开")
                        setProcessingCaptureState(false)
                        isCameraClosed = false // 标记为已打开
                        cameraDevice = camera
                        isInitialized = true
                        setupImageReader(size, quickCapture)
                        if (isQuickCapture) {
                            createGpuFrameSession { success ->
                                if (!success) {
                                    releaseCamera()
                                }
                                weakCallback.get()?.invoke(success)
                            }
                        } else {
                            setupPreviewSurface()
                            createPreviewSession()
                            weakCallback.get()?.invoke(true)
                        }

                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        releaseCamera()
                        weakCallback.get()?.invoke(false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        L.e(TAG, "相机打开错误: $error")
                        releaseCamera()
                        weakCallback.get()?.invoke(false)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            releaseCamera()
            L.e(TAG, "初始化失败: ${e.message}", e)
            weakCallback.get()?.invoke(false)
        }
    }

    private fun createPreviewSession() {
        val previewSurface = this.previewSurface ?: return
        val imageReaderSurface = imageReader?.surface ?: return

        try {
            if (isCameraClosed) {
                return
            }
            cameraDevice?.createCaptureSession(
                listOf(previewSurface, imageReaderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                setProcessingCaptureState(false)
                                Log.d(TAG, "TARGET 1")
                                return
                            }
                            captureSession = session
                            isSessionClosed = false
                            try {
                                val builder = cameraDevice!!.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {

                                    // 添加自动曝光模式
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    // 设置曝光补偿（根据需要调整）
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                                    // 启用自动曝光锁定（可选）
                                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                                    addTarget(previewSurface)
                                }
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            } catch (e: Exception) {
                                L.e(TAG, "设置预览失败", e)
                                setProcessingCaptureState(false)
                            }

                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        L.e(TAG, "预览会话配置失败")
                        setProcessingCaptureState(false)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            L.e(TAG, "创建预览会话失败", e)
            setProcessingCaptureState(false)
        }
    }

    private var isSessionClosed = false
    private var pendingGpuFrameFallback: Runnable? = null
    fun isCameraDoing(): Boolean = isProcessingCapture.value

    @Volatile
    var isProcessingCapture = MutableStateFlow(false)

    fun setProcessingCaptureState(state: Boolean) {
        isProcessingCapture.call(state)
    }


    fun takePicture(callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        imgCallback = weakCallback
        if (isCameraDoing()) {
            Log.d(TAG, "takePicture 正在处理中")
            imgCallback?.get()?.invoke(null)
            return
        }

        setProcessingCaptureState(true)


        if (!isInitialized || cameraDevice == null) {
            Log.d(TAG, "相机未初始化")
            imgCallback?.get()?.invoke(null)
            setProcessingCaptureState(false)
            return
        }


        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG,"延迟900后开始拍照")
            delay(900)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: run {
                    Log.d(TAG, "拍照失败")
                    if (isQuickCapture) {
                        releaseCamera()
                    } else {
                        setProcessingCaptureState(false)
                    }
                    weakCallback.get()?.invoke(null)
                    return@setOnImageAvailableListener
                }

                var outputFile: File? = null
                try {
                    val buffer = image.planes[0].buffer
                    outputFile = saveImageFromBuffer(buffer)
                } catch (e: Exception) {
                    L.e(TAG, "保存图片失败", e)
                } finally {
                    image.close()
                    if (isQuickCapture) {
                        releaseCamera()
                    } else {
                        setProcessingCaptureState(false)
                    }
                    weakCallback.get()?.invoke(outputFile)
                }
            }, backgroundHandler)

            try {
                val surface = imageReader?.surface ?: throw IllegalStateException("ImageReader surface is null")
                val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(270))
                }

                DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")
                cameraDevice!!.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                session.capture(builder.build(), null, backgroundHandler)
                            } catch (e: Exception) {
                                L.e(TAG, "拍照失败", e)
                                imgCallback?.get()?.invoke(null)
                                setProcessingCaptureState(false)
//                            createPreviewSession()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            L.e(TAG, "拍照失败->")
                            imgCallback?.get()?.invoke(null)
                            setProcessingCaptureState(false)
                            createPreviewSession()
                        }
                    },
                    backgroundHandler
                )
            } catch (e: Exception) {
                L.e(TAG, "拍照异常", e)
                imgCallback?.get()?.invoke(null)
                setProcessingCaptureState(false)
            }
        }

    }

    data class GpuFrame(
        val hardwareBuffer: HardwareBuffer,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val previewBitmap: Bitmap? = null,
    )

    fun isGpuCaptureWarm(): Boolean {
        return isInitialized &&
            cameraDevice != null &&
            gpuImageReader != null &&
            captureSession != null &&
            !isSessionClosed &&
            !isCameraClosed
    }

    private fun createGpuFrameSession(onConfigured: ((Boolean) -> Unit)? = null) {
        val reader = gpuImageReader ?: run {
            onConfigured?.invoke(false)
            return
        }
        val surface = reader.surface ?: run {
            onConfigured?.invoke(false)
            return
        }
        val jpegSurface = imageReader?.surface

        try {
            if (isCameraClosed) {
                onConfigured?.invoke(false)
                return
            }
            cameraDevice?.createCaptureSession(
                listOfNotNull(surface, jpegSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                setProcessingCaptureState(false)
                                onConfigured?.invoke(false)
                                return
                            }
                            captureSession = session
                            isSessionClosed = false
                            try {
                                val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                                    addTarget(surface)
                                }
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                Log.i(TAG, "gpu frame session ready")
                                onConfigured?.invoke(true)
                            } catch (error: Exception) {
                                Log.e(TAG, "启动常驻预览帧采集失败", error)
                                setProcessingCaptureState(false)
                                onConfigured?.invoke(false)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "常驻预览帧会话配置失败")
                        setProcessingCaptureState(false)
                        onConfigured?.invoke(false)
                    }
                },
                backgroundHandler,
            )
        } catch (error: Exception) {
            Log.e(TAG, "创建常驻预览帧会话失败", error)
            setProcessingCaptureState(false)
            onConfigured?.invoke(false)
        }
    }

    private fun cancelPendingGpuFrameFallback() {
        pendingGpuFrameFallback?.let { runnable ->
            backgroundHandler?.removeCallbacks(runnable)
        }
        pendingGpuFrameFallback = null
    }

    fun takeGpuFrame(callback: (GpuFrame?) -> Unit) {
        val weakCallback = WeakReference(callback)
        gpuFrameCallback = weakCallback
        if (isCameraDoing()) {
            Log.d(TAG, "takeGpuFrame 正在处理中")
            weakCallback.get()?.invoke(null)
            return
        }

        if (!isInitialized || cameraDevice == null) {
            Log.d(TAG, "相机未初始化")
            weakCallback.get()?.invoke(null)
            return
        }

        val reader = gpuImageReader ?: run {
            Log.d(TAG, "GPU ImageReader 未初始化")
            weakCallback.get()?.invoke(null)
            return
        }
        val surface = reader.surface ?: run {
            Log.d(TAG, "ImageReader surface is null")
            weakCallback.get()?.invoke(null)
            return
        }
        val jpegSurface = imageReader?.surface
        val captureHandler = backgroundHandler ?: run {
            Log.w(TAG, "takeGpuFrame backgroundHandler is null")
            weakCallback.get()?.invoke(null)
            return
        }

        setProcessingCaptureState(true)
        Log.i(TAG, "takeGpuFrame listener armed warm=${isGpuCaptureWarm()}")

        cancelPendingGpuFrameFallback()
        val jpegReader = imageReader
        drainImageReader(reader)
        jpegReader?.let { drainImageReader(it) }

        // 标记本次请求是否已经完成，避免 listener / fallback / 异常路径重复结束。
        val frameAcquired = java.util.concurrent.atomic.AtomicBoolean(false)
        val fallbackDeadlineMs = SystemClock.elapsedRealtime() + GPU_FRAME_FALLBACK_TIMEOUT_MS
        val latestGpuFrame = java.util.concurrent.atomic.AtomicReference<GpuFrame?>(null)
        val latestPreviewBitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        lateinit var fallbackRunnable: Runnable

        fun clearGpuListener() {
            runCatching { reader.setOnImageAvailableListener(null, null) }
                .onFailure { error -> Log.w(TAG, "clear gpu listener failed", error) }
        }

        fun clearListeners() {
            clearGpuListener()
            runCatching { jpegReader?.setOnImageAvailableListener(null, null) }
                .onFailure { error -> Log.w(TAG, "clear jpeg listener failed", error) }
        }

        fun finishRequest(frame: GpuFrame?) {
            if (!frameAcquired.compareAndSet(false, true)) {
                frame?.hardwareBuffer?.close()
                return
            }
            cancelPendingGpuFrameFallback()
            clearListeners()
            setProcessingCaptureState(false)
            Log.i(TAG, "takeGpuFrame listener completed success=${frame != null}")
            weakCallback.get()?.invoke(frame)
        }

        fun tryFinishWithLatest(force: Boolean = false) {
            val gpuFrame = latestGpuFrame.get()
            if (gpuFrame == null) {
                if (force) {
                    finishRequest(null)
                }
                return
            }
            val previewBitmap = latestPreviewBitmap.get()
            if (!force && jpegReader != null && previewBitmap == null) {
                return
            }
            val ownedPreviewBitmap = latestPreviewBitmap.getAndSet(null) ?: previewBitmap
            finishRequest(
                gpuFrame.copy(
                    previewBitmap = ownedPreviewBitmap,
                ),
            )
        }

        fun scheduleFallback(delayMs: Long) {
            if (frameAcquired.get()) {
                return
            }
            cancelPendingGpuFrameFallback()
            pendingGpuFrameFallback = fallbackRunnable
            captureHandler.postDelayed(fallbackRunnable, delayMs)
        }

        fun acceptGpuFrame(frame: GpuFrame) {
            if (!latestGpuFrame.compareAndSet(null, frame)) {
                frame.hardwareBuffer.close()
                frame.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                return
            }
            clearGpuListener()
            tryFinishWithLatest()
        }

        fallbackRunnable = Runnable {
            if (frameAcquired.get()) {
                return@Runnable
            }
            if (latestGpuFrame.get() != null) {
                val remainingMs = fallbackDeadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    Log.w(TAG, "takeGpuFrame preview bitmap wait timed out")
                    tryFinishWithLatest(force = true)
                } else {
                    scheduleFallback(minOf(GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS, remainingMs))
                }
                return@Runnable
            }
            val frame = acquireGpuFrame(reader)
            if (frame != null) {
                Log.i(TAG, "takeGpuFrame fallback polling acquired frame")
                acceptGpuFrame(frame)
                return@Runnable
            }
            val remainingMs = fallbackDeadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                Log.w(TAG, "takeGpuFrame fallback polling timed out")
                tryFinishWithLatest(force = true)
                return@Runnable
            }
            scheduleFallback(minOf(GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS, remainingMs))
        }

        reader.setOnImageAvailableListener({ activeReader ->
            val frame = acquireGpuFrame(activeReader)
            if (frame == null) {
                Log.w(TAG, "takeGpuFrame listener fired but frame unavailable, waiting for fallback")
                scheduleFallback(GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS)
                return@setOnImageAvailableListener
            }
            acceptGpuFrame(frame)
        }, captureHandler)

        jpegReader?.setOnImageAvailableListener({ activeReader ->
            val previewBitmap = acquirePreviewBitmap(activeReader)
            if (previewBitmap == null) {
                Log.w(TAG, "takeGpuFrame jpeg listener fired but preview bitmap unavailable")
                return@setOnImageAvailableListener
            }
            if (frameAcquired.get()) {
                previewBitmap.takeIf { !it.isRecycled }?.recycle()
                return@setOnImageAvailableListener
            }
            latestPreviewBitmap.getAndSet(previewBitmap)?.takeIf { !it.isRecycled }?.recycle()
            Log.i(
                TAG,
                "takeGpuFrame acquired preview bitmap width=${previewBitmap.width} height=${previewBitmap.height} rotation=${getQuickCaptureRotationDegrees()}",
            )
            tryFinishWithLatest()
        }, captureHandler)

        fun submitGpuFrameRequest(sessionLabel: String) {
            try {
                val session = captureSession
                if (session == null || isSessionClosed) {
                    Log.w(TAG, "takeGpuFrame session unavailable label=$sessionLabel")
                    finishRequest(null)
                    return
                }
                val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                    addTarget(surface)
                    jpegSurface?.let {
                        addTarget(it)
                    }
                }
                session.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            timestamp: Long,
                            frameNumber: Long,
                        ) {
                            scheduleFallback(GPU_FRAME_FALLBACK_INITIAL_DELAY_MS)
                        }

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult,
                        ) {
                            scheduleFallback(GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS)
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure,
                        ) {
                            Log.w(TAG, "takeGpuFrame capture failed label=$sessionLabel frame=${failure.frameNumber}")
                            scheduleFallback(GPU_FRAME_FALLBACK_RETRY_INTERVAL_MS)
                        }
                    },
                    captureHandler,
                )
                Log.i(TAG, "takeGpuFrame submitted request label=$sessionLabel warm=${isGpuCaptureWarm()}")
            } catch (error: Exception) {
                Log.e(TAG, "请求常驻预览帧失败 label=$sessionLabel", error)
                finishRequest(null)
            }
        }

        if (captureSession == null || isSessionClosed) {
            Log.i(TAG, "takeGpuFrame rebuilding gpu frame session")
            createGpuFrameSession { success ->
                if (!success) {
                    finishRequest(null)
                    return@createGpuFrameSession
                }
                submitGpuFrameRequest("rebuilt")
            }
        } else {
            submitGpuFrameRequest("warm")
        }
    }

    private fun acquireGpuFrame(reader: ImageReader): GpuFrame? {
        val image = reader.acquireLatestImage() ?: run {
            Log.d(TAG, "获取预览帧失败 - image is null")
            return null
        }

        var frame: GpuFrame? = null
        try {
            val hardwareBuffer = image.hardwareBuffer
            if (hardwareBuffer == null) {
                Log.e(TAG, "预览帧 HardwareBuffer 为空")
            } else {
                val rotationDegrees = getQuickCaptureRotationDegrees()
                frame = GpuFrame(
                    hardwareBuffer = hardwareBuffer,
                    width = image.width,
                    height = image.height,
                    rotationDegrees = rotationDegrees,
                )
                Log.i(TAG, "takeGpuFrame acquired width=${image.width} height=${image.height} rotation=$rotationDegrees")
            }
        } catch (error: Exception) {
            Log.e(TAG, "获取预览帧失败", error)
        } finally {
            image.close()
        }
        return frame
    }

    fun startRecording(isAudioMute: Boolean = false, callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        if (!isInitialized || cameraDevice == null || !hasAudioPermission() || isRecording) {
            weakCallback.get()?.invoke(null)
            return
        }

//        try {
//            captureSession?.stopRepeating()
//        } catch (e: Exception) {
//            Log.d(TAG, "停止预览失败", e)
//        }

        try {
            resetRecordingState()
            videoFile = createVideoFile()
            setupPreviewSurface()

            mediaRecorder = MediaRecorder().apply {
                if (!isAudioMute) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                val size = getBestVideoSize() ?: Size(1080, 1920)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (!isAudioMute) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setVideoSize(size.width, size.height)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(videoFile?.absolutePath)
                setOrientationHint(270)
                prepare()
            }

            DeviceUtil.setSystemProp("vendor.rkd.camera.sensormode", "5")
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val recorderSurface = mediaRecorder!!.surface
            builder?.addTarget(previewSurface!!)
            builder?.addTarget(recorderSurface)

            setProcessingCaptureState(true)
            cameraDevice?.createCaptureSession(
                listOf(previewSurface!!, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(sessionLock) {
                            if (cameraDevice == null) {
                                Log.d(TAG, "cameraDevice 已关闭")
                                resetRecordingState()
                                weakCallback.get()?.invoke(null)
                                setProcessingCaptureState(false)
                                return
                            }

                            try {
                                captureSession = session
                                mediaRecorder?.start()
                                isRecording = true
                                weakCallback.get()?.invoke(videoFile)
                                Handler(backgroundHandler!!.looper).post {
                                    try {
                                        builder?.build()?.let {
                                            synchronized(sessionLock) {
                                                captureSession?.setRepeatingRequest(it, null, backgroundHandler)
                                            }
                                        }
                                    } catch (e: IllegalStateException) {
                                        setProcessingCaptureState(false)
                                        L.e(TAG, "录像时 session 已关闭", e)
                                    } catch (e: Exception) {
                                        setProcessingCaptureState(false)
                                        L.e(TAG, "录像 setRepeatingRequest 异常", e)
                                    }
                                }
                            } catch (e: Exception) {
                                L.e(TAG, "录像开始失败", e)
                                setProcessingCaptureState(false)
                                resetRecordingState()
                                weakCallback.get()?.invoke(null)
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        resetRecordingState()
                        setProcessingCaptureState(false)
                        weakCallback.get()?.invoke(null)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            setProcessingCaptureState(false)
            resetRecordingState()
            weakCallback.get()?.invoke(null)
        }
    }

    fun stopRecording(callback: (File?) -> Unit) {
        val weakCallback = WeakReference(callback)
        if (!isRecording) {
            weakCallback.get()?.invoke(null)
            return
        }

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "停止录像异常", e)
        }

        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false

        synchronized(sessionLock) {
            isSessionClosed = true
            captureSession?.close()
            captureSession = null
        }
        L.e(TAG, "stopRecording " + videoFile?.absolutePath)
        weakCallback.get()?.invoke(videoFile)
        setProcessingCaptureState(false)
    }

    private fun setupImageReader(mSize: Size? = null, quickCapture: Boolean = false) {
        if (quickCapture) {
            setupGpuImageReader(mSize)
            setupQuickCaptureCpuReader(mSize)
            return
        }
        L.d(TAG, "setupImageReader->1")
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.JPEG)
        val supportedSizes = outputSizes.orEmpty()
        val fallbackSize = supportedSizes
            .minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1080, 1920)
        val size = when {
            mSize == null -> {
                supportedSizes.firstOrNull { it.width == 2268 && it.height == 3024 } ?: fallbackSize
            }
            else -> {
                chooseCaptureSize(supportedSizes, mSize) ?: fallbackSize
            }
        }
        Log.d(
            TAG,
            "setupImageReader->request=${mSize?.width ?: -1}x${mSize?.height ?: -1} selected=${size.width}x${size.height}",
        )
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        // 不设置监听器，改为在 takePicture() 时临时设置
    }

    private fun setupGpuImageReader(mSize: Size? = null) {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!) ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = map?.getOutputSizes(ImageFormat.PRIVATE)
            ?: map?.getOutputSizes(SurfaceTexture::class.java)
            ?: emptyArray()
        // 提高分辨率以支持640x640中心裁剪，相当于数字变焦效果
        // 1280x720裁剪640x640 = 2x变焦
        val requested = mSize ?: Size(1280, 720)
        val fallbackSize = outputSizes
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: requested
        val size = choosePreviewSize(outputSizes, requested) ?: fallbackSize
        Log.i(
            TAG,
            "setupGpuImageReader->request=${requested.width}x${requested.height} selected=${size.width}x${size.height}",
        )
        quickCaptureGpuFrameSize = size
        gpuImageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.PRIVATE,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE.toLong(),
        )
    }

    private fun setupQuickCaptureCpuReader(mSize: Size? = null) {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!) ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.YUV_420_888)
        val supportedSizes = outputSizes.orEmpty()
        val requested = quickCaptureGpuFrameSize ?: mSize ?: Size(1280, 720)
        val fallbackSize = supportedSizes
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: requested
        val size = supportedSizes.firstOrNull { it.width == requested.width && it.height == requested.height }
            ?: choosePreviewSize(supportedSizes, requested)
            ?: fallbackSize
        Log.i(
            TAG,
            "setupQuickCaptureCpuReader->request=${requested.width}x${requested.height} selected=${size.width}x${size.height}",
        )
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    }

    /**
     * quick capture 只关心尽量小的稳定 JPEG 输出，避免精确匹配失败后回退到大分辨率。
     */
    private fun chooseCaptureSize(sizes: Array<out Size>, requested: Size): Size? {
        if (sizes.isEmpty()) {
            return null
        }
        val requestArea = requested.width.toLong() * requested.height.toLong()
        val exact = sizes.firstOrNull { it.width == requested.width && it.height == requested.height }
        if (exact != null) {
            return exact
        }

        val smallerOrEqual = sizes
            .filter { size ->
                size.width <= requested.width && size.height <= requested.height
            }
            .sortedWith(
                compareBy<Size> { requested.width - it.width }
                    .thenBy { requested.height - it.height }
                    .thenBy { it.width.toLong() * it.height.toLong() },
            )
        if (smallerOrEqual.isNotEmpty()) {
            return smallerOrEqual.first()
        }

        return sizes.minWithOrNull(
            compareBy<Size> { kotlin.math.abs(it.width.toLong() * it.height.toLong() - requestArea) }
                .thenBy { kotlin.math.abs(it.width - requested.width) + kotlin.math.abs(it.height - requested.height) },
        )
    }

    private fun choosePreviewSize(sizes: Array<out Size>, requested: Size): Size? {
        if (sizes.isEmpty()) {
            return null
        }
        val requestRatio = requested.width.toFloat() / requested.height.toFloat()
        return sizes
            .filter { it.width > 0 && it.height > 0 }
            .sortedWith(
                compareBy<Size> {
                    kotlin.math.abs(it.width.toFloat() / it.height.toFloat() - requestRatio)
                }.thenBy {
                    val widthPenalty = if (it.width < requested.width) requested.width - it.width else 0
                    val heightPenalty = if (it.height < requested.height) requested.height - it.height else 0
                    widthPenalty + heightPenalty
                }.thenBy {
                    kotlin.math.abs(it.width - requested.width) + kotlin.math.abs(it.height - requested.height)
                },
            )
            .firstOrNull()
    }


    private fun saveImageFromBuffer(buffer: ByteBuffer): File? {
        val photoFile = createImageFile()
        if (photoFile == null) {
            Log.e(TAG, "创建图片文件失败")
            return null
        }
        try {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            FileOutputStream(photoFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            return photoFile
        } catch (e: Exception) {
            L.e(TAG, "保存图像失败: ${e.message}", e)
            return null
        } finally {
        }
    }

    private fun drainImageReader(reader: ImageReader) {
        while (true) {
            val staleImage = runCatching { reader.acquireLatestImage() }
                .onFailure { error -> Log.w(TAG, "drainImageReader failed", error) }
                .getOrNull()
                ?: break
            staleImage.close()
        }
    }

    private fun acquirePreviewBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "acquirePreviewBitmap unsupported format=${image.format}")
                return null
            }
            val nv21 = yuv420888ToNv21(image) ?: return null
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val output = ByteArrayOutputStream()
            if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, output)) {
                return null
            }
            val bytes = output.toByteArray()
            val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return rotateBitmapIfNeeded(decodedBitmap, getQuickCaptureRotationDegrees())
        } catch (error: Exception) {
            Log.w(TAG, "decode preview bitmap failed", error)
            return null
        } finally {
            image.close()
        }
    }

    private fun rotateBitmapIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) {
            return source
        }

        val matrix = android.graphics.Matrix().apply {
            postRotate(normalizedRotation.toFloat())
        }
        return try {
            val rotatedBitmap = Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true,
            )
            if (rotatedBitmap !== source && !source.isRecycled) {
                source.recycle()
            }
            rotatedBitmap
        } catch (error: Exception) {
            Log.w(TAG, "rotateBitmapIfNeeded failed rotation=$normalizedRotation", error)
            source
        }
    }

    private fun getQuickCaptureRotationDegrees(): Int {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!) ?: return 0
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return when (((sensorOrientation % 360) + 360) % 360) {
            0, 90, 180, 270 -> sensorOrientation
            else -> 0
        }
    }

    private fun yuv420888ToNv21(image: android.media.Image): ByteArray? {
        if (image.planes.size < 3) {
            return null
        }
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(
            plane = yPlane,
            width = width,
            height = height,
            out = nv21,
            outOffset = 0,
            outPixelStride = 1,
        )

        copyPlane(
            plane = vPlane,
            width = width / 2,
            height = height / 2,
            out = nv21,
            outOffset = ySize,
            outPixelStride = 2,
        )
        copyPlane(
            plane = uPlane,
            width = width / 2,
            height = height / 2,
            out = nv21,
            outOffset = ySize + 1,
            outPixelStride = 2,
        )

        return nv21
    }

    private fun copyPlane(
        plane: android.media.Image.Plane,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowData = ByteArray(rowStride)
        var outputIndex = outOffset

        for (row in 0 until height) {
            val rowLength = if (pixelStride == 1 && outPixelStride == 1) {
                width
            } else {
                (width - 1) * pixelStride + 1
            }
            buffer.get(rowData, 0, rowLength)
            var inputIndex = 0
            for (col in 0 until width) {
                out[outputIndex] = rowData[inputIndex]
                outputIndex += outPixelStride
                inputIndex += pixelStride
            }
            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - rowLength)
            }
        }
    }


    private fun setupPreviewSurface() {
        if (surfaceTexture == null) {
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture?.setDefaultBufferSize(1280, 720)
        }
        if (previewSurface == null) {
            previewSurface = Surface(surfaceTexture)
        }
    }

    private fun resetRecordingState() {
        isRecording = false
        mediaRecorder?.release()
        mediaRecorder = null
        previewSurface?.release()
        surfaceTexture?.release()
        previewSurface = null
        surfaceTexture = null
        videoFile = null
    }

    private fun getBestVideoSize(): Size? {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            val map: StreamConfigurationMap? = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes: Array<Size>? = map?.getOutputSizes(MediaRecorder::class.java)
            // 设置摄像头录像为横屏
            sizes?.firstOrNull { it.width == 1080 && it.height == 1920 }
            // 设置摄像头录像为竖屏
//            sizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: sizes?.firstOrNull { it.width == 720 && it.height == 1280 }
                ?: sizes?.getOrNull(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun getJpegOrientation(rotation: Int): Int {
        val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return when (rotation) {
            Surface.ROTATION_0 -> (sensorOrientation + 0) % 360
            Surface.ROTATION_90 -> (sensorOrientation + 270) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }
    }

//    private fun createImageFile(): File {
//        // 获取基础图片目录
//        val baseDir = MyApplication.getContext().getExternalFilesDir(Environment.DIRECTORY_DCIM)
//        // 创建包含album子目录的完整路径
//        val albumDir = File(baseDir, "album")
//        // 确保目录存在（若不存在则创建）
//        if (!albumDir.exists()) {
//            albumDir.mkdirs()
//        }
//        // 生成时间戳文件名
//        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//        // 在album目录下创建图片文件
//        return File(albumDir, "IMG_$timeStamp.jpg")
//    }


    fun createImageFile(): File? { // 改为返回 File?，避免异常时返回无效对象
        return try {
            // 1. 关键修改：获取系统公共 DCIM 目录（替代原私有目录）
            // 路径示例：/storage/emulated/0/DCIM（所有应用可访问）
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            baseDir.mkdirs()
            if (baseDir == null || !baseDir.exists()) {
                return null // 极端情况：公共目录不存在（如存储挂载失败）
            }

            // 2. 保持原有逻辑：创建 album 子目录（路径：/Pictures/album）
            val albumDir = File(baseDir, "album")
            if (!albumDir.exists()) {
                albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
            }

            // 3. 保持原有逻辑：生成时间戳文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFile = File(albumDir, "IMG_$timeStamp.jpg")

            // 4. 新增：通知系统扫描文件，确保其他应用（如相册、微信）能识别
            scanPublicFile(imageFile)

            imageFile // 返回公共目录下的 File 对象（后续可直接写入数据）
        } catch (e: Exception) {
            e.printStackTrace()
            null // 异常时返回 null（如权限不足、存储满）
        }
    }

    // 辅助方法：通知系统扫描公共目录的文件（核心，否则其他应用找不到）
    private fun scanPublicFile(file: File) {
        val context = MyApplication.getContext()
        // 发送广播触发系统媒体扫描（兼容所有 Android 版本）
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        val fileUri = Uri.fromFile(file)
        mediaScanIntent.data = fileUri
        context.sendBroadcast(mediaScanIntent)
    }

    private fun createVideoFile(): File {
        // 获取基础视频目录
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        // 创建包含album子目录的完整路径
        val albumDir = File(baseDir, "album")
        // 确保目录存在（若不存在则创建）
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }
        // 生成时间戳文件名
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 在album目录下创建视频文件
        return File(albumDir, "VID_$timeStamp.mp4")
    }

    fun releaseCamera() {
        try {
            cancelPendingGpuFrameFallback()
            if (isCameraClosed) return
            if (isRecording) {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {
                }
                mediaRecorder?.release()
            }

            synchronized(sessionLock) {
                captureSession?.close()
                captureSession = null
            }
            cancelPendingGpuFrameFallback()
            setProcessingCaptureState(false)
            cameraDevice?.close()
            gpuImageReader?.close()
            imageReader?.close()
            imgCallback = null
            gpuFrameCallback = null
            cameraDevice = null
            gpuImageReader = null
            quickCaptureGpuFrameSize = null
            imageReader = null
            previewSurface?.release()
            surfaceTexture?.release()
            previewSurface = null
            surfaceTexture = null

            stopBackgroundThread()

            mediaRecorder = null

            L.d(TAG, "释放相机")
        } catch (e: Exception) {
            L.d(TAG, "releaseCamera 异常: ${e.message}")
        } finally {
            isCameraClosed = true
            isRecording = false
            isInitialized = false
            setProcessingCaptureState(false)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
                cameraManager?.registerAvailabilityCallback(availabilityCallback, backgroundHandler)
            }
        }
    }

    private fun stopBackgroundThread(callback: (() -> Unit)? = null) {
        cameraManager?.unregisterAvailabilityCallback(availabilityCallback)
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        callback?.invoke() // 直接执行回调，无需延迟
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(MyApplication.getContext(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(MyApplication.getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun isRecording(): Boolean = isRecording


    private var captureCollectJob: Job? = null
    private var isListeningProcessing = false

    fun actionDestroyCameraTask() {
        L.d(TAG, "actionDestroyCameraTask->${isCameraDoing()}")
        if (isCameraDoing()) {
            mainScope.launch {
                // 2. 关键：先取消旧协程并等待其完全结束（避免旧协程残留）
                captureCollectJob?.let {
                    it.cancel() // 取消旧协程
                    it.join()   // 等待协程完全终止（解决协作式取消的延迟问题）
                    captureCollectJob = null // 清空引用，避免重复操作
                }
                // 3. 确保当前没有其他监听协程，再启动新协程
                if (!isListeningProcessing) {
                    isListeningProcessing = true
                    captureCollectJob = launch {
                        try {
                            // 新增：5秒超时机制
                            withTimeoutOrNull(5000) {
                                // 监听处理状态流
                                isProcessingCapture.collect { processing ->
                                    L.d(TAG, "actionDestroyCameraTask collect->$processing isCameraClosed：${isCameraClosed}")
                                    if (!processing && !isCameraClosed) {
                                        releaseCamera()
                                        captureCollectJob?.cancel() // 满足条件时取消协程
                                    }
                                }
                            } ?: run {
                                // 超时未满足条件，强制释放
                                Log.d(TAG, "相机释放超时（5秒），强制释放资源")
                                if (!isCameraClosed) {
                                    releaseCamera()
                                }
                            }
                        } catch (e: CancellationException) {
                            // 正常取消，无需处理
                        } finally {
                            isListeningProcessing = false
                            captureCollectJob = null
                        }
                    }
                }
            }
        } else {
            releaseCamera()
        }
    }

    fun saveImage2(bytes: ByteArray) {
        val photoFile = createImageFile()
        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图像失败: ${e.message}", e)
        } finally {
            bitmap?.recycle()
        }
    }


}
