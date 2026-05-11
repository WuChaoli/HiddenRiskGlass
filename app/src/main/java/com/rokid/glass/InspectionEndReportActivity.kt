package com.rokid.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
import androidx.annotation.StringRes
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.InspectionBackgroundUploadQueue
import com.rokid.glass.hiddenrisk.InspectionBackgroundUploadService
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class InspectionEndReportActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "InspectionEndReport"
        private const val SHOW_END_REPORT_HAZARD_COUNT = true
    }

    private lateinit var scrollSavedHazardThumbs: ScrollView
    private lateinit var gridSavedHazardThumbs: GridLayout
    private lateinit var tvHazardCount: TextView
    private lateinit var operationGuideEnd: OperationGuideView
    private lateinit var bottomPromptEnd: BottomPromptView
    private lateinit var statusBarEnd: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val thumbnailBitmaps = mutableListOf<Bitmap>()
    private var finishExitTriggered = false
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
        val savedHazardJpegs = InspectionWorkflowSession.buildEndReportThumbnails()
        bindHazardSummaryText()
        scrollSavedHazardThumbs.post {
            renderSavedHazardThumbnails(savedHazardJpegs)
        }

        operationGuideEnd.setGuide(
            content = getString(R.string.ai_inspection_operation_guide_confirm_return),
        )
        bottomPromptEnd.setPrompt(
            title = getString(R.string.ai_inspection_end_report_prompt_title),
            subtitle = getString(R.string.ai_inspection_end_report_prompt_subtitle),
        )
        hideActionPrompts()

        startTimeAndBatteryUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (!endReportTtsPlayed) {
            endReportTtsPlayed = OfflineTtsPlayer.play(
                context = this,
                ownerTag = TAG,
                audioResId = R.raw.inspection_end,
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
        stopTimeAndBatteryUpdate()
        OfflineTtsPlayer.release(TAG)
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

    private fun hideActionPrompts() {
        operationGuideEnd.visibility = View.GONE
        bottomPromptEnd.visibility = View.GONE
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildSingleStepTriggers(),
            ) {
                submitFinishInspectionInBackground()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildDoubleStepTriggers(),
            ) {
                returnToMenuDirectly()
            },
        )
    }

    private fun buildSingleStepTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            voiceTrigger(R.string.ai_inspection_voice_confirm, "que ren"),
            voiceTrigger(R.string.ai_inspection_voice_confirm_alias, "que ding"),
            voiceTrigger(R.string.ai_inspection_voice_continue_alias, "ji xu"),
        )
    }

    private fun buildDoubleStepTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            voiceTrigger(R.string.ai_inspection_voice_return, "fan hui"),
            voiceTrigger(R.string.ai_inspection_voice_cancel_alias, "qu xiao"),
        )
    }

    private fun voiceTrigger(@StringRes textRes: Int, pinyin: String): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    /**
     * 结束页右侧标题支持按开关切换：
     * 默认显示“确认结束巡检”，如需恢复数量展示可打开常量开关。
     */
    private fun bindHazardSummaryText() {
        tvHazardCount.text = if (SHOW_END_REPORT_HAZARD_COUNT) {
            getString(
                R.string.ai_inspection_end_report_hazard_count,
                InspectionWorkflowSession.buildEndReportHazardCount(),
            )
        } else {
            getString(R.string.ai_inspection_end_report_confirm_finish_title)
        }
    }

    private fun submitFinishInspectionInBackground() {
        if (finishExitTriggered) return
        finishExitTriggered = true
        val saveResultConfig = InspectionConfigRepository.get().network.saveResultApi
        val enterpriseFlowEnabled = InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()
        val useFallbackPayload =
            !enterpriseFlowEnabled && saveResultConfig.allowUploadWhenEnterpriseFlowDisabled
        if (!enterpriseFlowEnabled && !useFallbackPayload) {
            exitAppAfterFinishSubmitted()
            return
        }
        val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
        if (enterprisePayload == null && !useFallbackPayload) {
            android.util.Log.w(TAG, "skip finish background upload: missing enterprise payload")
            exitAppAfterFinishSubmitted()
            return
        }
        val fallbackPayload = saveResultConfig.fallbackUploadPayload
        val baseUrl = enterprisePayload?.apiBaseUrl.orEmpty()
        val authCode = enterprisePayload?.authCode ?: fallbackPayload.authCode
        val objectId = enterprisePayload?.objectId ?: fallbackPayload.objectId
        val userId = enterprisePayload?.userId ?: fallbackPayload.userId
        val customParam = enterprisePayload?.extraField ?: fallbackPayload.customParam
        val backupOnly = useFallbackPayload && saveResultConfig.backupOnlyUpload
        if ((!backupOnly && baseUrl.isBlank()) ||
            authCode.isBlank() ||
            objectId.isBlank() ||
            userId.isBlank()
        ) {
            android.util.Log.w(TAG, "skip finish background upload: missing upload payload")
            exitAppAfterFinishSubmitted()
            return
        }
        val taskId = InspectionBackgroundUploadQueue.enqueueFinishInspection(
            taskKey = buildFinishUploadTaskKey(
                baseUrl = baseUrl,
                authCode = authCode,
                objectId = objectId,
                userId = userId,
                customParam = customParam,
                backupOnly = backupOnly,
            ),
            baseUrl = baseUrl,
            authCode = authCode,
            objectId = objectId,
            userId = userId,
            customParam = customParam,
            backupOnly = backupOnly,
            nsCode = InspectionWorkflowSession.deviceNsCode,
        )
        if (!taskId.isNullOrBlank()) {
            InspectionBackgroundUploadService.start(this, taskId)
        }
        exitAppAfterFinishSubmitted()
    }

    private fun buildFinishUploadTaskKey(
        baseUrl: String,
        authCode: String,
        objectId: String,
        userId: String,
        customParam: String,
        backupOnly: Boolean,
    ): String {
        return listOf(
            baseUrl,
            authCode,
            objectId,
            userId,
            customParam,
            backupOnly.toString(),
            InspectionWorkflowSession.resolveFinishSessionId().orEmpty(),
        ).joinToString(separator = "|")
    }

    private fun returnToMenuDirectly() {
        if (isFinishing || isDestroyed) return
        finishExitTriggered = false
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun exitAppAfterFinishSubmitted() {
        if (isFinishing || isDestroyed) return
        InspectionWorkflowSession.resetAll()
        finishAffinity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
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

}
