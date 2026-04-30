package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.annotation.StringRes
import com.rokid.glass.component.GlassStatusBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.EnterpriseQrScanActivity
import com.rokid.glass.WifiQrScanActivity
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import kotlin.math.max

/**
 * 巡检加载页面。
 * 执行实际的 SDK 初始化、相机预热，完成后直接执行 Wi-Fi 判定分流。
 */
class InspectionLoadingActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "InspectionLoading"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val PROGRESS_STEP_DELAY_MS = 24L
        private const val SUBTITLE_FRAME_DELAY_MS = 320L
        private const val COMPLETE_HOLD_DELAY_MS = 400L
        private const val STATUS_UPDATE_DELAY_MS = 1000L
    }

    // 加载阶段枚举
    private enum class LoadingStage {
        IDLE,           // 初始状态
        SDK_INIT,       // SDK 初始化
        CAMERA_INIT,    // 相机初始化
        COMPLETE,       // 加载完成，立即分流
        ERROR           // 错误状态
    }

    // UI 组件
    private lateinit var layoutLoading: LinearLayout
    private lateinit var ivLoadingTrack: ImageView
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
    private lateinit var statusBar: GlassStatusBar

    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentProgress = 0
    private var targetProgress = 0
    private var progressRunnablePosted = false
    private var loadingStage = LoadingStage.IDLE
    private var mediaPermissionRequested = false
    private var activityDestroyed = false
    private var cameraInitStarted = false
    private var initializationCompleted = false
    private var completionUiCommitted = false
    private var cameraSessionGeneration = 0L
    private var debugSnapshotMode = false
    private var debugAnimateMode = false
    private var subtitleBaseText = ""
    private var subtitleFrame = 0
    private var subtitleAnimating = false
    private var batteryReceiver: BroadcastReceiver? = null
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }

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
            progressRunnablePosted = false
            if (currentProgress < targetProgress) {
                currentProgress++
                progressBar.progress = currentProgress
                tvProgressPercent.text = "${currentProgress}%"
                postProgressTick(PROGRESS_STEP_DELAY_MS)
            } else if (loadingStage == LoadingStage.COMPLETE && currentProgress >= 100) {
                commitCompletionUi()
            }
        }
    }

    private val subtitleRunnable = object : Runnable {
        override fun run() {
            if (activityDestroyed || !subtitleAnimating) return
            subtitleFrame = (subtitleFrame + 1) % 4
            tvLoadingSubtitle.text = when (subtitleFrame) {
                0 -> subtitleBaseText
                1 -> "$subtitleBaseText."
                2 -> "$subtitleBaseText.."
                else -> "$subtitleBaseText..."
            }
            uiHandler.postDelayed(this, SUBTITLE_FRAME_DELAY_MS)
        }
    }

    private val startCameraInitRunnable = Runnable {
        startCameraInit()
    }

    private val finishNavigationRunnable = Runnable {
        if (activityDestroyed || !initializationCompleted || loadingStage != LoadingStage.COMPLETE) {
            return@Runnable
        }
        InspectionSession.markInitialized()
        navigateToInspection()
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            if (activityDestroyed) return
            updateCurrentTime()
            uiHandler.postDelayed(this, STATUS_UPDATE_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_loading)

        initViews()
        debugSnapshotMode = intent.getBooleanExtra("debug_snapshot", false)
        debugAnimateMode = intent.getBooleanExtra("debug_animate", false)
        if (debugSnapshotMode) {
            tvLoadingTitle.setText(R.string.ai_inspection_loading_title)
            val debugProgress = intent.getIntExtra("debug_progress", 30)
            progressBar.progress = debugProgress
            tvProgressPercent.text = "$debugProgress%"
            if (debugAnimateMode) {
                currentProgress = debugProgress
                targetProgress = debugProgress
                startSpinner()
                setSubtitle(intent.getStringExtra("debug_subtitle") ?: getString(R.string.ai_inspection_loading_subtitle), animated = true)
            } else {
                stopSpinner()
                tvLoadingSubtitle.text = intent.getStringExtra("debug_subtitle")
                    ?: getString(R.string.ai_inspection_loading_subtitle)
            }
            return
        }

        startSpinner()

        // 开始进度动画
        animateProgressTo(10)

        // 初始化 SDK
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
        refreshInputActions()

        // 检查权限并开始初始化流程
        if (hasRequiredPermissions()) {
            startInitializationFlow()
        } else {
            requestPermissionsIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
        startStatusBarUpdates()
        if (debugSnapshotMode) return
    }

    override fun onPause() {
        stopStatusBarUpdates()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        activityDestroyed = true
        inputSession.release()
        super.onDestroy()
        stopLoadingUi()
        RokidSdkManager.removeListener(this)
        InspectionCameraCoordinator.release(CameraOwner.LOADING, reason = "loading_on_destroy")

        // 如果初始化未完成且出现错误，清理资源
        if (loadingStage == LoadingStage.ERROR) {
            InspectionSession.release()
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
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
            showError(getString(R.string.ai_inspection_loading_missing_camera_permission))
        }
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        Log.i(TAG, "SDK state=$state")
        uiHandler.post {
            when (state) {
                RokidSdkManager.SdkState.READY -> {
                    setSubtitle(getString(R.string.ai_inspection_loading_subtitle_sdk_ready), animated = true)
                    animateProgressTo(30)
                    // 延迟一下确保 SDK 完全就绪
                    uiHandler.removeCallbacks(startCameraInitRunnable)
                    uiHandler.postDelayed(startCameraInitRunnable, 200)
                }
                RokidSdkManager.SdkState.FAILED -> {
                    showError(RokidSdkManager.lastErrorMessage ?: getString(R.string.ai_inspection_loading_error_sdk_init))
                }
                else -> {}
            }
            refreshInputActions()
        }
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("loading_retry"),
                label = getString(R.string.ai_inspection_input_label_retry_init),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                ),
                enabled = { loadingStage == LoadingStage.ERROR },
            ) {
                retryInitialization()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = getString(R.string.ai_inspection_input_label_exit),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                    voiceTrigger(R.string.ai_inspection_voice_exit, "tui chu"),
                ),
            ) {
                finish()
            },
        )
    }

    private fun voiceTrigger(@StringRes textRes: Int, pinyin: String): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun initViews() {
        ivLoadingTrack = findViewById(R.id.ivLoadingTrack)
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

        statusBar = findViewById(R.id.statusBar)
        updateCurrentTime()
        updateBatteryLevel()
    }

    private fun startStatusBarUpdates() {
        updateCurrentTime()
        uiHandler.removeCallbacks(statusUpdateRunnable)
        uiHandler.post(statusUpdateRunnable)

        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateBatteryLevel(intent)
                }
            }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun stopStatusBarUpdates() {
        uiHandler.removeCallbacks(statusUpdateRunnable)
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
    }

    private fun updateCurrentTime() {
        statusBar.updateTime()
    }

    /**
     * 获取当前电池电量并更新电池图标填充
     */
    private fun updateBatteryLevel(intent: Intent? = null) {
        val batteryStatus = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { batteryIntent ->
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBar.setBatteryPercent(batteryPct)
            }
        }
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
        setSubtitle(getString(R.string.ai_inspection_loading_subtitle_sdk_init), animated = true)
        refreshInputActions()

        // SDK 初始化由 RokidSdkManager 处理，等待回调
        // 如果 SDK 已经就绪，直接开始相机预热。
        if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
            setSubtitle(getString(R.string.ai_inspection_loading_subtitle_sdk_ready), animated = true)
            animateProgressTo(30)
            startCameraInit()
        }
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
        setSubtitle(getString(R.string.ai_inspection_loading_subtitle_camera_init), animated = true)
        animateProgressTo(90)
        refreshInputActions()

        var requestGeneration = 0L
        requestGeneration = InspectionCameraCoordinator.acquire(
            owner = CameraOwner.LOADING,
            needPreview = false,
        ) { success ->
            if (requestGeneration != InspectionCameraCoordinator.getGeneration()) {
                Log.i(
                    TAG,
                    "ignore stale loading acquire callback requestGeneration=$requestGeneration currentGeneration=${InspectionCameraCoordinator.getGeneration()} success=$success",
                )
                return@acquire
            }
            uiHandler.post {
                if (activityDestroyed) return@post
                cameraSessionGeneration = requestGeneration
                if (success) {
                    InspectionSession.markInitialized()
                    onInitializationComplete()
                } else {
                    showError(InspectionSession.errorMessage ?: getString(R.string.ai_inspection_loading_error_frame_stream))
                }
            }
        }
        cameraSessionGeneration = requestGeneration
    }

    private fun onInitializationComplete() {
        if (initializationCompleted || activityDestroyed) {
            return
        }

        initializationCompleted = true
        loadingStage = LoadingStage.COMPLETE
        completionUiCommitted = false
        animateProgressTo(100)
        if (currentProgress >= 100) {
            commitCompletionUi()
        }
    }

    private fun showError(message: String) {
        loadingStage = LoadingStage.ERROR
        uiHandler.removeCallbacks(finishNavigationRunnable)
        stopSubtitleAnimation()

        // 隐藏加载视图
        layoutLoading.visibility = View.GONE

        // 显示错误视图
        layoutError.visibility = View.VISIBLE
        tvErrorMessage.text = message

        // 停止动画
        stopSpinner()
        refreshInputActions()
    }

    private fun retryInitialization() {
        stopLoadingUi()

        // 重置状态
        InspectionSession.reset()
        loadingStage = LoadingStage.IDLE
        currentProgress = 0
        targetProgress = 0
        progressRunnablePosted = false
        cameraInitStarted = false
        initializationCompleted = false
        completionUiCommitted = false
        progressBar.progress = 0
        tvProgressPercent.text = "0%"

        // 隐藏错误视图，显示加载视图
        layoutError.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        // 恢复 UI
        ivLoadingSpinner.visibility = View.VISIBLE
        ivLoadingTrack.visibility = View.VISIBLE
        ivLoadingComplete.visibility = View.GONE
        tvLoadingTitle.text = getString(R.string.ai_inspection_loading_title)
        tvLoadingSubtitle.visibility = View.VISIBLE
        layoutGuideContent.visibility = View.VISIBLE
        tvConfirmPrompt.visibility = View.GONE

        // 重新开始
        startSpinner()
        animateProgressTo(10)
        startInitializationFlow()
        refreshInputActions()
    }

    private fun navigateToInspection() {
        InspectionWorkflowSession.beginInspection(
            InspectionBackendSessionId.create(RokidSdkManager.getSerialNumber(), prefix = "inspection"),
        )
        val wifiConnected = SystemStateUtils.getCurrentWifiSsid(this) != null
        InspectionWorkflowSession.updateMode(wifiConnected)
        val targetIntent = if (!InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()) {
            Intent(this, AiInspectionActivity::class.java)
        } else if (wifiConnected) {
            Intent(this, EnterpriseQrScanActivity::class.java)
        } else {
            Intent(this, WifiQrScanActivity::class.java).apply {
                putExtra(WifiQrScanActivity.EXTRA_NEXT_AFTER_SUCCESS, EnterpriseQrScanActivity::class.java.name)
            }
        }
        // 企业/Wi-Fi 扫码页复用加载页已预热的共享帧流，进入后只检测是否可用。
        startActivity(targetIntent)
        finish()
    }

    private fun animateProgressTo(target: Int) {
        val normalizedTarget = target.coerceIn(0, 100)
        targetProgress = max(targetProgress, normalizedTarget)
        if (currentProgress >= targetProgress) {
            if (loadingStage == LoadingStage.COMPLETE && currentProgress >= 100) {
                commitCompletionUi()
            }
            return
        }
        postProgressTick()
    }

    private fun postProgressTick(delayMs: Long = 0L) {
        if (activityDestroyed || progressRunnablePosted) return
        progressRunnablePosted = true
        if (delayMs <= 0L) {
            uiHandler.post(progressRunnable)
        } else {
            uiHandler.postDelayed(progressRunnable, delayMs)
        }
    }

    private fun setSubtitle(baseText: String, animated: Boolean) {
        subtitleBaseText = baseText
        subtitleFrame = 3
        subtitleAnimating = animated
        uiHandler.removeCallbacks(subtitleRunnable)
        tvLoadingSubtitle.text = if (animated) "$baseText..." else baseText
        if (animated) {
            uiHandler.postDelayed(subtitleRunnable, SUBTITLE_FRAME_DELAY_MS)
        }
    }

    private fun stopSubtitleAnimation() {
        subtitleAnimating = false
        uiHandler.removeCallbacks(subtitleRunnable)
    }

    private fun startSpinner() {
        ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        ivLoadingSpinner.visibility = View.VISIBLE
        ivLoadingTrack.visibility = View.VISIBLE
        ivLoadingComplete.visibility = View.GONE
    }

    private fun stopSpinner() {
        ivLoadingSpinner.clearAnimation()
    }

    private fun commitCompletionUi() {
        if (completionUiCommitted || activityDestroyed) return
        completionUiCommitted = true
        stopSpinner()
        stopSubtitleAnimation()
        setSubtitle(getString(R.string.ai_inspection_loading_subtitle_complete), animated = false)
        uiHandler.removeCallbacks(finishNavigationRunnable)
        uiHandler.postDelayed(finishNavigationRunnable, COMPLETE_HOLD_DELAY_MS)
    }

    private fun stopLoadingUi() {
        stopSpinner()
        stopSubtitleAnimation()
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.removeCallbacks(startCameraInitRunnable)
        uiHandler.removeCallbacks(finishNavigationRunnable)
        progressRunnablePosted = false
        cameraSessionGeneration = 0L
    }
}
