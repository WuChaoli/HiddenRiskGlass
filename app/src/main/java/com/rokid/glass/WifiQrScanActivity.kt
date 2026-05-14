package com.rokid.glass

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiNetworkSuggestion
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.AppFileLogger
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.utils.WifiQrParser
import com.rokid.glass.utils.WifiQrPayload
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner

class WifiQrScanActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "RokidWifiQrScan"
        private const val VERIFY_INTERVAL_MS = 500L
        private const val VERIFY_TIMEOUT_MS = 15_000L
        private const val RESULT_STAY_MS = 1000L
        const val EXTRA_NEXT_AFTER_SUCCESS = "extra_next_after_success"
    }

    private enum class ConnectionStage {
        READY_TO_SCAN,
        WAITING_SCAN_RESULT,
        WAITING_SYSTEM_RESULT,
        VERIFYING_CONNECTION,
        CONNECTING_WITH_SPECIFIER,
        SHOWING_RESULT,
    }

    private data class PrivateWifiEntry(
        val action: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val label: String,
    )

    private lateinit var tvStatus: TextView
    private lateinit var resultOverlay: FrameLayout
    private lateinit var tvResultMessage: TextView
    private lateinit var viewfinder: View
    private lateinit var tvScanHint: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var ivResultIcon: ImageView
    private lateinit var tvResultStatusInFrame: TextView
    private lateinit var tvErrorDetail: TextView
    private lateinit var infoCard: LinearLayout
    private lateinit var bottomHints: LinearLayout
    private lateinit var statusBar: GlassStatusBar

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectionStage = ConnectionStage.READY_TO_SCAN
    private var pendingPayload: WifiQrPayload? = null
    private var activeStrategyName: String? = null
    private var awaitingPrivateFlowReturn = false
    private var verifyDeadlineMs = 0L
    private var nextAfterSuccess: String? = null
    private var nextHomeActivityClassName: String? = null
    private var resultWasSuccess = false
    private var scanLaunchInFlight = false

    private val finishResultRunnable = Runnable { completeDisplayedResult() }

    private val addNetworksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val payload = pendingPayload
            AppFileLogger.i(
                TAG,
                "add networks result resultCode=${result.resultCode} strategy=$activeStrategyName target=${payload?.ssid}",
            )
            if (payload == null) {
                restartScan(getString(R.string.rokid_wifi_scan_launching))
                return@registerForActivityResult
            }
            val currentSsid = SystemStateUtils.getCurrentWifiSsid(this)
            if (currentSsid == payload.ssid) {
                handleConnectionVerified(payload, "add_networks_result")
                return@registerForActivityResult
            }
            if (result.resultCode == Activity.RESULT_CANCELED) {
                AppFileLogger.i(TAG, "user cancelled add networks flow ssid=${payload.ssid}")
                restartScan(getString(R.string.rokid_wifi_scan_cancelled))
                return@registerForActivityResult
            }
            startVerification(payload, "add_networks_result")
        }

    private val verifyRunnable = object : Runnable {
        override fun run() {
            val payload = pendingPayload
            if (payload == null || connectionStage != ConnectionStage.VERIFYING_CONNECTION) {
                return
            }
            val currentSsid = SystemStateUtils.getCurrentWifiSsid(this@WifiQrScanActivity)
            if (currentSsid == payload.ssid) {
                handleConnectionVerified(payload, "verification")
                return
            }
            if (System.currentTimeMillis() >= verifyDeadlineMs) {
                AppFileLogger.w(
                    TAG,
                    "verify timeout strategy=$activeStrategyName target=${payload.ssid} current=$currentSsid",
                )
                restartScan(getString(R.string.wifi_scan_connect_failed))
                return
            }
            mainHandler.postDelayed(this, VERIFY_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_qr_scan)

        nextAfterSuccess = intent.getStringExtra(EXTRA_NEXT_AFTER_SUCCESS)
        nextHomeActivityClassName = intent.getStringExtra(InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY)
        tvStatus = findViewById(R.id.tvStatus)
        resultOverlay = findViewById(R.id.resultOverlay)
        tvResultMessage = findViewById(R.id.tvResultMessage)
        viewfinder = findViewById(R.id.viewfinder)
        tvScanHint = findViewById(R.id.tvScanHint)
        resultContent = findViewById(R.id.resultContent)
        ivResultIcon = findViewById(R.id.ivResultIcon)
        tvResultStatusInFrame = findViewById(R.id.tvResultStatusInFrame)
        tvErrorDetail = findViewById(R.id.tvErrorDetail)
        infoCard = findViewById(R.id.infoCard)
        bottomHints = findViewById(R.id.bottomHints)
        statusBar = findViewById(R.id.statusBar)

        findViewById<View>(R.id.cameraPreviewView).visibility = View.INVISIBLE
        tvStatus.text = getString(R.string.rokid_wifi_scan_launching)
        tvScanHint.text = getString(R.string.rokid_wifi_scan_hint)
        hideBottomHints()
        updateBatteryLevel()
        refreshInputActions()
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
        if (awaitingPrivateFlowReturn && pendingPayload != null) {
            awaitingPrivateFlowReturn = false
            AppFileLogger.i(TAG, "private/system wifi flow returned target=${pendingPayload?.ssid}")
            startVerification(requireNotNull(pendingPayload), "private_system_resume")
            return
        }
        if (connectionStage == ConnectionStage.READY_TO_SCAN && !scanLaunchInFlight) {
            launchRokidScanner()
        }
    }

    override fun onPause() {
        inputSession.detach()
        if (connectionStage != ConnectionStage.VERIFYING_CONNECTION &&
            connectionStage != ConnectionStage.SHOWING_RESULT
        ) {
            mainHandler.removeCallbacks(verifyRunnable)
        }
        super.onPause()
    }

    override fun onDestroy() {
        inputSession.release()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun launchRokidScanner() {
        connectionStage = ConnectionStage.WAITING_SCAN_RESULT
        scanLaunchInFlight = true
        tvStatus.text = getString(R.string.rokid_wifi_scan_launching)
        AppFileLogger.i(TAG, "launch Rokid scanner")
        refreshInputActions()
        GlassScanner.launch(this, scanCallback = object : GlassScanCallback {
            override fun onScanSuccess(content: String?, barcode: Barcode) {
                scanLaunchInFlight = false
                runOnUiThread {
                    AppFileLogger.i(TAG, "scan success raw=${barcode.rawValue}")
                    handleRawScanResult(content ?: barcode.rawValue)
                }
            }

            override fun onScanFailure(error: String) {
                scanLaunchInFlight = false
                runOnUiThread {
                    AppFileLogger.w(TAG, "scan failure error=$error")
                    if (connectionStage == ConnectionStage.WAITING_SCAN_RESULT) {
                        showResultAndFinish(getString(R.string.rokid_wifi_scan_failed))
                    }
                }
            }
        })
    }

    private fun handleRawScanResult(rawValue: String?) {
        val raw = rawValue?.takeIf { it.isNotBlank() }
        if (raw == null) {
            showResultAndFinish(getString(R.string.rokid_wifi_scan_cancelled))
            return
        }
        val payload = WifiQrParser.parse(raw)
        when {
            payload == null -> restartScan(getString(R.string.wifi_scan_invalid))
            payload.security == WifiQrPayload.SecurityType.WEP ->
                restartScan(getString(R.string.wifi_scan_unsupported_security))
            else -> {
                tvStatus.text = getString(R.string.rokid_wifi_scan_success, payload.ssid)
                connectToWifi(payload)
            }
        }
    }

    private fun connectToWifi(payload: WifiQrPayload) {
        pendingPayload = payload
        activeStrategyName = null
        mainHandler.removeCallbacks(verifyRunnable)
        connectionStage = ConnectionStage.WAITING_SYSTEM_RESULT
        refreshInputActions()
        AppFileLogger.i(
            TAG,
            "wifi qr parsed ssid=${payload.ssid} security=${payload.security} hidden=${payload.hidden}",
        )

        if (launchPrivateWifiFlow(payload)) return
        if (launchAddNetworksFlow(payload)) return
        restartScan(getString(R.string.wifi_scan_connect_failed))
    }

    private fun launchPrivateWifiFlow(payload: WifiQrPayload): Boolean {
        privateWifiEntries().forEach { entry ->
            val intent = Intent().apply {
                entry.action?.let(::setAction)
                if (entry.packageName != null && entry.className != null) {
                    component = ComponentName(entry.packageName, entry.className)
                }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val resolved = intent.resolveActivity(packageManager)
            if (resolved != null) {
                activeStrategyName = "private_system:${entry.label}"
                awaitingPrivateFlowReturn = true
                tvStatus.text = getString(R.string.wifi_scan_strategy_system, payload.ssid)
                startActivity(intent)
                AppFileLogger.i(TAG, "launch private/system wifi flow label=${entry.label} target=${payload.ssid}")
                return true
            }
        }
        return false
    }

    private fun launchAddNetworksFlow(payload: WifiQrPayload): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        val suggestionBuilder = WifiNetworkSuggestion.Builder().setSsid(payload.ssid)
        when (payload.security) {
            WifiQrPayload.SecurityType.OPEN -> Unit
            WifiQrPayload.SecurityType.WPA -> suggestionBuilder.setWpa2Passphrase(payload.password!!)
            WifiQrPayload.SecurityType.WEP -> return false
        }
        if (payload.hidden) {
            suggestionBuilder.setIsHiddenSsid(true)
        }
        val suggestion = suggestionBuilder.build()
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
        }
        if (intent.resolveActivity(packageManager) == null) {
            return false
        }
        activeStrategyName = "add_networks"
        connectionStage = ConnectionStage.WAITING_SYSTEM_RESULT
        refreshInputActions()
        tvStatus.text = getString(R.string.wifi_scan_strategy_add_networks, payload.ssid)
        addNetworksLauncher.launch(intent)
        return true
    }

    private fun startVerification(payload: WifiQrPayload, source: String) {
        connectionStage = ConnectionStage.VERIFYING_CONNECTION
        refreshInputActions()
        activeStrategyName = source
        verifyDeadlineMs = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        tvStatus.text = getString(R.string.wifi_scan_verify_connection, payload.ssid)
        mainHandler.removeCallbacks(verifyRunnable)
        mainHandler.post(verifyRunnable)
    }

    private fun handleConnectionVerified(payload: WifiQrPayload, source: String) {
        connectionStage = ConnectionStage.SHOWING_RESULT
        activeStrategyName = source
        mainHandler.removeCallbacks(verifyRunnable)
        tvStatus.text = getString(R.string.wifi_scan_success, payload.ssid)
        InspectionWorkflowSession.updateMode(connected = true)
        showResultAndFinish(getString(R.string.wifi_scan_success_generic))
    }

    private fun showResultAndFinish(message: String) {
        connectionStage = ConnectionStage.SHOWING_RESULT
        resultWasSuccess = message == getString(R.string.wifi_scan_success_generic)

        viewfinder.visibility = View.INVISIBLE
        tvScanHint.visibility = View.GONE
        infoCard.visibility = View.GONE
        findViewById<View>(R.id.scanFrame).background = null
        resultContent.visibility = View.VISIBLE
        ivResultIcon.setImageResource(if (resultWasSuccess) R.drawable.ic_check_circle else R.drawable.ic_close_circle)
        tvResultStatusInFrame.text =
            if (resultWasSuccess) getString(R.string.wifi_scan_result_success) else getString(R.string.wifi_scan_result_failed)
        tvErrorDetail.visibility = if (resultWasSuccess) View.GONE else View.VISIBLE
        hideBottomHints()

        mainHandler.removeCallbacks(finishResultRunnable)
        if (resultWasSuccess) {
            mainHandler.postDelayed(finishResultRunnable, RESULT_STAY_MS)
        }
        refreshInputActions()
    }

    private fun completeDisplayedResult() {
        if (isFinishing) return
        mainHandler.removeCallbacks(finishResultRunnable)
        val targetClassName = nextAfterSuccess
        if (resultWasSuccess && !targetClassName.isNullOrBlank()) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val targetClass = Class.forName(targetClassName) as Class<out Activity>
                startActivity(Intent(this, targetClass).apply {
                    if (targetClass == InspectionLoadingActivity::class.java) {
                        putExtra(InspectionLoadingActivity.EXTRA_FORCE_LOADING_FLOW, true)
                        nextHomeActivityClassName?.let {
                            putExtra(InspectionLoadingActivity.EXTRA_NEXT_HOME_ACTIVITY, it)
                        }
                    }
                })
                finish()
            }.onFailure {
                finish()
            }
            return
        }
        finish()
    }

    private fun retryScan() {
        resultWasSuccess = false
        viewfinder.visibility = View.VISIBLE
        tvScanHint.visibility = View.VISIBLE
        infoCard.visibility = View.VISIBLE
        findViewById<View>(R.id.scanFrame).setBackgroundResource(R.drawable.glass_scan_frame)
        resultContent.visibility = View.GONE
        tvErrorDetail.visibility = View.GONE
        hideBottomHints()
        resetToReadyState(getString(R.string.rokid_wifi_scan_retry))
        launchRokidScanner()
    }

    private fun resetToReadyState(message: String) {
        mainHandler.removeCallbacks(verifyRunnable)
        pendingPayload = null
        activeStrategyName = null
        awaitingPrivateFlowReturn = false
        connectionStage = ConnectionStage.READY_TO_SCAN
        scanLaunchInFlight = false
        tvStatus.text = message
        refreshInputActions()
    }

    private fun restartScan(message: String) {
        resetToReadyState(message)
        launchRokidScanner()
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = { isPrimaryActionEnabled() },
            ) {
                handlePrimaryAction()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
            ) {
                exitAppDirectly()
            },
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun isPrimaryActionEnabled(): Boolean {
        return when (connectionStage) {
            ConnectionStage.READY_TO_SCAN,
            ConnectionStage.SHOWING_RESULT -> true
            ConnectionStage.WAITING_SCAN_RESULT,
            ConnectionStage.WAITING_SYSTEM_RESULT,
            ConnectionStage.VERIFYING_CONNECTION,
            ConnectionStage.CONNECTING_WITH_SPECIFIER -> false
        }
    }

    private fun handlePrimaryAction() {
        when (connectionStage) {
            ConnectionStage.READY_TO_SCAN -> launchRokidScanner()
            ConnectionStage.SHOWING_RESULT -> {
                if (resultWasSuccess) {
                    completeDisplayedResult()
                } else {
                    retryScan()
                }
            }
            else -> Unit
        }
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

    private fun voiceTrigger(textRes: Int, pinyin: String): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    private fun privateWifiEntries(): List<PrivateWifiEntry> = emptyList()

    private fun hideBottomHints() {
        bottomHints.visibility = View.GONE
    }

    private fun exitAppDirectly() {
        InspectionCameraCoordinator.releaseAppCamera(reason = "rokid_wifi_scan_exit_app_directly")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }

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
}
