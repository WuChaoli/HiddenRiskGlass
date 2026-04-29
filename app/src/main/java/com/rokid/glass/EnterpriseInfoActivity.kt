package com.rokid.glass

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseInfoActivity : BaseGlassActivity() {

    private lateinit var tvCompanyName: TextView
    private lateinit var infoTextViews: List<TextView>
    private lateinit var tvRegion: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvRiskTags: TextView
    private lateinit var tvRiskLevel: TextView
    private lateinit var tvRecentInspectionTime: TextView
    private lateinit var viewHazardDivider: View
    private lateinit var tvHazardHistoryTitle: TextView
    private lateinit var scrollHazardHistory: View
    private lateinit var hazardListContainer: LinearLayout
    private lateinit var statusBar: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enterprise_info)

        tvCompanyName = findViewById(R.id.tvCompanyName)
        tvRegion = findViewById(R.id.tvRegion)
        tvCategory = findViewById(R.id.tvCategory)
        tvRiskTags = findViewById(R.id.tvRiskTags)
        tvRiskLevel = findViewById(R.id.tvRiskLevel)
        tvRecentInspectionTime = findViewById(R.id.tvRecentInspectionTime)
        infoTextViews = listOf(
            tvRegion,
            tvCategory,
            tvRiskTags,
            tvRiskLevel,
            tvRecentInspectionTime,
        )
        viewHazardDivider = findViewById(R.id.viewHazardDivider)
        tvHazardHistoryTitle = findViewById(R.id.tvHazardHistoryTitle)
        scrollHazardHistory = findViewById(R.id.scrollHazardHistory)
        hazardListContainer = findViewById(R.id.hazardListContainer)
        statusBar = findViewById(R.id.statusBar)
        applyLayoutMode(DEFAULT_LAYOUT_MODE)
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
        tvRecentInspectionTime.text = RECENT_INSPECTION_TIME

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
            tvRecentInspectionTime.text = RECENT_INSPECTION_TIME
            hazardListContainer.removeAllViews()
            return
        }

        tvCompanyName.text = info.companyName
        tvRegion.text = getString(R.string.enterprise_info_region_prefix) + info.region
        tvCategory.text = getString(R.string.enterprise_info_category_prefix) + info.category
        tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix) + info.riskTags
        tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix) + info.riskLevel
        tvRecentInspectionTime.text = RECENT_INSPECTION_TIME

        renderHazardHistory(info.hazardHistory)
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

    /**
     * 根据当前布局模式切换页面显示方案。
     */
    private fun applyLayoutMode(layoutMode: EnterpriseInfoLayoutMode) {
        val showHazardHistory = layoutMode == EnterpriseInfoLayoutMode.LEGACY
        val companyNameSizeSp = if (layoutMode == EnterpriseInfoLayoutMode.NEW) {
            COMPANY_NAME_TEXT_SIZE_NEW_SP
        } else {
            COMPANY_NAME_TEXT_SIZE_LEGACY_SP
        }
        val infoTextSizeSp = if (layoutMode == EnterpriseInfoLayoutMode.NEW) {
            INFO_TEXT_SIZE_NEW_SP
        } else {
            INFO_TEXT_SIZE_LEGACY_SP
        }
        val infoLineSpacingExtraDp = if (layoutMode == EnterpriseInfoLayoutMode.NEW) {
            INFO_LINE_SPACING_EXTRA_NEW_DP
        } else {
            INFO_LINE_SPACING_EXTRA_LEGACY_DP
        }

        tvCompanyName.setTextSize(TypedValue.COMPLEX_UNIT_SP, companyNameSizeSp)
        infoTextViews.forEach { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, infoTextSizeSp)
            textView.setLineSpacing(dpToPx(infoLineSpacingExtraDp), 1f)
        }

        val hazardVisibility = if (showHazardHistory) View.VISIBLE else View.GONE
        viewHazardDivider.visibility = hazardVisibility
        tvHazardHistoryTitle.visibility = hazardVisibility
        scrollHazardHistory.visibility = hazardVisibility
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

    /**
     * 获取当前电池电量并更新电池图标填充
     */
    private fun updateBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBar.setBatteryPercent(batteryPct)
            }
        }
    }

    /**
     * dp 转 px，保证运行时行间距在不同密度设备上表现一致。
     */
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        private const val TAG = "EnterpriseInfoActivity"
        private const val MAX_HAZARD_HISTORY_DISPLAY_COUNT = 3
        private const val RECENT_INSPECTION_TIME = "最近巡查时间：2026年1月21日"
        private const val COMPANY_NAME_TEXT_SIZE_LEGACY_SP = 20f
        private const val COMPANY_NAME_TEXT_SIZE_NEW_SP = 24f
        private const val INFO_TEXT_SIZE_LEGACY_SP = 11f
        private const val INFO_TEXT_SIZE_NEW_SP = 17f
        private const val INFO_LINE_SPACING_EXTRA_LEGACY_DP = 0f
        private const val INFO_LINE_SPACING_EXTRA_NEW_DP = 6f
        private val DEFAULT_LAYOUT_MODE = EnterpriseInfoLayoutMode.NEW
    }

    /**
     * 企业详情页布局模式。
     */
    private enum class EnterpriseInfoLayoutMode {
        LEGACY,
        NEW,
    }
}
