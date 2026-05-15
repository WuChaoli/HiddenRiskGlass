package com.rokid.glass


import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.camera.CameraTestManager
import com.rokid.glesse.databinding.ActivityCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相机功能主Activity - 实现了拍照和条码扫描功能
 * 继承自 [BaseActivity]，提供了基本的相机操作界面
 * 新增了的图片处理逻辑，交给后续的业务代码来做
 */
class CameraActivity : BaseActivity() {
    // 视图绑定对象，用于访问布局中的UI组件
    private lateinit var viewBinding: ActivityCameraBinding

    // 纹理视图，用于显示相机预览画面
    private lateinit var textureView: TextureView

    // 相机管理器，负责相机的具体操作实现
    private lateinit var cameraManager: CameraTestManager

    // 条码扫描器，用于识别二维码和条形码
    private val scanner: BarcodeScanner = BarcodeScanning.getClient()

    // 主线程处理器，用于在主线程执行UI更新操作
    private val handler = Handler(Looper.getMainLooper())

    // 扫描状态标志，防止重复扫描
    private var isScanning = false

    // 日志标记，用于调试输出
    private val TAG = "CameraActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用边缘到边缘显示模式
        enableEdgeToEdge()
        viewBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        // 创建相机管理器实例
        cameraManager = CameraTestManager(this)
        // 获取纹理视图实例
        textureView = viewBinding.textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            /**
             * 表面纹理可用时调用，初始化相机并开始预览
             */
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "----onSurfaceTextureAvailable---width=$width, height=$height")
                // 初始化相机，设置预览尺寸并回调结果
                cameraManager.initialize(surface, width, height) { success ->
                    if (success) {
                        startBarcodeScanningLoop()
                        // 启动位图处理功能（用于持续获取预览帧
                        handlerBitmap()
                        Log.d(TAG, "相机初始化成功并开始预览")
                    } else {
                        Log.e(TAG, "相机初始化失败")
                    }
                }
            }

            /**
             * 表面纹理尺寸改变时调用
             */
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            /**
             * 表面纹理被销毁时调用
             */
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            /**
             * 表面纹理更新时调用（每一帧都会触发）
             */
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        // 设置拍照按钮点击事件
        viewBinding.btnTakePhoto.requestFocus()
        viewBinding.btnTakePhoto.setOnClickListener {
            // 创建照片保存路径
            val filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val outputFile = File(filePath, "photo.jpg")
            Log.d("log", "-------文件路径=${outputFile.absolutePath}")
            // 调用相机管理器拍照功能
            cameraManager.takePicture(outputFile) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "照片已保存: ${outputFile.absolutePath}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "拍照失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }


    }

    /**
     * 处理纹理视图中的位图数据
     * 使用协程创建定时任务，每隔50毫秒获取一次当前预览帧
     */
    fun handlerBitmap() {
        // 在IO调度器中启动协程
        lifecycleScope.launch(Dispatchers.IO) {
            // 创建无限循环的Flow，每100毫秒发射一次数据
            infiniteIntervalFlow(100).collect { value ->
                // 检查纹理视图是否可用
                if (!textureView.isAvailable) {
                    return@collect
                }
                // 获取当前纹理视图的位图
                val bitmap: Bitmap? = textureView.bitmap
                if (bitmap == null) {
                    return@collect
                }
                // 1. 关键修改：获取系统公共 DCIM 目录（替代原私有目录）
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                // 2. 保持原有逻辑：创建 album 子目录（路径：/Pictures/album）
                val albumDir = File(baseDir, "album")
                if (!albumDir.exists()) {
                    albumDir.mkdirs() // 自动创建多级目录（DCIM 已存在，仅创建 album）
                }
                // 3. 保持原有逻辑：生成时间戳文件名
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outputFile = File(albumDir, "IMG_$timeStamp.jpg")
                FileOutputStream(outputFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                }
                // 这里可以对获取到的位图进行处理
                Log.d(TAG, "图片宽度: ${bitmap.width} ,图片高度: ${bitmap.height}  ${Thread.currentThread().name}")
            }
        }
    }


    /**
     * 创建一个无限循环的 Flow，每指定毫秒发送一个递增的数据
     * @param intervalMillis 间隔时间，默认为 50 毫秒
     */
    fun infiniteIntervalFlow(intervalMillis: Long = 50): Flow<Long> = flow {
        var counter = 0L
        while (true) {
            emit(counter)  // 发送当前计数值
            counter++
            delay(intervalMillis)  // 延迟指定毫秒数
        }
    }

    /**
     * 启动条码扫描循环
     * 定期从纹理视图获取位图并尝试扫描其中的条码
     */
    private fun startBarcodeScanningLoop() {
        handler.post(object : Runnable {
            override fun run() {
                // 检查纹理视图是否可用且当前未在扫描
                if (textureView.isAvailable && !isScanning) {
                    val bitmap: Bitmap? = textureView.bitmap
                    if (bitmap == null) {
                        return
                    }
                    // 设置扫描状态为正在进行
                    isScanning = true
                    // 从位图中扫描条码
                    scanBarcodesFromBitmap(bitmap, onSuccess = { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            for (barcode in barcodes) {
                                Log.d(TAG, "识别到: ${barcode.rawValue}")
                            }
                        }
                        // 重置扫描状态
                        isScanning = false
                    }, onFailure = { e ->
                        Log.e(TAG, "识别失败", e)
                        // 出错时也需重置扫描状态
                        isScanning = false
                    })
                }
                handler.postDelayed(this, 1000) // 每隔 1 秒执行一次
            }
        })
    }

    /**
     * 从位图中扫描条码
     * @param bitmap 需要扫描的位图
     * @param onSuccess 扫描成功回调，返回识别到的条码列表
     * @param onFailure 扫描失败回调，返回异常信息
     */
    fun scanBarcodesFromBitmap(
        bitmap: Bitmap,
        onSuccess: (List<Barcode>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> onSuccess(barcodes) }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /**
     * Activity销毁时的清理工作
     * 移除所有待处理的消息和回调，并释放相机资源
     */
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraManager.release()
    }


}
