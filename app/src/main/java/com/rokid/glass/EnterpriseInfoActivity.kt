package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseInfoActivity : BaseGlassActivity() {

    private lateinit var tvCompany: TextView
    private lateinit var tvSite: TextView
    private lateinit var tvInspector: TextView
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private var headGestureSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enterprise_info)

        tvCompany = findViewById(R.id.tvCompany)
        tvSite = findViewById(R.id.tvSite)
        tvInspector = findViewById(R.id.tvInspector)
        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()

        val info = InspectionWorkflowSession.enterpriseInfo
        tvCompany.text = getString(R.string.enterprise_info_company) + "：${info?.companyName ?: "-"}"
        tvSite.text = getString(R.string.enterprise_info_site) + "：${info?.siteName ?: "-"}"
        tvInspector.text = getString(R.string.enterprise_info_inspector) + "：${info?.inspectorName ?: "-"}"
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

    companion object {
        private const val TAG = "EnterpriseInfoActivity"
    }
}
