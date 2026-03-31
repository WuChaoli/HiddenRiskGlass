package com.rokid.glass

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.rokid.glesse.databinding.ActivityTestMediaBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.uitls.log.L
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.media.PreviewResolution
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.system.server.media.callback.AudioCallback
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.media.listener.IMediaStateLister
import com.rokid.security.system.server.message.callback.IResultCallback
import com.rokid.ui.view.base.BaseGlassActivity
import java.io.File

class TestMediaActivity : BaseGlassActivity() {

    private lateinit var binding: ActivityTestMediaBinding
    private var TAG = "TestMediaActivity::"
    private var zoom = 1

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return super.onGlassKeyEvent(keyEvent)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        GlassSdk.getGlassMediaService()?.addPhotoCallback(mPhotoFileCallback)
//        val fileName = "test_${System.currentTimeMillis()}.png"
//        val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//        val file = File(publicPicturesDir, fileName)
//        GlassSDK.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P,file.absolutePath)

        GlassSdk.getGlassMediaService()?.setMediaStateLister(mICameraStateLister)

        binding.btPhoto480P.setOnClickListener {
            L.d(TAG, "btPhoto-->takePhoto")
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_480P, file.absolutePath)
        }

        binding.btPhoto720P.setOnClickListener {
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_720P, file.absolutePath)
        }

        binding.btPhoto1080P.setOnClickListener {
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_1080P, file.absolutePath)
        }

        binding.btPhoto4K.setOnClickListener {
            val fileName = "test_${System.currentTimeMillis()}.png"
            val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(publicPicturesDir, fileName)
            GlassSdk.getGlassMediaService()?.takePhoto(PhotoResolution.RESOLUTION_4K, file.absolutePath)
        }

        binding.btStartRecord720P.setOnClickListener {
            val min = 60
            val enableAudio = true
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
            L.d(TAG, "onCreate: path = $path")
            val recordConfig = RecordConfig(
                path,
                min,
                enableAudio,
//                width = 1080,
//                height = 1920

//                width = 720,
//                height = 1280

                width = PreviewResolution.ResolutionInfo_720P.width,
                height = PreviewResolution.ResolutionInfo_720P.height
            )
            GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
        }

        binding.btStartRecord1080P.setOnClickListener {
            val min = 60
            val enableAudio = true
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
            L.d(TAG, "onCreate: path = $path")
            val recordConfig = RecordConfig(
                path,
                min,
                enableAudio,
                width = PreviewResolution.ResolutionInfo_1080P.width,
                height = PreviewResolution.ResolutionInfo_1080P.height
            )
            GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
        }

        binding.btStopRecord.setOnClickListener {
            Log.d(TAG,"----停止视频刻录")
            GlassSdk.getGlassMediaService()?.stopRecord()
        }

        binding.btSendVideo.setOnClickListener {
            GlassSdk.getGlassMessageService()?.sendVideoStreamDataV2(object : IResultCallback.Default() {
                override fun onSuccess(result: Boolean) {
                    super.onSuccess(result)
                    Log.i(TAG, "sendVideoStreamData --onSuccess: ")
                }

                override fun onFailed(code: Int, errormsg: String?) {
                    super.onFailed(code, errormsg)
                    Log.i(TAG, "sendVideoStreamData -- onFailed:  code = $code, errormsg = $errormsg")
                }
            })
        }

        binding.btStopSendVideo.setOnClickListener {
            GlassSdk.getGlassMessageService()?.stopVideoStreamData()
        }
        binding.btnSetZoom.setOnClickListener {
            binding.btnSetZoom.text = "zoom: $zoom"
            GlassSdk.getGlassMediaService()?.zoomCamera(zoom)
            zoom++
            if (zoom > 3) {
                zoom = 1
            }
        }
        binding.btnRecordAudio.setOnClickListener {
            GlassSdk.getGlassMediaService()?.startAudioRecord(mAudioCallback)
        }
        binding.btnStopRecordAudio.setOnClickListener {
            GlassSdk.getGlassMediaService()?.stopAudioRecord(mAudioCallback)
        }
    }

    private val mAudioCallback = object : AudioCallback.Stub() {
        override fun onAudioStream(buffer: ByteArray?, bufferLen: Int) {
            L.d(TAG, "onAudioStream-->bufferLen: $bufferLen")
        }

        override fun getCallbackId(): String? {
            return "10001"
        }
    }

    private val mPhotoFileCallback = object : PhotoFileCallback.Stub() {
        override fun onTakePhoto(path: String) {
            L.d(TAG, "onTakePhoto-->path = $path")
        }

        override fun getCallbackId(): String? {
            return "10002"
        }

        override fun onTakePhotoV2(path: String, width: Int, height: Int) {
            L.d(TAG, "onTakePhoto--> width = $width,height = $height, path = $path")
        }
    }

    private val mICameraStateLister = object : IMediaStateLister.Stub() {
        override fun onCameraResolutionChange(width: Int, height: Int) {
            Log.i(TAG, "onCameraResolutionChange: width = $width, height = $height")
        }

        override fun onCameraError(code: Int, errorMsg: String) {
            Log.e(TAG, "onCameraError")
        }
    }

    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            L.i(TAG, "onError: ")
        }

        override fun onFinish() {
            L.i(TAG, "onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            L.i(TAG, "onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            L.i(TAG, "onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            L.i(TAG, "onStart: ")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: ")
        GlassSdk.getGlassMediaService()?.removePhotoCallback(mPhotoFileCallback)
        GlassSdk.getGlassMediaService()?.removeMediaStateLister(mICameraStateLister)
        GlassSdk.getGlassMediaService()?.stopAudioRecord(mAudioCallback)
        GlassSdk.getGlassMediaService()?.stopRecord()
//        GlobalMediaManager.closeCamera()
    }

}