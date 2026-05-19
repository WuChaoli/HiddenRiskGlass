package com.rokid.glass.updater

import android.os.Bundle
import android.widget.TextView
import com.google.gson.Gson
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import java.io.IOException
import java.util.concurrent.Executors

class AppUpdatePromptActivity : BaseGlassActivity() {
    private lateinit var tvUpdateVersion: TextView
    private lateinit var tvUpdateNotes: TextView
    private lateinit var tvUpdateStatus: TextView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val worker = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private lateinit var updateInfo: AppUpdateInfo
    private var installing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update_prompt)
        tvUpdateVersion = findViewById(R.id.tvUpdateVersion)
        tvUpdateNotes = findViewById(R.id.tvUpdateNotes)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)

        val json = intent.getStringExtra(EXTRA_UPDATE_INFO)
        if (json.isNullOrBlank()) {
            finish()
            return
        }
        updateInfo = Gson().fromJson(json, AppUpdateInfo::class.java)
        tvUpdateVersion.text = getString(R.string.app_update_version, updateInfo.versionName)
        tvUpdateNotes.text = updateInfo.releaseNotes.ifBlank { getString(R.string.app_update_notes_empty) }
        refreshInputActions()
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
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.app_update_install_now),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_install_now), "li ji an zhuang"),
                    ),
                    enabled = { !installing },
                ) {
                    installUpdate()
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId("return"),
                    label = getString(R.string.app_update_skip),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_skip), "tiao guo ben ci"),
                    ),
                    enabled = { !installing },
                ) {
                    updateManager.skipVersion(updateInfo.versionCode)
                    finish()
                },
            ),
        )
    }

    private fun installUpdate() {
        if (installing) return
        installing = true
        tvUpdateStatus.setText(R.string.app_update_downloading)
        refreshInputActions()
        worker.execute {
            try {
                if (!updateManager.canRequestPackageInstalls()) {
                    runOnUiThread {
                        installing = false
                        tvUpdateStatus.setText(R.string.app_update_permission_required)
                        updateManager.openInstallPermissionSettings()
                        refreshInputActions()
                    }
                    return@execute
                }
                updateManager.downloadAndInstall(updateInfo)
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
                    refreshInputActions()
                }
            }
        }
    }

    companion object {
        const val EXTRA_UPDATE_INFO = "update_info"
        private const val TAG = "AppUpdatePrompt"
    }
}
