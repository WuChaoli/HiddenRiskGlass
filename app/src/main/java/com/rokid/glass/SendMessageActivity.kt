package com.rokid.glass

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.data.GlobalData
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivitySendmessageBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.media.callback.AudioCallback
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.security.system.server.message.file.listener.FileReceiveListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

class SendMessageActivity : BaseActivity() {

    private var currentSelectId: Int = -1
    private var randomFile = false
    private val TAG = "SendMessageActivity"
    private lateinit var binding: ActivitySendmessageBinding
    private var startTime = 0L
    private var selectBtnStatus = 0
    private val sdDownload = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

    private var handlerLprCount = 5
    private lateinit var huoVoiceAction: VoiceAction

    /**
     * 获取P2P发送文件的管理器
     * @return IGlassFileOperate
     */
    private val mFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassFileOperater
    }

    /**
     * 获取蓝牙发送文件的管理器
     * @return IGlassFileOperate
     */
    private val mBTFileOperator by lazy {
        GlassSdk.getGlassMessageService()?.glassBtFileOperater
    }

    /**
     *  设置本端文件接收的监听器
     */
    private val bleFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: 蓝牙本端开始发送文件")
            log("onStart: 蓝牙本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: 蓝牙本端发送文件的进度 $progress")
            log("onProgressChanged: 蓝牙本端发送文件的进度 $progress")
        }

        /**
         * 眼睛端发送文件路径
         */
        override fun onComplete(filePath: String) {
            //手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: 蓝牙本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log("onComplete: 蓝牙本端发送文件完成，${duration}秒,${fileSize}")
            } else {
                log("onComplete: 蓝牙本端发送文件完成，${duration}毫秒,${fileSize}")
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: 蓝牙本端发送文件失败")
            log("onFail: 蓝牙本端发送文件失败")
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: 蓝牙对方取消了发送文件")
            log("onCancel: 蓝牙对方取消了发送文件")
        }
    }

    private val p2pFileReceiveListener = object : FileReceiveListener.Stub() {
        override fun onStart() {
            startTime = System.currentTimeMillis()
            Log.i(TAG, "onStart: p2p本端开始发送文件")
            log("onStart: p2p本端开始发送文件")
        }

        override fun onProgressChanged(progress: Float) {
            Log.i(TAG, "onProgressChanged: p2p本端发送文件的进度 $progress")
            log("onProgressChanged: p2p本端发送文件的进度 $progress")
        }

        /**
         * 眼睛端发送文件路径
         */
        override fun onComplete(filePath: String) {
            //手机端接收文件的路径 /storage/emulated/0/Android/data/com.rokid.phone/files/receiver/gonglu.png
            Log.i(TAG, "onComplete: p2p本端发送文件完成,----$filePath")
            var duration = System.currentTimeMillis() - startTime
            val fileSize = FileSizeUtil.formatFileSize(FileSizeUtil.getFileSizeBytes(curFile).toDouble())
            if (duration > 1000) {
                duration = duration / 1000
                log("onComplete: p2p本端发送文件完成，${duration}秒,${fileSize}")
            } else {
                log("onComplete: p2p本端发送文件完成，${duration}毫秒,${fileSize}")
            }
        }

        override fun onFail() {
            Log.i(TAG, "onFail: p2p本端发送文件失败")
            log("onFail: p2p本端发送文件失败")
        }

        override fun onCancel() {
            Log.i(TAG, "onCancel: p2p对方取消了发送文件")
            log("onCancel: p2p对方取消了发送文件")
        }
    }

    val a1File = "xiyi.jpg"
    val a2File = "gonglu.png"
    var curFile = File("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "初始化")
        binding = ActivitySendmessageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        copyFileFromAssetsToExternalStorage(this, a1File)
        copyFileFromAssetsToExternalStorage(this, a2File)

        selectBtn(binding.wfSendTextBtn)

        mBTFileOperator?.setFileReceiveListener(bleFileReceiveListener)
        mFileOperator?.setFileReceiveListener(p2pFileReceiveListener)

        huoVoiceAction = VoiceAction("火箭人", "huo jian ren", object : IVoiceCallback.Stub() {
            override fun onVoiceTriggered() {
                Log.e(TAG, "火箭人")
            }
        })
        GlassSdk.getGlassOfflineCmdService()?.add(huoVoiceAction)

        binding.keyMark.setOnKeyListener { view, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            Log.d(TAG, "setOnKeyListener 键盘回车事件")
                            toClick()
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往前键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往后键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        }

                        KeyEvent.KEYCODE_BACK -> {
                            Log.d(TAG, "setOnKeyListener 鼠标右击返回事件")
                            finish()
                        }
                    }
                }
            }
            true
        }


        lifecycleScope.launch(Dispatchers.IO) {
            delay(5000)
            while (handlerLprCount > 0) {
                val result = takePhotoAndAwait() // 协程会在此处等待拍照完成
                Log.d(TAG, "协程继续执行，结果为: $result")
                handlerLprCount--
            }
        }

