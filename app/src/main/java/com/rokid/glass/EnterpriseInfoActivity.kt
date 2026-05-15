package com.rokid.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseInfoActivity : BaseGlassActivity() {

    private lateinit var tvCompanyName: TextView
    private lateinit var tvRegion: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvRiskTags: TextView
    private lateinit var tvRiskLevel: TextView
    private lateinit var tvRecentInspectionTime: TextView
    private lateinit var hazardListContainer: LinearLayout
    private lateinit var statusBar: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            statusBar.updateTime()
            mainHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enterprise_info)

        tvCompanyName = findViewById(R.id.tvCompanyName)
        tvRegion = findViewById(R.id.tvRegion)
        tvCategory = findViewById(R.id.tvCategory)
        tvRiskTags = findViewById(R.id.tvRiskTags)
        tvRiskLevel = findViewById(R.id.tvRiskLevel)
        tvRecentInspectionTime = findViewById(R.id.tvRecentInspectionTime)
        hazardListContainer = findViewById(R.id.hazardListContainer)
        statusBar = findViewById(R.id.statusBar)
        statusBar.updateTime()
        updateBatteryLevel()

        // debug模式：从Intent读取测试数据
        if (intent.hasExtra("debug_company")) {
            bindDebugEnterpriseInfo()
        } else {
            bindEnterpriseInfo()
        }
    }

    private fun bindDebugEnterpriseInfo() {
        tvCompanyName.text = intent.getStringExtra("debug_company") ?: "-"
        tvRegion.text = getString(R.string.enterprise_info_region_prefix) + (intent.getStringExtra("debug_region") ?: "")
        tvCategory.text = getString(R.string.enterprise_info_category_prefix) + (intent.getStringExtra("debug_category") ?: "")
        tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix) + (intent.getStringExtra("debug_risk_tags") ?: "")
        tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix) + (intent.getStringExtra("debug_risk_level") ?: "")
        bindRecentInspectionTime(intent.getStringExtra("debug_last_inspection_date").orEmpty())

        val hazards = listOf(
            "三合一住人",
            "防盗窗未设紧急逃生口",
            "电子烟靠近笔记本电脑存在火灾风险",
            "防盗窗影响逃生和灭火救援",
            "多孔插线板随意放置",
            "电气安全",
            "多设备集中连接",
        )
        renderHazardHistory(hazards)
    }

    private fun bindEnterpriseInfo() {
val info = InspectionWorkflowSession.enterpriseInfo
         if (info == null) {
             tvCompanyName.text = "-"
             tvRegion.text = getString(R.string.enterprise_info_region_prefix)
             tvCategory.text = getString(R.string.enterprise_info_category_prefix)
             tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix)
             tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix)
             bindRecentInspectionTime("")
             hazardListContainer.removeAllViews()
             return
         }

         tvCompanyName.text = info.companyName
         tvRegion.text = getString(R.string.enterprise_info_region_prefix) + info.region
         tvCategory.text = getString(R.string.enterprise_info_category_prefix) + info.category
         tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix) + info.riskTags
         tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix) + info.riskLevel
         bindRecentInspectionTime(info.lastInspectionDate)

         renderHazardHistory(info.hazardHistory)
    }

    private fun bindRecentInspectionTime(lastInspectionDate: String) {
        val normalizedDate = lastInspectionDate.trim()
        if (normalizedDate.isBlank()) {
            tvRecentInspectionTime.visibility = View.GONE
            tvRecentInspectionTime.text = ""
            return
        }

        tvRecentInspectionTime.visibility = View.VISIBLE
        tvRecentInspectionTime.text = getString(R.string.enterprise_info_recent_inspection_time_prefix) + normalizedDate
    }

    private fun renderHazardHistory(hazards: List<String>) {
        hazardListContainer.removeAllViews()
        hazards.take(MAX_HAZARD_HISTORY_DISPLAY_COUNT).forEachIndexed { index, hazard ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_hazard_history, hazardListContainer, false)
            val tvNumber = itemView.findViewById<TextView>(R.id.tvNumber)
            val tvHazard = itemView.findViewById<TextView>(R.id.tvHazard)
            tvNumber.text = (index + 1).toString()
            tvHazard.text = hazard
            hazardListContainer.addView(itemView)
        }
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        startStatusBarUpdates()
    }

    override fun onPause() {
        stopStatusBarUpdates()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        stopStatusBarUpdates()
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
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
            ) {
                startActivity(Intent(this, AiInspectionMenuActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
            ) {
                exitPage()
            },
        )
    }

    private fun buildConfirmTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            voiceTrigger(R.string.ai_inspection_voice_confirm, "que ren"),
            voiceTrigger(R.string.ai_inspection_voice_confirm_alias, "que ding"),
            voiceTrigger(R.string.ai_inspection_voice_continue_alias, "ji xu"),
        )
    }

    private fun buildReturnTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            voiceTrigger(R.string.ai_inspection_voice_return, "fan hui"),
            voiceTrigger(R.string.ai_inspection_voice_cancel_alias, "qu xiao"),
        )
    }

    private fun voiceTrigger(
        textRes: Int,
        pinyin: String,
    ): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    private fun exitPage() {
        InspectionCameraCoordinator.releaseAppCamera(reason = "enterprise_info_exit_app")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

    /**
     * 页面可见时持续刷新状态栏时间与电量，保证底部状态栏显示真实系统状态。
     */
    private fun startStatusBarUpdates() {
        statusBar.updateTime()
        updateBatteryLevel()
        mainHandler.removeCallbacks(statusUpdateRunnable)
        mainHandler.post(statusUpdateRunnable)
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
        mainHandler.removeCallbacks(statusUpdateRunnable)
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
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

    companion object {
        private const val TAG = "EnterpriseInfoActivity"
        private val MAX_HAZARD_HISTORY_DISPLAY_COUNT: Int
            get() = InspectionConfigRepository.get().enterpriseInfo.maxHazardHistoryDisplayCount

        private const val STATUS_UPDATE_INTERVAL_MS = 1000L
    }
}
