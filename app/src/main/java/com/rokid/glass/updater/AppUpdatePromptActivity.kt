package com.rokid.glass.updater

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.gson.Gson
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import java.io.IOException
import java.util.concurrent.Executors

internal fun moveUpdatePromptSelection(currentIndex: Int, delta: Int, lastIndex: Int): Int {
    return (currentIndex + delta).coerceIn(0, lastIndex)
}

/**
 * 版本更新提示弹窗 Activity。
 *
 * 提供三个操作按钮：
 * - 更新：立即下载并安装新版本，下载过程中显示进度条
 * - 跳过本次：持久化跳过当前版本，后续不再提示该版本
 * - 取消：关闭弹窗，下次启动时重新提示
 */
class AppUpdatePromptActivity : BaseGlassActivity() {
    /** 更新弹窗不需要自动常亮，避免 onPause 清除 FLAG_KEEP_SCREEN_ON 时导致短暂失焦熄灭 */
    override val keepScreenOnEnabled: Boolean
        get() = false
    private lateinit var tvUpdateVersion: TextView
    private lateinit var tvUpdateNotes: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var layoutProgress: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var layoutActions: LinearLayout
    private lateinit var btnUpdate: TextView
    private lateinit var btnSkip: TextView
    private lateinit var btnCancel: TextView
    private lateinit var actionButtons: List<TextView>

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val worker = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private lateinit var updateInfo: AppUpdateInfo
    private var installing = false
    private var selectedIndex = ACTION_UPDATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update_prompt)
        initViews()

        val json = intent.getStringExtra(EXTRA_UPDATE_INFO)
        if (json.isNullOrBlank()) {
            finish()
            return
        }
        updateInfo = Gson().fromJson(json, AppUpdateInfo::class.java)
        tvUpdateVersion.text = getString(R.string.app_update_version, updateInfo.versionName)
        tvUpdateNotes.text = updateInfo.releaseNotes.ifBlank { getString(R.string.app_update_notes_empty) }
        setupButtonListeners()
        updateSelection()
        refreshInputActions()
    }

    private fun initViews() {
        tvUpdateVersion = findViewById(R.id.tvUpdateVersion)
        tvUpdateNotes = findViewById(R.id.tvUpdateNotes)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        layoutProgress = findViewById(R.id.layoutProgress)
        progressBar = findViewById(R.id.progressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        layoutActions = findViewById(R.id.layoutActions)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnSkip = findViewById(R.id.btnSkip)
        btnCancel = findViewById(R.id.btnCancel)
        actionButtons = listOf(btnUpdate, btnSkip, btnCancel)
    }

    private fun setupButtonListeners() {
        btnUpdate.setOnClickListener { executeAction(ACTION_UPDATE) }
        btnSkip.setOnClickListener { executeAction(ACTION_SKIP) }
        btnCancel.setOnClickListener { executeAction(ACTION_CANCEL) }
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun refreshInputActions() {
        inputSession.updateActions(
            listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                    ),
                    enabled = { !installing },
                ) {
                    moveSelection(-1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                    ),
                    enabled = { !installing },
                ) {
                    moveSelection(1)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = "确认",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                    ),
                    enabled = { !installing },
                ) {
                    executeAction(selectedIndex)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("update_install"),
                    label = getString(R.string.app_update_install_now),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_install_now), "li ji an zhuang"),
                    ),
                    enabled = { !installing },
                ) {
                    executeAction(ACTION_UPDATE)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("skip"),
                    label = getString(R.string.app_update_skip),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_skip), "tiao guo ben ci"),
                    ),
                    enabled = { !installing },
                ) {
                    executeAction(ACTION_SKIP)
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Cancel,
                    label = getString(R.string.app_update_button_cancel),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_button_cancel), "qu xiao"),
                    ),
                    enabled = { !installing },
                ) {
                    executeAction(ACTION_CANCEL)
                },
            ),
        )
    }

    private fun moveSelection(delta: Int) {
        val targetIndex = moveUpdatePromptSelection(selectedIndex, delta, actionButtons.lastIndex)
        if (targetIndex == selectedIndex) return
        selectedIndex = targetIndex
        updateSelection()
    }

    private fun updateSelection() {
        actionButtons.forEachIndexed { index, button ->
            button.setBackgroundResource(
                if (index == selectedIndex) R.drawable.glass_card_outline_selected
                else R.drawable.glass_card_outline,
            )
            button.setTextColor(
                getColor(
                    if (index == selectedIndex) R.color.rokid_glass_dialog_button_focused_text_color
                    else R.color.green,
                ),
            )
        }
    }

    private fun executeAction(index: Int) {
        when (index) {
            ACTION_UPDATE -> installUpdate()
            ACTION_SKIP -> {
                updateManager.skipVersion(updateInfo.versionCode)
                finish()
            }
            ACTION_CANCEL -> {
                updateManager.skipCurrentSession()
                finish()
            }
        }
    }

    private fun installUpdate() {
        if (installing) return
        installing = true
        tvUpdateStatus.setText(R.string.app_update_downloading)
        layoutActions.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        refreshInputActions()
        worker.execute {
            try {
                if (!updateManager.canRequestPackageInstalls()) {
                    runOnUiThread {
                        installing = false
                        tvUpdateStatus.setText(R.string.app_update_permission_required)
                        layoutActions.visibility = View.VISIBLE
                        layoutProgress.visibility = View.GONE
                        updateManager.openInstallPermissionSettings()
                        refreshInputActions()
                    }
                    return@execute
                }
                updateManager.downloadAndInstall(updateInfo) { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        val percent = ((bytesRead * 100) / totalBytes).toInt()
                        runOnUiThread {
                            progressBar.progress = percent
                            tvProgressPercent.text = getString(R.string.app_update_progress_percent, percent)
                        }
                    }
                }
            } catch (error: IOException) {
                runOnUiThread {
                    installing = false
                    tvUpdateStatus.text = when {
                        error.message?.contains("sha256", ignoreCase = true) == true ->
                            getString(R.string.app_update_verify_failed)
                        error.message?.contains("installer", ignoreCase = true) == true ->
                            getString(R.string.app_update_installer_failed)
                        else -> getString(R.string.app_update_download_failed)
                    }
                    layoutActions.visibility = View.VISIBLE
                    layoutProgress.visibility = View.GONE
                    refreshInputActions()
                }
            }
        }
    }

    companion object {
        const val EXTRA_UPDATE_INFO = "update_info"
        private const val TAG = "AppUpdatePrompt"
        private const val ACTION_UPDATE = 0
        private const val ACTION_SKIP = 1
        private const val ACTION_CANCEL = 2
    }
}
