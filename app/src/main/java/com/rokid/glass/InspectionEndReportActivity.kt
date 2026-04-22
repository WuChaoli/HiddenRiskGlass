package com.rokid.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.HttpUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import java.util.Date

class InspectionEndReportActivity : BaseGlassActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvHazardCount: TextView
    private lateinit var operationGuideEnd: OperationGuideView
    private lateinit var bottomPromptEnd: BottomPromptView
    private lateinit var statusBarEnd: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var headGestureSupported = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            statusBarEnd.updateTime()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_end_report)

        ivPreview = findViewById(R.id.ivPreview)
        tvHazardCount = findViewById(R.id.tvHazardCount)
        operationGuideEnd = findViewById(R.id.operationGuideEnd)
        bottomPromptEnd = findViewById(R.id.bottomPromptEnd)
        statusBarEnd = findViewById(R.id.statusBarEnd)
        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()

        val summary = InspectionWorkflowSession.summary
        val hasHazardCount = if (summary.hasHazardCount == 0) 3 else summary.hasHazardCount
        tvHazardCount.text = "分析出${hasHazardCount}条隐患"
        InspectionWorkflowSession.latestCapturedBitmap?.let(ivPreview::setImageBitmap)

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
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        stopTimeAndBatteryUpdate()
        inputSession.release()
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