//        //wifi模式发送文本
//        binding.wfSendTextBtn.setOnClickListener {
//            Log.d(TAG, "-------p2p发送文本点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.wfSendTextBtn)
//            toClick()
//        }
//        binding.wfSendFileBtn.setOnClickListener {
//            Log.d(TAG, "-------p2p发送文件点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.wfSendFileBtn)
//            toClick()
//        }
//        binding.btSendTextBtn.setOnClickListener {
//            Log.d(TAG, "-------蓝牙发送文本点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.btSendTextBtn)
//            toClick()
//        }
//        binding.btSendFileBtn.setOnClickListener {
//            Log.d(TAG, "-------蓝牙发送文件点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.btSendFileBtn)
//            toClick()
//        }
//        binding.btTts.setOnClickListener {
//            Log.d(TAG, "-------文本转语音语音转文本点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.btTts)
//            toClick()
//        }
//        binding.btAsr.setOnClickListener {
//            Log.d(TAG, "-------语音转文本点击事件-------")
//            unAllSelectState(binding.llMain)
//            selectBtn(binding.btAsr)
//            toClick()
//        }
    }

//    fun unAllSelectState(viewGroup: ViewGroup) {
//        for (i in 0 until viewGroup.childCount) {
//            if (viewGroup.getChildAt(i) is ViewGroup) {
//                unAllSelectState(viewGroup.getChildAt(i) as ViewGroup)
//            } else if (viewGroup.getChildAt(i) is AppCompatTextView) {
//                unSelectBtn(viewGroup.getChildAt(i) as AppCompatTextView)
//            }
//        }
//    }

    // 将拍照任务封装为挂起函数
    private suspend fun takePhotoAndAwait(): String = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "-------------调用了拍照方法, handlerLprCount:$handlerLprCount")
        val file = QuickCameraManager.createImageFile()
        if (file == null) {
            continuation.resume("创建图片文件失败") // 创建图片文件失败，直接恢复协程
            return@suspendCancellableCoroutine
        }
        val photoFileCallback = object : PhotoFileCallback.Stub() {
            override fun onTakePhoto(path: String) {
                L.d(TAG, "onTakePhoto-->path = $path")
                // 拍照成功，恢复协程并传递结果
                GlassSdk.getGlassMediaService()?.removePhotoCallback(this)
                continuation.resume("ok")
            }

            override fun getCallbackId(): String? {
                return "10002"
            }

            override fun onTakePhotoV2(path: String, width: Int, height: Int) {
//                L.d(TAG, "onTakePhoto--> width = $width,height = $height, path = $path")
//                // 拍照成功（带分辨率信息），恢复协程并传递结果
//                continuation.resume("")
//                GlassSdk.getGlassMediaService()?.removePhotoCallback(this)
            }
        }
        GlassSdk.getGlassMediaService()?.addPhotoCallback(photoFileCallback)
        // 调用拍照接口
        GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P, file.absolutePath)
    }


    private fun toClick() {
        when (currentSelectId) {
            R.id.wfSendTextBtn -> {
                GlassSdk.getGlassMessageService()?.sendTextMessageByP2P("眼睛端发送P2P测试的消息")
                log("眼睛端发送P2P测试的消息")
            }

            R.id.wfSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log("p2p准备发送文件...")
                if (randomFile) {
                    randomFile = false
                    Log.e(TAG, "文件目录: ${fileToSend1.absolutePath}")
                    curFile = fileToSend1
                    mFileOperator?.sendFile("", fileToSend1.absolutePath, p2pFileReceiveListener, mResultCallback)
                } else {
                    randomFile = true
                    Log.e(TAG, "文件目录: ${fileToSend2.absolutePath}")
                    curFile = fileToSend2
                    mFileOperator?.sendFile("", fileToSend2.absolutePath, p2pFileReceiveListener, mResultCallback)
                }
            }

            R.id.btSendTextBtn -> {
                GlassSdk.getGlassMessageService()?.sendTextMessageByClassicBT("眼睛端发送蓝牙测试消息")
                log("眼睛端发送蓝牙测试消息")
            }

            R.id.btSendFileBtn -> {
                val fileToSend1 = File(sdDownload, a1File)
                val fileToSend2 = File(sdDownload, a2File)
                logBuilder.clear()
                log("蓝牙准备发送文件...")
                if (randomFile) {
                    randomFile = false
                    curFile = fileToSend1
                    Log.e(TAG, "蓝牙发送---->文件目录: ${fileToSend1.absolutePath}")
                    mBTFileOperator?.sendFile("", fileToSend1.absolutePath, bleFileReceiveListener, mResultCallback)
                } else {
                    randomFile = true
                    curFile = fileToSend2
                    Log.e(TAG, "蓝牙发送---->文件目录: ${fileToSend2.absolutePath}")
                    mBTFileOperator?.sendFile("", fileToSend2.absolutePath, bleFileReceiveListener, mResultCallback)
                }
            }

            R.id.btTts -> {
                /**
                 * 开始tts 任务
                 * 需要播放的内容
                 */
                val str = "秋天不回来，我要去爬山啦"
                GlassSdk.getGlassTtsService()?.doSpeechTts(str)
                log(str)
            }

            R.id.btAsr -> {
                /**
                 * 当用户说话时语音会转文本
                 */
                GlassSdk.getGlassAsrService()?.startSpeech(object : SpeechCallback.Stub() {
                    override fun onStart() {
                        Log.i(TAG, "语音转文本开始")
                        log("语音转文本开始")
                    }

                    /**
                     * asr识别过程结果
                     * @param content 识别的内容
                     */
                    override fun onIntermediateVad(content: String) {
                        Log.i(TAG, content)
                        log(content)
                    }

                    /**
                     * asr识别完成的结果
                     * @param content 识别的内容
                     */
                    override fun onAsrComplete(content: String?) {
                        content?.apply {
                            Log.i(TAG, this)
                            log(this)
                        }
                    }

                    /**
                     * asr识别完成带意图识别
                     * @param content 识别的内容
                     * @param intent  意图index
                     * @param intentJson 意图json
                     * */
                    override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) {
                    }

                    override fun onError(code: Int) {
                        Log.i(TAG, "语音转文本失败:code=${code}")
                        log("语音转文本失败:code=${code}")
                    }

                    override fun onServiceConnectState(connect: Boolean) {
                        Log.i(TAG, "语音连接状态:${connect}")
                        log("语音连接状态:${connect}")
                    }
                })
            }

            R.id.btSendAudioStream -> {
                GlassSdk.getGlassMediaService()?.startAudioRecord(audioRecord)
            }
        }
    }

    private val audioRecord = object : AudioCallback.Stub() {
        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
            if (buffer == null || bufferLen == 0) {
                return
            }
            if (!GlobalData.btConnectState.value) {
                return
            }
            /**
             * 发送二进制流数据
             * @param tag 业务标记
             * @param data 音频数据
             * @param clientId 手机客户端id
             * @param callback 发送数据流成功或失败的回调
             */
            GlassSdk.getGlassMessageService()?.sendStreamData("AudioTag1", buffer, "GlassSample", object : IResultCallback.Stub() {
                override fun onSuccess(result: Boolean) {
//                    Log.i(TAG, "发送带业务标记的音频数据流数成功")
                    log("发送带业务标记的音频数据流数成功")
                }

                override fun onFailed(code: Int, errormsg: String?) {
                    Log.i(TAG, "发送带业务标记的音频数据流数失败:code=${code}，errormsg=${errormsg}")
                    log("发送带业务标记的音频数据流数失败:code=${code},errormsg=${errormsg}")
                }

            })
        }

        override fun getCallbackId(): String? {
            return "SendMessageRecord"
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 优先将事件分发给keyMark视图
        binding.keyMark.dispatchKeyEvent(event)
        return true
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                selectBtnStatus++
                selectBtnStatus %= 7
                when (selectBtnStatus) {
                    1 -> {
                        unSelectBtn(binding.wfSendTextBtn)
                        selectBtn(binding.wfSendFileBtn)
                    }

                    2 -> {
                        unSelectBtn(binding.wfSendFileBtn)
                        selectBtn(binding.btSendTextBtn)
                    }

                    3 -> {
                        unSelectBtn(binding.btSendTextBtn)
                        selectBtn(binding.btSendFileBtn)
                    }

                    4 -> {
                        unSelectBtn(binding.btSendFileBtn)
                        selectBtn(binding.btTts)
                    }

                    5 -> {
                        unSelectBtn(binding.btTts)
                        selectBtn(binding.btAsr)
                    }

                    6 -> {
                        unSelectBtn(binding.btAsr)
                        selectBtn(binding.btSendAudioStream)
                    }

                    0 -> {
                        unSelectBtn(binding.btSendAudioStream)
                        selectBtn(binding.wfSendTextBtn)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                selectBtnStatus--
                if (selectBtnStatus < 0) {
                    selectBtnStatus = 6
                }
                selectBtnStatus %= 7
                when (selectBtnStatus) {
                    1 -> {
                        unSelectBtn(binding.btSendTextBtn)
                        selectBtn(binding.wfSendFileBtn)
                    }

                    2 -> {
                        unSelectBtn(binding.btSendFileBtn)
                        selectBtn(binding.btSendTextBtn)
                    }

                    3 -> {
                        unSelectBtn(binding.btTts)
                        selectBtn(binding.btSendFileBtn)
                    }

                    4 -> {
                        unSelectBtn(binding.btAsr)
                        selectBtn(binding.btTts)
                    }

                    5 -> {
                        unSelectBtn(binding.btSendAudioStream)
                        selectBtn(binding.btAsr)
                    }

                    6 -> {
                        unSelectBtn(binding.wfSendTextBtn)
                        selectBtn(binding.btSendAudioStream)
                    }

                    0 -> {
                        unSelectBtn(binding.wfSendFileBtn)
                        selectBtn(binding.wfSendTextBtn)
                    }
                }
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                toClick()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun unSelectBtn(textView: AppCompatTextView) {
        textView.isFocusable = false
        textView.isFocusableInTouchMode = false
        textView.clearFocus()
        textView.setBackgroundResource(R.drawable.round_unselect_bg)
    }

    private fun selectBtn(textView: AppCompatTextView) {
        textView.isClickable = true
        textView.isFocusable = true
        textView.isFocusableInTouchMode = true
        textView.setTextColor(ContextCompat.getColor(this, R.color.green))
        textView.setBackgroundResource(R.drawable.round_select_bg)
        currentSelectId = textView.id
        textView.requestFocus()
    }

    private val mResultCallback = object : IResultCallback.Stub() {
        override fun onSuccess(result: Boolean) {
            Log.d(TAG, "mResultCallback onSuccess() result: $result")
        }

        override fun onFailed(code: Int, errormsg: String?) {
            Log.d(TAG, "mResultCallback onFailed(), code: $code, errormsg: $errormsg")
        }
    }

    private val logBuilder = StringBuilder(4000)
    private fun log(msg: String) {
        if (logBuilder.length > 4000) {
            logBuilder.delete(0, logBuilder.length)
        }
        logBuilder.insert(0, "$msg\n")
        lifecycleScope.launch {
            binding.tvLog.text = logBuilder.toString()
        }
    }

    fun copyFileFromAssetsToExternalStorage(context: Context, fileName: String) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            inputStream = context.assets.open(fileName)
            val outputFile = File(sdDownload, fileName)
            outputStream = FileOutputStream(outputFile)
            inputStream.copyTo(outputStream)
            outputStream.flush()
            // 文件写入成功
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.cancel()
        mFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        mBTFileOperator?.removeFileReceiveListener(bleFileReceiveListener)
        GlassSdk.getGlassAsrService()?.stopSpeech()
        GlassSdk.getGlassMediaService()?.stopAudioRecord(audioRecord)
        if (::huoVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(huoVoiceAction)
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}

