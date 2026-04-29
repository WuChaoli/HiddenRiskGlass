package com.rokid.glass


import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.utils.FaceModelUtils
import com.rokid.glass.utils.TimeUtils
import com.rokid.glesse.databinding.ActivityTrackBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.LPRModel
import com.rokid.security.sdk.base.common.out.AIRecgMode.Companion.MODE_FACE
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.recog.online.listener.IGlassDetectionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class GlassFaceTrackActivity : BaseActivity() {
    private val TAG = "GlassTrackActivity"
    private lateinit var binding: ActivityTrackBinding
    private var lastTrackId: Long = -1L
    private var startTime = 0L

    /**
     * 离线和在线人脸检测功能
     */
    private val mAbsGlassOnlineRecService by lazy {
        GlassSdk.getGlassOnlineRecService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
    }

    private fun initView() {
//        val min = 20
//        val enableAudio = true
//        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
//        Log.d(TAG, "onCreate: path = $path")
//        val recordConfig = RecordConfig(
//            path, min, enableAudio,
//            width = PreviewResolution.ResolutionInfo_1080P_Land.width,
//            height = PreviewResolution.ResolutionInfo_1080P_Land.height
//        )
//        // 开始视频录制
//        GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
        // 设置人脸检测监听
        mAbsGlassOnlineRecService?.setGlassOnlineRecListener(mIGlassDetectionListener)
        // 开始人脸检测
        mAbsGlassOnlineRecService?.startDetection(MODE_FACE)
    }

    /**
     * 视频录制回调
     */
    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            Log.d(TAG, "onError: ")
        }

        override fun onFinish() {
            Log.d(TAG, "onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            Log.d(TAG, "onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            Log.d(TAG, "onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            Log.d(TAG, "onStart: ")
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止视频录制
        GlassSdk.getGlassMediaService()?.stopRecord()
        // 停止人脸检测
        mAbsGlassOnlineRecService?.stopDetection()
        // 移除检测监听
        mAbsGlassOnlineRecService?.removeGlassOnlineRecListener(mIGlassDetectionListener)
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        super.onGlassKeyEvent(keyEvent)
        if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_FRONT) {
//            workHomeAdapter?.toNextItem()
        } else if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_BEHIND) {
//            workHomeAdapter?.toPreviousItem()
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }


    /**
     * 将 Bitmap 转换为 PNG 格式的 byte[]
     * @param bitmap 输入的 Bitmap 对象
     * @return 转换后的 byte[]，如果转换失败则返回 null
     */
    fun bitmapToPngByteArray(bitmap: Bitmap): ByteArray? {
        val stream = ByteArrayOutputStream()
        // quality 参数对 PNG 无效，可以传入任意值
        val success = bitmap.compress(CompressFormat.PNG, 100, stream)
        return if (success) {
            stream.toByteArray()
        } else {
            null
        }
    }

    /**
     * 人脸检测监听
     */
    private val mIGlassDetectionListener = object : IGlassDetectionListener.Stub() {

        /**
         * 检测模式发送改变的回调
         * @param mode 检测模式
         */
        override fun onModeChange(mode: Int) {
            Log.e(TAG, "onModeChange: $mode")
        }

        /**
         * 人脸检测的回调(可用于更新人脸追踪框)
         * @param faceModels 人脸模型列表
         */
        override fun onFaceTrack(faceModels: List<FaceModel>) {
        }

        /**
         * 车牌检测的回调
         * @param lprModel 车牌模型
         */
        override fun onLPRTrack(lprModel: LPRModel) {
        }

        /**
         * 筛选最优的人脸图像 (可用于在线人脸识别接口的调用)
         * @param processedFaceModels 处理后的人脸模型列表
         */
        override fun onProcessedFaceModels(processedFaceModels: List<FaceModel>) {
            if (processedFaceModels.isEmpty()) {
                return
            }
//            Log.e(TAG, "识别到数据")
            val faceModel = FaceModelUtils.findBestFaceRect(
                processedFaceModels,
                processedFaceModels[0].frameWidth,
                processedFaceModels[0].frameHeight
            )
            if (startTime == 0L) {
                startTime = System.currentTimeMillis()
            }
            if (faceModel == null) {
                Log.i(TAG, "未获取到符合要求人脸数据")
                return
            }

            if (faceModel.trackId == lastTrackId) {
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
//                    var successReturned = false
//                    val timeoutJob = launch {
//                        delay(3000) // 等待 3 秒
//                        if (!successReturned) {
//                            sendConnect = true
//                            Log.e(TAG, "超时 3 秒未返回 -> sendConnect=true")
//                        }
//                    }
                Log.i(TAG, "onProcessedFaceModels 开始识别 trackId = ${faceModel.trackId}")
                // 先更新人脸抓拍图
                val smallBitmap = mAbsGlassOnlineRecService?.getFaceSamllBitmap(faceModel.trackId)
                if (smallBitmap == null) {
                    return@launch
                }
                // 1. 关键修改：获取系统公共 Pictures 目录（替代原私有目录）
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
                    smallBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                }
                if (!smallBitmap.isRecycled) {
                    smallBitmap.recycle()
                }
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "人脸检测时长：${TimeUtils.formatDuration(duration)}")
//                    val startTime = System.currentTimeMillis()
//                   val faceIdentifyItem = OnlineFaceIdentifyItem(
//                        false,
//                        faceModel,
//                        faceModel.trackId.toString(),
//                        smallBitmap,
//                        null,
//                        LoadState.LOADING
//                    )
//                    val faceRecognizeParam = FaceRecognizeParam(faceModel.trackId.toString(), faceModel.frameId.toString())
//                    Log.e(TAG, "recognizeFac:发送")
//                            GlassSdk.getGlassMessageService()?.sendStreamData("在线",bitmapToPngByteArray(bitmap),"GlassSample",object: com.rokid.security.system.server.message.callback.IResultCallback.Stub(){
//                                override fun onSuccess(p0: Boolean) {
////                                    TODO("Not yet implemented")
//                                    Log.e(TAG,"recognizeFac:发送成功")
//                                }
//
//                                override fun onFailed(p0: Int, p1: String?) {
////                                    TODO("Not yet implemented")
//                                    Log.e(TAG,"recognizeFac:发送失败")
//                                }
//                            })
                /**
                 * 在线人脸识别
                 * @param param 人脸识别参数
                 * @param callback 识别结果的回调
                 */
//                    mAbsGlassOnlineRecService?.recognizeFace(faceRecognizeParam, object : IResultCallback.Stub() {
//                        override fun onSuccess(data: RecognizePersonInfo?) {
//                            lastTrackId = faceModel.trackId
//                            val endTime = System.currentTimeMillis()
//                            Log.e(
//                                TAG, "识别成功,耗时=${endTime - startTime},frameId = ${faceRecognizeParam.frameId}, " +
//                                        "trackId= ${faceRecognizeParam.trackId} , onSuccess: data = $data"
//                            )
//                            timeoutJob.cancel()
//                            sendConnect = false
//                        }
//
//                        override fun onFailed(code: Int, errormsg: String?) {
//                            timeoutJob.cancel()
//                            sendConnect = true
//                            val endTime = System.currentTimeMillis()
//                            Log.e(
//                                TAG, "识别失败,耗时=${TimeUtils.formatDuration(endTime - startTime)}, frameId = ${faceRecognizeParam.frameId}," +
//                                        " trackId= ${faceRecognizeParam.trackId} ,onFailed:  code = $code, errormsg = $errormsg"
//                            )
//                        }
//
//                    })
            }
        }
    }

}