package com.rokid.glass

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.adapter.MenuCardAdapter
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import com.google.gson.Gson
import com.rokid.glass.updater.AppUpdatePromptActivity

/**
 * 主菜单 Activity，作为 App 的 LAUNCHER 入口。
 *
 * 职责：
 * 1. 第一层菜单展示（3卡片：基层应消、连接WiFi、检查更新）
 * 2. 集成 EntryGuardCoordinator 进行后台静默初始化
 * 3. 禁用双击/后退退出（消费事件但不 finish）
 * 4. 仅语音"退出应用"可真正退出 App
 * 5. 初始化完成后才能点击"基层应消"进入第二层
 */
class MainMenuActivity : BaseGlassActivity() {

    private lateinit var tvBottomHint: TextView
    private lateinit var tvInitStatus: TextView
    private lateinit var statusBar: GlassStatusBar
    private lateinit var recyclerMenu: RecyclerView
    private lateinit var layoutWifiRequiredDialog: LinearLayout
    private lateinit var tvWifiRequiredMessage: TextView
    private lateinit var tvWifiRetry: TextView
    private lateinit var tvWifiExit: TextView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private val entryGuardCoordinator by lazy {
        EntryGuardCoordinator(this, object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {
                showWifiRequiredDialog(messageResId)
            }

            override fun onWifiConnecting() {
                showWifiRequiredDialog(R.string.ai_entry_wifi_connecting)
            }

            override fun onWifiConnected() {
                hideWifiRequiredDialog()
            }

            override fun onWifiConnectionFailed(messageResId: Int) {
                showWifiRequiredDialog(messageResId)
            }

            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
                // SDK 状态变更，不单独显示，由 onAllGuardsReady 统一处理
                Log.d(TAG, "SDK state changed: $state")
            }

            override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) {
                // 相机状态变更，不单独显示，由 onAllGuardsReady 统一处理
                Log.d(TAG, "Camera state changed: $state")
            }

