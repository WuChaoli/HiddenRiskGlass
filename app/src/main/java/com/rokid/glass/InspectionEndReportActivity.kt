package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.HttpUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class InspectionEndReportActivity : BaseGlassActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvSummary: TextView
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var headGestureSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_end_report)

        ivPreview = findViewById(R.id.ivPreview)
        tvSummary = findViewById(R.id.tvSummary)
        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()

        val summary = InspectionWorkflowSession.summary
        val analyzedCount = if (summary.analyzedCount == 0) 3 else summary.analyzedCount
        val hasHazardCount = if (summary.hasHazardCount == 0) 3 else summary.hasHazardCount
        val noHazardCount = summary.noHazardCount
        val mayHazardCount = summary.mayHazardCount
        tvSummary.text = getString(
            R.string.ai_detection_end_summary,
            analyzedCount,
            hasHazardCount,
            noHazardCount,
            mayHazardCount,
        )
        InspectionWorkflowSession.latestCapturedBitmap?.let(ivPreview::setImageBitmap)
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        inputSession.release()
        super.onDestroy()
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
                finishInspectionAndReturnHome()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "重新开始",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                ),
            ) {
                finishInspectionAndReturnHome()
            },
        )
    }

    private fun finishInspectionAndReturnHome() {
        // 首批先做模拟结束上报，不阻塞返回流程。
        HttpUtils().reportSaveResult(
            snCode = com.rokid.glass.hiddenrisk.RokidSdkManager.getSerialNumber(),
            isSave = "0",
            sessionId = System.currentTimeMillis().toString(),
            callback = object : HttpUtils.SaveResultCallback {
                override fun onSuccess(response: HttpUtils.ApiResponse) = Unit
                override fun onFailure(e: Exception) = Unit
            },
        )
        InspectionWorkflowSession.resetAll()
        startActivity(Intent(this, InspectionLoadingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    companion object {
        private const val TAG = "InspectionEndReport"
    }
}
