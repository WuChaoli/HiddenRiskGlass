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
import com.rokid.glass.config.AutoHazardRoutingMode
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.InspectionSession
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private val modelLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_ai_analysis,
                    labelResId = R.string.ai_entry_menu_analysis,
                    pinyinResId = R.string.ai_entry_menu_analysis_pinyin,
                    onClick = { startHazardAnalysis() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_device_guide,
                    labelResId = R.string.ai_entry_menu_guide,
                    pinyinResId = R.string.ai_entry_menu_guide_pinyin,
                    onClick = { startDeviceGuide() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_hazard_record,
                    labelResId = R.string.ai_entry_menu_record,
                    pinyinResId = R.string.ai_entry_menu_record_pinyin,
                    onClick = { startActivity(Intent(this@AiInspectionMenuActivity, HazardRecordActivity::class.java)) },
                ),
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
        recyclerMenu.itemAnimator = null

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
        preloadInspectionSession()
    }

    override fun onPause() {
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        statusBarUpdater.stop()
        inputSession.release()
        modelLoadExecutor.shutdown()
        try {
            if (!modelLoadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                modelLoadExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            modelLoadExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
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

    /**
     * 后台预初始化 InspectionSession（NCNN 实例创建 + 模型加载）。
     * 在 onResume 时启动，使得到达 AiInspectionActivity 时 isInitialized 已为 true。
     * 若已初始化则跳过。
     */
    private fun preloadInspectionSession() {
        if (InspectionSession.isInitialized) return
        Log.d(TAG, "preloadInspectionSession: starting background preload")
        modelLoadExecutor.execute {
            try {
                val needsModel = InspectionConfigRepository.get()
                    .aiInspection
                    .autoHazardRoutingMode == AutoHazardRoutingMode.LOCAL_ONLY
                if (needsModel) {
                    val created = InspectionSession.createNcnnInstance()
                    if (!created) {
                        Log.e(TAG, "preloadInspectionSession: createNcnnInstance failed")
                        return@execute
                    }
                    val loaded = InspectionSession.loadModel(assets)
                    if (!loaded) {
                        Log.e(TAG, "preloadInspectionSession: loadModel failed")
                        return@execute
                    }
                }
                InspectionSession.markInitialized()
                Log.d(TAG, "preloadInspectionSession: marked initialized")
            } catch (e: Exception) {
                Log.e(TAG, "preloadInspectionSession: unexpected error", e)
            }
        }
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
            return buildList {
                addAll(
                    UnifiedInputSession.buildPageCommonActions(
                        onConfirm = { executeExitConfirmSelection() },
                        onCancel = { hideExitConfirmDialog() },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId.Previous,
                        label = "上一个",
                        triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                        onTrigger = { moveExitConfirmSelection(-1) },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId.Next,
                        label = "下一个",
                        triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                        onTrigger = { moveExitConfirmSelection(+1) },
                    ),
                )
            }
        }
        return buildList {
            // 页面通用动作：确认→点击卡片，取消→显示退出确认弹窗
            addAll(
                UnifiedInputSession.buildPageCommonActions(
                    onConfirm = { onItemConfirmed(selectedIndex) },
                    onCancel = { showExitConfirmDialog() },
                ),
            )
            // 导航：前后滑动选卡片
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND)),
                    onTrigger = { moveSelection(-1) },
                ),
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT)),
                    onTrigger = { moveSelection(+1) },
                ),
            )
            // 卡片语音指令：自动从 VoiceActionItem 生成
            addAll(
                UnifiedInputSession.buildCardVoiceActions(
                    menuAdapter.cards,
                    this@AiInspectionMenuActivity,
                    onVoiceTrigger = { index -> onItemConfirmed(index) },
                ),
            )
            // 非卡片语音指令：结束巡查（手动注册，3个别名）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("ai_menu_finish_inspection"),
                    label = "结束巡查",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice("结束巡查", "jie shu xun cha"),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish), "jie shu ren wu"),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_inspection_voice_finish_accent_alias), "jie su ren wu"),
                    ),
                    onTrigger = { startEndReport() },
                ),
            )
            // 非卡片语音指令：检查扫码（手动注册）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("ai_menu_scan"),
                    label = "检查扫码",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice("检查扫码", "jian cha sao ma"),
                    ),
                    onTrigger = { startEnterpriseQrScan(forceScan = true) },
                ),
            )
        }
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

    /** 移动选中框：更新高亮 */
    private fun moveSelection(delta: Int) {
        val target = (selectedIndex + delta).coerceIn(0, menuAdapter.itemCount - 1)
        if (target == selectedIndex) return
        selectedIndex = target
        menuAdapter.selectedIndex = target
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
        // 先移动焦点到目标卡片
        selectedIndex = index
        menuAdapter.selectedIndex = index
        // post 确保 RecyclerView 完成焦点切换布局后再取 ViewHolder 播放动画
        recyclerMenu.post {
            val viewHolder = recyclerMenu.findViewHolderForAdapterPosition(index) as? MenuCardAdapter.ViewHolder
            if (viewHolder != null) {
                menuAdapter.animateClick(viewHolder) { executeConfirmedAction(index) }
            } else {
                executeConfirmedAction(index)
            }
        }
    }

    private fun executeConfirmedAction(index: Int) {
        menuAdapter.cards.getOrNull(index)?.execute()
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
