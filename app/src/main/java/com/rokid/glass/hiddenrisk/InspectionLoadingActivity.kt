package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback

/**
 * 巡检加载页面。
 * 执行实际的 SDK 初始化、模型加载、相机预热，完成后等待用户确认再跳转。
 */
class InspectionLoadingActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "InspectionLoading"
        private const val REQUEST_MEDIA_PERMISSION = 201
    }

    // 加载阶段枚举
    private enum class LoadingStage {
        IDLE,           // 初始状态
        SDK_INIT,       // SDK 初始化
        MODEL_LOAD,     // 模型加载
        CAMERA_INIT,    // 相机初始化
        COMPLETE,       // 加载完成，等待确认
        ERROR           // 错误状态
    }

    // UI 组件
    private lateinit var layoutLoading: LinearLayout
    private lateinit var ivLoadingSpinner: ImageView
    private lateinit var ivLoadingComplete: ImageView
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvLoadingHint: TextView
    private lateinit var layoutGuideCard: LinearLayout
    private lateinit var layoutGuideContent: LinearLayout
    private lateinit var tvConfirmPrompt: TextView
    private lateinit var tvErrorMessage: TextView
    private lateinit var layoutError: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentProgress = 0
    private var targetProgress = 0
    private var loadingStage = LoadingStage.IDLE
    private var mediaPermissionRequested = false
    private var activityDestroyed = false
    private var modelLoadStarted = false
    private var cameraInitStarted = false
    private var initializationCompleted = false

    // 转圈动画
    private val loadingRotateAnimation: RotateAnimation by lazy {
        RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 900L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    // 进度更新 Runnable
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (currentProgress < targetProgress) {
                currentProgress++
                progressBar.progress = currentProgress
                tvProgressPercent.text = "${currentProgress}%"
                uiHandler.postDelayed(this, 30L)
            }
        }
    }

    private val startModelLoadRunnable = Runnable {
        startModelLoading()
    }

    // 语音命令：开始巡检
    private val startVoiceAction = VoiceAction("开始", "kai shi", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (loadingStage == LoadingStage.COMPLETE) {
                    navigateToInspection()
                }
            }
        }
    })

    // 语音命令：退出
    private val exitVoiceAction = VoiceAction("退出", "tui chu", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { finish() }
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_loading)

        initViews()

        // 启动转圈动画
        ivLoadingSpinner.startAnimation(loadingRotateAnimation)

        // 开始进度动画
        animateProgressTo(10)

        // 初始化 SDK
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()

        // 检查权限并开始初始化流程
        if (hasRequiredPermissions()) {
            startInitializationFlow()
        } else {
            requestPermissionsIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册语音命令
        registerVoiceCommands()
    }

    override fun onPause() {
        super.onPause()
        // 注销语音命令
        unregisterVoiceCommands()
    }

    override fun onDestroy() {
        activityDestroyed = true
        super.onDestroy()
        ivLoadingSpinner.clearAnimation()
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.removeCallbacks(startModelLoadRunnable)
        RokidSdkManager.removeListener(this)

        // 如果初始化未完成且出现错误，清理资源
        if (loadingStage == LoadingStage.ERROR) {
            InspectionSession.release()
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                when (loadingStage) {
                    LoadingStage.COMPLETE -> {
                        navigateToInspection()
                        true
                    }
                    LoadingStage.ERROR -> {
                        // 错误状态下点击重试
                        retryInitialization()
                        true
                    }
                    else -> false
                }
            }
            GlassKeyEvent.KEYCODE_BACK,
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            startInitializationFlow()
        } else {
            showError("缺少相机权限，无法继续")
        }
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        Log.i(TAG, "SDK state=$state")
        uiHandler.post {
            when (state) {
                RokidSdkManager.SdkState.READY -> {
                    tvLoadingSubtitle.text = "SDK 就绪，正在加载模型…"
                    animateProgressTo(30)
                    // 延迟一下确保 SDK 完全就绪
                    uiHandler.removeCallbacks(startModelLoadRunnable)
                    uiHandler.postDelayed(startModelLoadRunnable, 200)
                }
                RokidSdkManager.SdkState.FAILED -> {
                    showError(RokidSdkManager.lastErrorMessage ?: "SDK 初始化失败")
                }
                else -> {}
            }
        }
    }

    private fun initViews() {
        ivLoadingSpinner = findViewById(R.id.ivLoadingSpinner)
        ivLoadingComplete = findViewById(R.id.ivLoadingComplete)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)
        progressBar = findViewById(R.id.progressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvLoadingHint = findViewById(R.id.tvLoadingHint)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutGuideCard = findViewById(R.id.layoutGuideCard)
        layoutGuideContent = findViewById(R.id.layoutGuideContent)
        tvConfirmPrompt = findViewById(R.id.tvConfirmPrompt)

        // 错误视图
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        layoutError = findViewById(R.id.layoutError)
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun requestPermissionsIfNeeded() {
        if (!mediaPermissionRequested) {
            mediaPermissionRequested = true
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions(),
                REQUEST_MEDIA_PERMISSION
            )
        }
    }

    private fun startInitializationFlow() {
        loadingStage = LoadingStage.SDK_INIT
        tvLoadingSubtitle.text = "正在初始化 SDK…"

        // SDK 初始化由 RokidSdkManager 处理，等待回调
        // 如果 SDK 已经就绪，直接开始模型加载
        if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
            tvLoadingSubtitle.text = "SDK 就绪，正在加载模型…"
            animateProgressTo(30)
            startModelLoading()
        }
    }

    private fun startModelLoading() {
        if (activityDestroyed || modelLoadStarted || initializationCompleted || loadingStage == LoadingStage.ERROR) {
            Log.i(
                TAG,
                "skip startModelLoading destroyed=$activityDestroyed modelLoadStarted=$modelLoadStarted complete=$initializationCompleted stage=$loadingStage"
            )
            return
        }

        modelLoadStarted = true
        loadingStage = LoadingStage.MODEL_LOAD
        tvLoadingSubtitle.text = "正在加载检测模型…"
        animateProgressTo(50)

        // 创建 NCNN 实例
        if (!InspectionSession.createNcnnInstance()) {
            showError(InspectionSession.errorMessage ?: "NCNN 初始化失败")
            return
        }

        // 在后台线程加载模型
        Thread {
            val success = InspectionSession.loadModel(assets)
            uiHandler.post {
                if (activityDestroyed) return@post
                if (success) {
                    tvLoadingSubtitle.text = "模型加载完成，准备相机…"
                    animateProgressTo(70)
                    startCameraInit()
                } else {
                    showError(InspectionSession.errorMessage ?: "模型加载失败")
                }
            }
        }.start()
    }

    private fun startCameraInit() {
        if (activityDestroyed || cameraInitStarted || initializationCompleted || loadingStage == LoadingStage.ERROR) {
            Log.i(
                TAG,
                "skip startCameraInit destroyed=$activityDestroyed cameraInitStarted=$cameraInitStarted complete=$initializationCompleted stage=$loadingStage"
            )
            return
        }

        cameraInitStarted = true
        loadingStage = LoadingStage.CAMERA_INIT
        tvLoadingSubtitle.text = "正在初始化相机…"

        InspectionSession.initCamera { success ->
            uiHandler.post {
                if (activityDestroyed) return@post
                if (success) {
                    onInitializationComplete()
                } else {
                    showError(InspectionSession.errorMessage ?: "相机初始化失败")
                }
            }
        }
    }

    private fun onInitializationComplete() {
        if (initializationCompleted || activityDestroyed) {
            return
        }

        initializationCompleted = true
        loadingStage = LoadingStage.COMPLETE
        animateProgressTo(100)

        // UI 切换到完成状态
        ivLoadingSpinner.clearAnimation()
        ivLoadingSpinner.visibility = View.GONE
        ivLoadingComplete.visibility = View.VISIBLE
        tvLoadingTitle.text = "系统初始化完成"
        tvLoadingSubtitle.visibility = View.INVISIBLE
        layoutGuideContent.visibility = View.GONE
        tvConfirmPrompt.visibility = View.VISIBLE
        tvLoadingHint.text = "单击或说\"开始\"进入巡检"

        // 标记初始化完成
        InspectionSession.markInitialized()

        // 重新注册语音命令（启用"开始"指令）
        unregisterVoiceCommands()
        registerVoiceCommands()
    }

    private fun showError(message: String) {
        loadingStage = LoadingStage.ERROR

        // 隐藏加载视图
        layoutLoading.visibility = View.GONE

        // 显示错误视图
        layoutError.visibility = View.VISIBLE
        tvErrorMessage.text = message

        // 停止动画
        ivLoadingSpinner.clearAnimation()
    }

    private fun retryInitialization() {
        uiHandler.removeCallbacks(startModelLoadRunnable)

        // 重置状态
        InspectionSession.reset()
        loadingStage = LoadingStage.IDLE
        currentProgress = 0
        targetProgress = 0
        modelLoadStarted = false
        cameraInitStarted = false
        initializationCompleted = false
        progressBar.progress = 0
        tvProgressPercent.text = "0%"

        // 隐藏错误视图，显示加载视图
        layoutError.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        // 恢复 UI
        ivLoadingSpinner.visibility = View.VISIBLE
        ivLoadingComplete.visibility = View.GONE
        tvLoadingTitle.text = getString(R.string.ai_inspection_loading_title)
        tvLoadingSubtitle.visibility = View.VISIBLE
        layoutGuideContent.visibility = View.VISIBLE
        tvConfirmPrompt.visibility = View.GONE

        // 重新开始
        ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        animateProgressTo(10)
        startInitializationFlow()
    }

    private fun navigateToInspection() {
        startActivity(Intent(this, AiInspectionActivity::class.java))
        finish()
    }

    private fun animateProgressTo(target: Int) {
        targetProgress = target
        uiHandler.post(progressRunnable)
    }

    private fun registerVoiceCommands() {
        val offlineCmdService = runCatching { GlassSdk.getGlassOfflineCmdService() }.getOrNull()
            ?: return

        // 根据当前状态注册不同的语音命令
        when (loadingStage) {
            LoadingStage.COMPLETE -> {
                offlineCmdService.add(startVoiceAction)
            }
            else -> {}
        }
        offlineCmdService.add(exitVoiceAction)
    }

    private fun unregisterVoiceCommands() {
        val offlineCmdService = runCatching { GlassSdk.getGlassOfflineCmdService() }.getOrNull()
            ?: return

        offlineCmdService.remove(startVoiceAction)
        offlineCmdService.remove(exitVoiceAction)
    }
}