            override fun onAutoUpdateAvailable(updateInfoJson: String) {
                startActivity(
                    Intent(this@MainMenuActivity, AppUpdatePromptActivity::class.java).apply {
                        putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, updateInfoJson)
                    },
                )
            }

            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {
                Log.d(TAG, "Auto update check complete, hasUpdate=$hasUpdate")
            }

            override fun onAllGuardsReady() {
                allGuardsReady = true
                tvInitStatus.visibility = View.GONE
                tvBottomHint.setText(R.string.main_menu_bottom_hint)
                inputSession.updateActions(buildInputActions())
            }
        })
    }

    private val menuAdapter by lazy {
        MenuCardAdapter(
            cards = listOf(
                MenuCardAdapter.MenuCardData(R.drawable.ic_menu_ai_analysis, R.string.main_menu_card_inspection),
                MenuCardAdapter.MenuCardData(0, R.string.main_menu_card_wifi, iconChar = "↻"),
                MenuCardAdapter.MenuCardData(0, R.string.main_menu_card_update, iconChar = "↻"),
            ),
        )
    }

    private var selectedIndex = 0
    private var allGuardsReady = false
    private var checkingUpdate = false
    private var wifiRequiredDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        tvBottomHint = findViewById(R.id.tvBottomHint)
        tvInitStatus = findViewById(R.id.tvInitStatus)
        statusBar = findViewById(R.id.statusBar)

        recyclerMenu = findViewById(R.id.recyclerMenu)
        recyclerMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerMenu.adapter = menuAdapter
        recyclerMenu.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // 初始选中第一张卡片
        menuAdapter.selectedIndex = 0

        layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
        tvWifiRequiredMessage = findViewById(R.id.tvWifiRequiredMessage)
        tvWifiRetry = findViewById(R.id.tvWifiRetry)
        tvWifiExit = findViewById(R.id.tvWifiExit)
        tvWifiRetry.setOnClickListener { entryGuardCoordinator.launchWifiScanner(this) }
        tvWifiExit.setOnClickListener { exitAppDirectly() }
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        statusBarUpdater.start(statusBar)
        entryGuardCoordinator.startBackgroundGuards()
        if (!allGuardsReady) {
            tvInitStatus.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        statusBarUpdater.stop()
        inputSession.release()
        entryGuardCoordinator.release()
        super.onDestroy()
    }

    /**
     * 覆盖 onGlassKeyEvent：禁用双击/后退退出，仅消费事件。
     */
    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK || keyEvent == GlassKeyEvent.KEYCODE_BACK) {
            return true // 消费事件，不退出
        }
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        if (wifiRequiredDialogVisible) {
            return listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.ai_entry_wifi_retry),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.ai_entry_wifi_retry), "chong xin sao ma"),
                    ),
                ) {
                    entryGuardCoordinator.launchWifiScanner(this)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Exit,
                    label = getString(R.string.ai_entry_wifi_exit),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice(
                            getString(R.string.main_menu_voice_exit_app),
                            getString(R.string.main_menu_voice_exit_app_pinyin),
                        ),
                    ),
                ) {
                    exitAppDirectly()
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
                id = UnifiedInputSession.InputActionId("main_menu_inspection"),
                label = getString(R.string.main_menu_card_inspection),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(
                        getString(R.string.main_menu_voice_inspection),
                        getString(R.string.main_menu_voice_inspection_pinyin),
                    ),
                ),
            ) {
                onItemConfirmed(0)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("main_menu_wifi"),
                label = getString(R.string.main_menu_card_wifi),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(
                        getString(R.string.main_menu_card_wifi),
                        "lian jie wifi",
                    ),
                ),
            ) {
                onItemConfirmed(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("main_menu_update"),
                label = getString(R.string.main_menu_card_update),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(
                        getString(R.string.main_menu_card_update),
                        "jian cha geng xin",
                    ),
                ),
                enabled = { !checkingUpdate },
            ) {
                onItemConfirmed(2)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = getString(R.string.main_menu_voice_exit_app),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice(
                        getString(R.string.main_menu_voice_exit_app),
                        getString(R.string.main_menu_voice_exit_app_pinyin),
                    ),
                ),
            ) {
                exitAppDirectly()
            },
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

    private fun onItemConfirmed(index: Int) {
        if (wifiRequiredDialogVisible) return
        when (index) {
            0 -> startInspection()
            1 -> entryGuardCoordinator.launchWifiScanner(this)
            2 -> checkUpdateManually()
            else -> Unit
        }
    }

    private fun startInspection() {
        if (!allGuardsReady) {
            tvBottomHint.setText(R.string.main_menu_init_status)
            return
        }
        startActivity(Intent(this, AiInspectionMenuActivity::class.java))
    }

    private fun checkUpdateManually() {
        if (checkingUpdate) return
        checkingUpdate = true
        tvBottomHint.setText(R.string.ai_entry_menu_update_checking)
        inputSession.updateActions(buildInputActions())
        entryGuardCoordinator.checkUpdateManually(object : EntryGuardCoordinator.UpdateCheckListener {
            override fun onComplete(hasUpdate: Boolean, updateInfoJson: String?) {
                runOnUiThread {
                    checkingUpdate = false
                    inputSession.updateActions(buildInputActions())
                    if (hasUpdate && updateInfoJson != null) {
                        startActivity(
                            Intent(this@MainMenuActivity, AppUpdatePromptActivity::class.java).apply {
                                putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, updateInfoJson)
                            },
                        )
                    } else {
                        tvBottomHint.setText(R.string.ai_entry_menu_update_latest)
                    }
                }
            }
        })
    }

    private fun showWifiRequiredDialog(messageResId: Int) {
        wifiRequiredDialogVisible = true
        tvWifiRequiredMessage.setText(messageResId)
        layoutWifiRequiredDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        tvInitStatus.visibility = View.GONE
        inputSession.updateActions(buildInputActions())
    }

    private fun hideWifiRequiredDialog() {
        wifiRequiredDialogVisible = false
        layoutWifiRequiredDialog.visibility = View.GONE
        recyclerMenu.isEnabled = true
        tvBottomHint.visibility = View.VISIBLE
        if (!allGuardsReady) {
            tvInitStatus.visibility = View.VISIBLE
        }
    }

    private fun exitAppDirectly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

    companion object {
        private const val TAG = "MainMenuActivity"
    }
}
