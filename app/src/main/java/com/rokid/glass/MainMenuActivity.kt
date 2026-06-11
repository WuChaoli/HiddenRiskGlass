package com.rokid.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.utils.ToastUtil

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
    private val entryGuardCoordinator: EntryGuardCoordinator by lazy {
        EntryGuardCoordinator(this, object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {
                // WiFi 未连接时直接拉起扫码页，不弹窗
                entryGuardCoordinator.launchWifiScanner(this@MainMenuActivity)
            }

            override fun onWifiConnecting() {
                hideWifiRequiredDialog()
                ToastUtil.show(getString(R.string.ai_entry_wifi_connecting_toast))
            }

            override fun onWifiConnected() {
                hideWifiRequiredDialog()
                ToastUtil.show(getString(R.string.ai_entry_wifi_connected_toast))
            }

            override fun onWifiConnectionFailed(messageResId: Int) {
                ToastUtil.cancel()
                showWifiRequiredDialog(messageResId)
            }

            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
                // SDK 状态变更，不单独显示，由 onAllGuardsReady 统一处理
                Log.d(TAG, "SDK state changed: $state")
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
                if (allGuardsReady) {
                    tvBottomHint.setText(R.string.main_menu_bottom_hint)
                }
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
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_ai_analysis,
                    labelResId = R.string.main_menu_card_inspection,
                    pinyinResId = R.string.main_menu_card_inspection_pinyin,
                    onClick = { startInspection() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_wifi,
                    labelResId = R.string.main_menu_card_wifi,
                    pinyinResId = R.string.main_menu_card_wifi_pinyin,
                    onClick = { launchWifiScannerWithPermissionCheck() },
                ),
                MenuCardAdapter.MenuCardData(
                    iconResId = R.drawable.ic_menu_update,
                    labelResId = R.string.main_menu_card_update,
                    pinyinResId = R.string.main_menu_card_update_pinyin,
                    onClick = { checkUpdateManually() },
                ),
            ),
        )
    }

    private var selectedIndex = 0
    private var allGuardsReady = false
    private var checkingUpdate = false
    private var wifiRequiredDialogVisible = false
    private var wifiDialogSelectedRetry = true // true=重新扫码, false=退出应用

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
        recyclerMenu.itemAnimator = null

        // 初始选中第一张卡片
        menuAdapter.selectedIndex = 0

        layoutWifiRequiredDialog = findViewById(R.id.layoutWifiRequiredDialog)
        tvWifiRequiredMessage = findViewById(R.id.tvWifiRequiredMessage)
        tvWifiRetry = findViewById(R.id.tvWifiRetry)
        tvWifiExit = findViewById(R.id.tvWifiExit)
        tvWifiRetry.setOnClickListener { entryGuardCoordinator.launchWifiScanner(this) }
        tvWifiExit.setOnClickListener {
            wifiDialogSelectedRetry = false
            executeWifiDialogAction()
        }
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
        statusBarUpdater.start(statusBar)
        entryGuardCoordinator.startBackgroundGuards()
        // 如果之前已就绪，重新验证 WiFi 状态（从第二层返回时可能已断开）
        if (allGuardsReady && !entryGuardCoordinator.revalidateWifiState()) {
            allGuardsReady = false
            tvInitStatus.visibility = View.GONE
        }
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
            return buildList {
                addAll(
                    UnifiedInputSession.buildPageCommonActions(
                        onConfirm = { executeWifiDialogAction() },
                        onCancel = { hideWifiRequiredDialog() },
                    ),
                )
                add(
                    UnifiedInputSession.InputActionSpec(
                        id = UnifiedInputSession.InputActionId("wifi_dialog_switch"),
                        label = "切换焦点",
                        triggers = listOf(
                            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                        ),
                        onTrigger = { moveWifiDialogFocus() },
                    ),
                )
            }
        }
        return buildList {
            // 页面通用动作：确认→点击卡片，取消→退出应用
            addAll(
                UnifiedInputSession.buildPageCommonActions(
                    onConfirm = { onItemConfirmed(selectedIndex) },
                    onCancel = { exitAppDirectly() },
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
                    this@MainMenuActivity,
                ),
            )
            // 非卡片语音指令：退出应用（独立于卡片，手动注册）
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Exit,
                    label = getString(R.string.main_menu_voice_exit_app),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice(
                            getString(R.string.main_menu_voice_exit_app),
                            getString(R.string.main_menu_voice_exit_app_pinyin),
                        ),
                    ),
                    onTrigger = { exitAppDirectly() },
                ),
            )
        }
    }

    /** 移动选中框：更新高亮 */
    private fun moveSelection(delta: Int) {
        val target = (selectedIndex + delta).coerceIn(0, menuAdapter.itemCount - 1)
        if (target == selectedIndex) return
        selectedIndex = target
        menuAdapter.selectedIndex = target
    }

    private fun onItemConfirmed(index: Int) {
        if (wifiRequiredDialogVisible) return
        val viewHolder = recyclerMenu.findViewHolderForAdapterPosition(selectedIndex) as? MenuCardAdapter.ViewHolder
        if (viewHolder != null) {
            menuAdapter.animateClick(viewHolder) { executeConfirmedAction(index) }
        } else {
            executeConfirmedAction(index)
        }
    }

    private fun executeConfirmedAction(index: Int) {
        menuAdapter.cards.getOrNull(index)?.execute()
    }

    private fun launchWifiScannerWithPermissionCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            entryGuardCoordinator.launchWifiScanner(this)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                entryGuardCoordinator.launchWifiScanner(this)
            } else {
                Toast.makeText(this, R.string.ai_entry_wifi_connect_failed, Toast.LENGTH_SHORT).show()
            }
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
                    if (allGuardsReady) {
                        tvBottomHint.setText(R.string.main_menu_bottom_hint)
                    }
                    if (hasUpdate && updateInfoJson != null) {
                        startActivity(
                            Intent(this@MainMenuActivity, AppUpdatePromptActivity::class.java).apply {
                                putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, updateInfoJson)
                            },
                        )
                    } else {
                        ToastUtil.show(getString(R.string.ai_entry_menu_update_latest))
                    }
                }
            }
        })
    }

    private fun showWifiRequiredDialog(messageResId: Int) {
        wifiRequiredDialogVisible = true
        wifiDialogSelectedRetry = true // 默认选中"重新扫码"
        tvWifiRequiredMessage.setText(messageResId)
        layoutWifiRequiredDialog.visibility = View.VISIBLE
        recyclerMenu.isEnabled = false
        tvBottomHint.visibility = View.GONE
        tvInitStatus.visibility = View.GONE
        updateWifiDialogSelection()
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
        inputSession.updateActions(buildInputActions())
    }

    private fun moveWifiDialogFocus() {
        wifiDialogSelectedRetry = !wifiDialogSelectedRetry
        updateWifiDialogSelection()
    }

    private fun updateWifiDialogSelection() {
        if (wifiDialogSelectedRetry) {
            tvWifiRetry.setBackgroundResource(R.drawable.glass_card_outline_selected)
            tvWifiRetry.setTypeface(null, android.graphics.Typeface.BOLD)
            tvWifiExit.setBackgroundResource(R.drawable.glass_card_outline)
            tvWifiExit.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            tvWifiRetry.setBackgroundResource(R.drawable.glass_card_outline)
            tvWifiRetry.setTypeface(null, android.graphics.Typeface.NORMAL)
            tvWifiExit.setBackgroundResource(R.drawable.glass_card_outline_selected)
            tvWifiExit.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun executeWifiDialogAction() {
        if (wifiDialogSelectedRetry) {
            entryGuardCoordinator.launchWifiScanner(this)
        } else {
            hideWifiRequiredDialog()
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
        private const val REQUEST_CODE_CAMERA_PERMISSION = 7001
    }
}
