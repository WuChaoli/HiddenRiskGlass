package com.rokid.glass

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.adapter.MenuCardAdapter
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.DeviceGuideActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HazardRecordActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class AiInspectionMenuActivity : BaseGlassActivity() {

    private lateinit var tvBottomHint: TextView
    private lateinit var tvInspectionSummary: TextView
    private lateinit var statusBar: GlassStatusBar
    private lateinit var recyclerMenu: RecyclerView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private var selectedIndex = 0

    private lateinit var layoutExitConfirmDialog: LinearLayout
    private lateinit var tvExitConfirmConfirm: TextView
    private lateinit var tvExitConfirmCancel: TextView
    private lateinit var exitConfirmButtons: List<TextView>
    private var entryGuardNavigating = false
    private var exitConfirmDialogVisible = false
    private var exitConfirmSelectedIndex = EXIT_CONFIRM_CONFIRM
    private val uiHandler = Handler(Looper.getMainLooper())

    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.ai_entry_menu_analysis),
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_device_guide, R.string.ai_entry_menu_guide),
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_hazard_record, R.string.ai_entry_menu_record),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection_menu)

        tvBottomHint = findViewById(R.id.tvBottomHint)
        tvInspectionSummary = findViewById(R.id.tvInspectionSummary)
        statusBar = findViewById(R.id.statusBar)

        recyclerMenu = findViewById(R.id.recyclerMenu)
        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerMenu.adapter = menuAdapter
        recyclerMenu.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // 初始选中第一张卡片
        menuAdapter.selectedIndex = 0

        layoutExitConfirmDialog = findViewById(R.id.layoutExitConfirmDialog)
        tvExitConfirmConfirm = findViewById(R.id.tvExitConfirmConfirm)
        tvExitConfirmCancel = findViewById(R.id.tvExitConfirmCancel)
        exitConfirmButtons = listOf(tvExitConfirmConfirm, tvExitConfirmCancel)
        tvExitConfirmConfirm.setOnClickListener { startEndReport() }
        tvExitConfirmCancel.setOnClickListener { hideExitConfirmDialog() }
        updateInspectionSummary()
    }

    override fun onResume() {
        super.onResume()
        entryGuardNavigating = false
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        statusBarUpdater.start(statusBar)
        updateInspectionSummary()
        runEntryGuards()
    }

    override fun onPause() {
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        statusBarUpdater.stop()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK || keyEvent == GlassKeyEvent.KEYCODE_BACK) {
            showExitConfirmDialog()
            return true
        }
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun runEntryGuards() {
        if (entryGuardNavigating || exitConfirmDialogVisible) return

        if (
            InspectionWorkflowSession.enterpriseQrPayload == null ||
            InspectionWorkflowSession.enterpriseInfo == null
        ) {
            entryGuardNavigating = true
            startEnterpriseQrScan(forceScan = false)
            return
        }

        updateInspectionSummary()
    }

    private fun showExitConfirmDialog() {
        exitConfirmDialogVisible = true
        exitConfirmSelectedIndex = EXIT_CONFIRM_CONFIRM
        layoutExitConfirmDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        updateExitConfirmSelection()
        inputSession.updateActions(buildInputActions())
    }

    private fun hideExitConfirmDialog(refreshInput: Boolean = true) {
        exitConfirmDialogVisible = false
        if (::layoutExitConfirmDialog.isInitialized) {
            layoutExitConfirmDialog.visibility = View.GONE
        }
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
        if (refreshInput) {
            inputSession.updateActions(buildInputActions())
        }
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        if (exitConfirmDialogVisible) {
            return listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                ) {
                    moveExitConfirmSelection(-1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                ) {
                    moveExitConfirmSelection(+1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.ai_entry_end_inspection_confirm),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_end_inspection_confirm), "que ren"),
                    ),
                ) { event ->
                    if (event.trigger is UnifiedInputSession.InputTrigger.Voice) {
                        startEndReport()
                    } else {
                        executeExitConfirmSelection()
                    }
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Cancel,
                    label = getString(R.string.ai_entry_end_inspection_cancel),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_end_inspection_cancel), "qu xiao"),
                    ),
                ) {
                    hideExitConfirmDialog()
                },
            )
        }
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Previous,
                label = "上一个",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
            ) {
                moveSelection(-1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Next,
                label = "下一个",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
            ) {
                moveSelection(+1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK)),
            ) {
                onItemConfirmed(selectedIndex)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_analysis"),
                label = "实时分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("实时分析", "shi shi fen xi")),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_guide"),
                label = "设备指引",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("设备指引", "she bei zhi yin")),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_record"),
                label = "隐患拍照",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患拍照", "yin huan pai zhao")),
            ) {
                onItemConfirmed(2)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_finish_inspection"),
                label = "结束巡查",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("结束巡查", "jie shu xun cha"),
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish), "jie shu ren wu"),
                    UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish_accent_alias), "jie su ren wu"),
                ),
            ) {
                startEndReport()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_scan"),
                label = "检查扫码",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("检查扫码", "jian cha sao ma")),
            ) {
                startEnterpriseQrScan(forceScan = true)
            },
        )
    }

    private fun updateInspectionSummary() {
        val companyName = InspectionWorkflowSession.enterpriseInfo
            ?.companyName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "-"
        tvInspectionSummary.text = getString(
            R.string.ai_entry_menu_inspection_summary,
            companyName,
            InspectionWorkflowSession.buildEndReportHazardCount(),
        )
    }

    /** 移动选中框：更新高亮，并确保目标卡片完整停留在可视区域内 */
    private fun moveSelection(delta: Int) {
        val target = (selectedIndex + delta).coerceIn(0, menuAdapter.itemCount - 1)
        if (target == selectedIndex) return
        selectedIndex = target
        menuAdapter.selectedIndex = target
        ensureSelectedCardVisible(target)
    }

    private fun ensureSelectedCardVisible(position: Int, retryAfterLayout: Boolean = true) {
        val lm = recyclerMenu.layoutManager as? LinearLayoutManager ?: return
        val itemView = lm.findViewByPosition(position)
        if (itemView == null) {
            lm.scrollToPositionWithOffset(position, recyclerMenu.paddingLeft)
            if (retryAfterLayout) {
                recyclerMenu.post { ensureSelectedCardVisible(position, retryAfterLayout = false) }
            }
            return
        }

        val visibleLeft = recyclerMenu.paddingLeft
        val visibleRight = recyclerMenu.width - recyclerMenu.paddingRight
        val itemLeft = lm.getDecoratedLeft(itemView)
        val itemRight = lm.getDecoratedRight(itemView)

        when {
            itemLeft < visibleLeft -> recyclerMenu.smoothScrollBy(itemLeft - visibleLeft, 0)
            itemRight > visibleRight -> recyclerMenu.smoothScrollBy(itemRight - visibleRight, 0)
        }
    }

    private fun moveExitConfirmSelection(delta: Int) {
        val target = (exitConfirmSelectedIndex + delta).coerceIn(0, exitConfirmButtons.lastIndex)
        if (target == exitConfirmSelectedIndex) return
        exitConfirmSelectedIndex = target
        updateExitConfirmSelection()
    }

    private fun updateExitConfirmSelection() {
        exitConfirmButtons.forEachIndexed { index, button ->
            button.setBackgroundResource(
                if (index == exitConfirmSelectedIndex) R.drawable.glass_card_outline_selected
                else R.drawable.glass_card_outline,
            )
            button.setTextColor(getColor(R.color.green))
            button.setTypeface(null, if (index == exitConfirmSelectedIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // 同步焦点，确保按键事件和视觉状态一致
            if (index == exitConfirmSelectedIndex) {
                button.requestFocus()
            }
        }
    }

    private fun executeExitConfirmSelection() {
        when (exitConfirmSelectedIndex) {
            EXIT_CONFIRM_CONFIRM -> startEndReport()
            EXIT_CONFIRM_CANCEL -> hideExitConfirmDialog()
        }
    }

    private fun onItemConfirmed(index: Int) {
        if (entryGuardNavigating || exitConfirmDialogVisible) return
        when (index) {
            0 -> startHazardAnalysis()
            1 -> startDeviceGuide()
            2 -> startActivity(Intent(this, HazardRecordActivity::class.java))
            else -> Unit
        }
    }

    private fun startHazardAnalysis() {
        startActivity(Intent(this, AiInspectionActivity::class.java))
    }

    private fun startDeviceGuide() {
        startActivity(Intent(this, DeviceGuideActivity::class.java))
    }

    private fun startEnterpriseQrScan(forceScan: Boolean) {
        startActivity(Intent(this, EnterpriseQrScanActivity::class.java).apply {
            putExtra(EnterpriseQrScanActivity.EXTRA_FORCE_SCAN, forceScan)
        })
    }

    private fun startEndReport() {
        startActivity(
            InspectionEndReportActivity.createIntent(
                this,
                InspectionEndReportReturnDestination.AI_MENU_HOME,
            ),
        )
    }

    companion object {
        private const val TAG = "AiInspectionMenu"
        private const val EXIT_CONFIRM_CONFIRM = 0
        private const val EXIT_CONFIRM_CANCEL = 1
    }
}
