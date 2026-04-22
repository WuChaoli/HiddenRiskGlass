package com.rokid.glass

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseInfoActivity : BaseGlassActivity() {

    private lateinit var tvCompanyName: TextView
    private lateinit var tvRegion: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvRiskTags: TextView
    private lateinit var tvRiskLevel: TextView
    private lateinit var hazardListContainer: LinearLayout
    private lateinit var statusBar: GlassStatusBar
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var headGestureSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enterprise_info)

        tvCompanyName = findViewById(R.id.tvCompanyName)
        tvRegion = findViewById(R.id.tvRegion)
        tvCategory = findViewById(R.id.tvCategory)
        tvRiskTags = findViewById(R.id.tvRiskTags)
        tvRiskLevel = findViewById(R.id.tvRiskLevel)
        hazardListContainer = findViewById(R.id.hazardListContainer)
        statusBar = findViewById(R.id.statusBar)
        updateBatteryLevel()

        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()

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

        // 动态添加历史隐患列表
        hazardListContainer.removeAllViews()
        val hazards = listOf(
            "三合一住人",
            "防盗窗未设紧急逃生口",
            "电子烟靠近笔记本电脑存在火灾风险",
            "防盗窗影响逃生和灭火救援",
            "多孔插线板随意放置",
            "电气安全",
            "多设备集中连接",
        )
        hazards.forEachIndexed { index, hazard ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_hazard_history, hazardListContainer, false)
            val tvNumber = itemView.findViewById<TextView>(R.id.tvNumber)
            val tvHazard = itemView.findViewById<TextView>(R.id.tvHazard)
            tvNumber.text = (index + 1).toString()
            tvHazard.text = hazard
            hazardListContainer.addView(itemView)
        }
    }

    private fun bindEnterpriseInfo() {
        val info = InspectionWorkflowSession.enterpriseInfo
        if (info == null) {
            tvCompanyName.text = "-"
            tvRegion.text = getString(R.string.enterprise_info_region_prefix)
            tvCategory.text = getString(R.string.enterprise_info_category_prefix)
            tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix)
            tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix)
            return
        }

        tvCompanyName.text = info.companyName
        tvRegion.text = getString(R.string.enterprise_info_region_prefix) + info.region
        tvCategory.text = getString(R.string.enterprise_info_category_prefix) + info.category
        tvRiskTags.text = getString(R.string.enterprise_info_risk_tags_prefix) + info.riskTags
        tvRiskLevel.text = getString(R.string.enterprise_info_risk_level_prefix) + info.riskLevel

        // 动态添加历史隐患列表
        hazardListContainer.removeAllViews()
        info.hazardHistory.forEachIndexed { index, hazard ->
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
                label = "确认",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("确认", "que ren"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
            ) {
                startActivity(Intent(this, AiInspectionMenuActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "返回",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                    }
                },
            ) {
                finish()
            },
        )
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

    companion object {
        private const val TAG = "EnterpriseInfoActivity"
    }
}
