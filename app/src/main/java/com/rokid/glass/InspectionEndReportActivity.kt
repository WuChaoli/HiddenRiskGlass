package com.rokid.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.glass.hiddenrisk.InspectionFinishService
import com.rokid.glass.hiddenrisk.RetryRequestHandle
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class InspectionEndReportActivity : BaseGlassActivity() {

    private lateinit var scrollSavedHazardThumbs: ScrollView
    private lateinit var gridSavedHazardThumbs: GridLayout
    private lateinit var tvHazardCount: TextView
    private lateinit var operationGuideEnd: OperationGuideView
    private lateinit var bottomPromptEnd: BottomPromptView
    private lateinit var statusBarEnd: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val thumbnailBitmaps = mutableListOf<Bitmap>()
    private var headGestureSupported = false
    private var finishSubmitting = false
    private var finishRequestHandle: RetryRequestHandle? = null
    private var endReportTtsPlayed = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            statusBarEnd.updateTime()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_end_report)

        scrollSavedHazardThumbs = findViewById(R.id.scrollSavedHazardThumbs)
        gridSavedHazardThumbs = findViewById(R.id.gridSavedHazardThumbs)
        tvHazardCount = findViewById(R.id.tvHazardCount)
        operationGuideEnd = findViewById(R.id.operationGuideEnd)
        bottomPromptEnd = findViewById(R.id.bottomPromptEnd)
        statusBarEnd = findViewById(R.id.statusBarEnd)
        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()

        val savedHazardJpegs = InspectionWorkflowSession.savedHazardJpegs
        tvHazardCount.text = "分析出${savedHazardJpegs.size}条隐患"
        scrollSavedHazardThumbs.post {
            renderSavedHazardThumbnails(savedHazardJpegs)
        }

        operationGuideEnd.setGuide(
            title = "操作指引",
            content = if (headGestureSupported) {
                "说出\"结束\"\n说出\"退出\"\n单击 结束\n双击 退出\n点头 结束\n摇头 退出"
            } else {
                "说出\"结束\"\n说出\"退出\"\n单击 结束\n双击 退出"
            }
        )
        bottomPromptEnd.setPrompt(
            title = "请确认是否结束本次巡检？"
        )

        startTimeAndBatteryUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (!endReportTtsPlayed) {
            endReportTtsPlayed = OfflineTtsPlayer.speak(
                ownerTag = TAG,
                message = getString(R.string.offline_tts_inspection_end_report),
            )
        }
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        finishRequestHandle?.cancel()
        finishRequestHandle = null
        stopTimeAndBatteryUpdate()
        inputSession.release()
        clearThumbnailBitmaps()
        super.onDestroy()
    }

    private fun startTimeAndBatteryUpdate() {
        statusBarEnd.updateTime()
        uiHandler.post(timeUpdateRunnable)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        val batteryPct = (level * 100 / scale.toFloat()).toInt()
                        statusBarEnd.setBatteryPercent(batteryPct)
                    }
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun stopTimeAndBatteryUpdate() {
        uiHandler.removeCallbacks(timeUpdateRunnable)
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "结束",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("结束", "jie shu"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
            ) {
                submitFinishInspection()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "退出",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                ),
            ) {
                returnHomeDirectly()
            },
        )
    }

    private fun submitFinishInspection() {
        if (finishSubmitting) return
        val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
        val validationMessage = when {
            enterprisePayload == null -> "缺少企业上下文，请先扫码后再试"
            enterprisePayload.apiBaseUrl.isBlank() -> "缺少接口地址，请重新扫码后再试"
            enterprisePayload.authCode.isBlank() -> "缺少鉴权码，请重新扫码后再试"
            enterprisePayload.objectId.isBlank() -> "缺少对象 ID，请重新扫码后再试"
            enterprisePayload.userId.isBlank() -> "缺少用户 ID，请重新扫码后再试"
            else -> null
        }
        if (validationMessage != null) {
            bottomPromptEnd.setSubtitle(validationMessage)
            return
        }
        val confirmedPayload = enterprisePayload ?: return
        finishSubmitting = true
        bottomPromptEnd.setSubtitle("正在提交结束请求...")
        inputSession.updateActions(emptyList())
        finishRequestHandle = InspectionFinishService.finishInspection(
            baseUrl = confirmedPayload.apiBaseUrl,
            authCode = confirmedPayload.authCode,
            objectId = confirmedPayload.objectId,
            userId = confirmedPayload.userId,
            customParam = confirmedPayload.extraField,
            callback = object : InspectionFinishService.Callback {
                override fun onSuccess() {
                    if (isFinishing || isDestroyed) return
                    finishRequestHandle = null
                    returnHomeDirectly()
                }

                override fun onError(message: String) {
                    finishRequestHandle = null
                    finishSubmitting = false
                    bottomPromptEnd.setSubtitle(message)
                    inputSession.updateActions(buildInputActions())
                }
            },
        )
    }

    private fun returnHomeDirectly() {
        if (isFinishing || isDestroyed) return
        finishRequestHandle?.cancel()
        finishRequestHandle = null
        finishSubmitting = false
        InspectionWorkflowSession.resetAll()
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun renderSavedHazardThumbnails(savedHazardJpegs: List<ByteArray>) {
        clearThumbnailBitmaps()
        gridSavedHazardThumbs.removeAllViews()
        if (savedHazardJpegs.isEmpty()) {
            scrollSavedHazardThumbs.visibility = View.GONE
            return
        }

        scrollSavedHazardThumbs.visibility = View.VISIBLE
        val thumbWidth = dp(36)
        val thumbHeight = dp(27)
        val thumbGap = dp(3)
        val availableWidth = scrollSavedHazardThumbs.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / 2)
        val columns = maxOf(1, availableWidth / (thumbWidth + thumbGap))
        gridSavedHazardThumbs.columnCount = columns

        savedHazardJpegs.forEachIndexed { index, jpegBytes ->
            val thumbnail = decodeSampledBitmap(
                jpegBytes = jpegBytes,
                targetWidth = thumbWidth,
                targetHeight = thumbHeight,
            ) ?: return@forEachIndexed
            thumbnailBitmaps.add(thumbnail)
            gridSavedHazardThumbs.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(thumbnail)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = thumbWidth
                        height = thumbHeight
                        setMargins(
                            if (index % columns == 0) 0 else thumbGap,
                            if (index < columns) 0 else thumbGap,
                            0,
                            0,
                        )
                    }
                }
            )
        }

        val rows = (savedHazardJpegs.size + columns - 1) / columns
        val desiredHeight = rows * thumbHeight + maxOf(0, rows - 1) * thumbGap
        val maxHeight = resources.displayMetrics.heightPixels / 2
        scrollSavedHazardThumbs.layoutParams = scrollSavedHazardThumbs.layoutParams.apply {
            height = if (desiredHeight > maxHeight) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scrollSavedHazardThumbs.requestLayout()
    }

    private fun clearThumbnailBitmaps() {
        thumbnailBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        thumbnailBitmaps.clear()
    }

    private fun decodeSampledBitmap(
        jpegBytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateInSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            )
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return sampleSize
        }
        while (sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val TAG = "InspectionEndReport"
    }
}
