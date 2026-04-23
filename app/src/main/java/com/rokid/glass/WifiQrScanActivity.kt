package com.rokid.glass

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.utils.WifiQrParser
import com.rokid.glass.utils.WifiQrPayload
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class WifiQrScanActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "WifiQrScanActivity"
        private const val REQUEST_CODE_PERMISSIONS = 5001
        private const val SCAN_INTERVAL_MS = 800L
        private const val SCAN_FRAME_TARGET_SIZE = 480
        private const val INVALID_QR_COOLDOWN_MS = 1600L
        private const val CONNECT_RESULT_COOLDOWN_MS = 2200L
        private const val VERIFY_INTERVAL_MS = 500L
        private const val VERIFY_TIMEOUT_MS = 15_000L
        private const val RESULT_STAY_MS = 1000L
        const val EXTRA_NEXT_AFTER_SUCCESS = "extra_next_after_success"
    }

    private enum class ConnectionStage {
        SCANNING,
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

    private lateinit var cameraPreviewView: RokidCameraPreviewView
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
    private lateinit var tvVoiceHint: TextView
    private lateinit var tvTouchHint: TextView
    private lateinit var statusBar: GlassStatusBar

    private val scanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCameraReady = false
    private var isProcessingFrame = false
    private var scanBlockedUntilMs = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectionStage = ConnectionStage.SCANNING
    private var pendingPayload: WifiQrPayload? = null
    private var activeStrategyName: String? = null
    private var awaitingPrivateFlowReturn = false
    private var verifyDeadlineMs = 0L
    private var nextAfterSuccess: String? = null
    private var debugSnapshotMode = false

    @Volatile
    private var latestPreviewFrame: ByteArray? = null

    @Volatile
    private var latestPreviewWidth = 0

    @Volatile
    private var latestPreviewHeight = 0

    private val addNetworksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val payload = pendingPayload
            Log.i(
                TAG,
                "add networks result resultCode=${result.resultCode} strategy=$activeStrategyName target=${payload?.ssid}"
            )
            if (payload == null) {
                resetToScanning(getString(R.string.wifi_scan_waiting), 0L)
                return@registerForActivityResult
            }
            val currentSsid = SystemStateUtils.getCurrentWifiSsid(this)
            if (currentSsid == payload.ssid) {
                handleConnectionVerified(payload, "add_networks_result")
                return@registerForActivityResult
            }
            if (result.resultCode == Activity.RESULT_CANCELED) {
                Log.i(TAG, "user cancelled add networks flow ssid=${payload.ssid}")
                finishDirectly()
                return@registerForActivityResult
            }
            startVerification(payload, "add_networks_result")
        }

    private val scanRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now < scanBlockedUntilMs) {
                mainHandler.postDelayed(this, maxOf(200L, scanBlockedUntilMs - now))
                return
            }
            if (connectionStage != ConnectionStage.SCANNING ||
                !isCameraReady ||
                isProcessingFrame
            ) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            if (!refreshLatestFrameFromSdk()) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            val frame = latestPreviewFrame
            val width = latestPreviewWidth
            val height = latestPreviewHeight
            if (frame == null || width <= 0 || height <= 0) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            isProcessingFrame = true
            scanner.process(InputImage.fromByteArray(frame, width, height, 0, ImageFormat.NV21))
                .addOnSuccessListener { barcodes -> handleScanResult(barcodes) }
                .addOnFailureListener { error -> Log.w(TAG, "scan failed: ${error.message}") }
                .addOnCompleteListener {
                    isProcessingFrame = false
                    mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                }
        }
    }

    private val verifyRunnable = object : Runnable {
        override fun run() {
            val payload = pendingPayload
            if (payload == null || connectionStage != ConnectionStage.VERIFYING_CONNECTION) {
                return
            }
            val currentSsid = SystemStateUtils.getCurrentWifiSsid(this@WifiQrScanActivity)
            Log.d(
                TAG,
                "verify tick strategy=$activeStrategyName target=${payload.ssid} current=$currentSsid deadline=$verifyDeadlineMs"
            )
            if (currentSsid == payload.ssid) {
                handleConnectionVerified(payload, "verification")
                return
            }
            if (System.currentTimeMillis() >= verifyDeadlineMs) {
                Log.w(TAG, "verify timeout strategy=$activeStrategyName target=${payload.ssid} current=$currentSsid")
                showResultAndFinish(getString(R.string.wifi_scan_connect_failed))
                return
            }
            mainHandler.postDelayed(this, VERIFY_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_qr_scan)

        nextAfterSuccess = intent.getStringExtra(EXTRA_NEXT_AFTER_SUCCESS)
        debugSnapshotMode = intent.getBooleanExtra("debug_snapshot", false)
        cameraPreviewView = findViewById(R.id.cameraPreviewView)
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
        tvVoiceHint = findViewById(R.id.tvVoiceHint)
        tvTouchHint = findViewById(R.id.tvTouchHint)
        statusBar = findViewById(R.id.statusBar)
        updateBatteryLevel()

        if (debugSnapshotMode) {
            applyDebugSnapshotState()
        }
    }

    override fun onResume() {
        super.onResume()
        if (debugSnapshotMode) return
        if (awaitingPrivateFlowReturn && pendingPayload != null) {
            awaitingPrivateFlowReturn = false
            Log.i(TAG, "private/system wifi flow returned target=${pendingPayload?.ssid}")
            startVerification(requireNotNull(pendingPayload), "private_system_resume")
            return
        }
        when (connectionStage) {
            ConnectionStage.WAITING_SYSTEM_RESULT,
            ConnectionStage.CONNECTING_WITH_SPECIFIER,
            ConnectionStage.VERIFYING_CONNECTION,
            ConnectionStage.SHOWING_RESULT -> {
                Log.i(TAG, "onResume stage=$connectionStage strategy=$activeStrategyName target=${pendingPayload?.ssid}")
                return
            }
            else -> Unit
        }
        if (hasRequiredPermissions()) {
            startCameraPipeline()
            startScanLoop()
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        if (debugSnapshotMode) {
            super.onPause()
            return
        }
        mainHandler.removeCallbacks(scanRunnable)
        if (connectionStage != ConnectionStage.VERIFYING_CONNECTION &&
            connectionStage != ConnectionStage.SHOWING_RESULT
        ) {
            mainHandler.removeCallbacks(verifyRunnable)
        }
        stopCameraPipeline()
        if (connectionStage == ConnectionStage.CONNECTING_WITH_SPECIFIER ||
            connectionStage == ConnectionStage.WAITING_SYSTEM_RESULT
        ) {
            Log.i(TAG, "onPause keep flow alive stage=$connectionStage strategy=$activeStrategyName")
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (!debugSnapshotMode) {
            mainHandler.removeCallbacksAndMessages(null)
            cancelNetworkRequest()
            scanner.close()
            stopCameraPipeline()
        }
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                if (connectionStage == ConnectionStage.SCANNING) {
                    scanBlockedUntilMs = 0L
                    tvStatus.setText(R.string.wifi_scan_waiting)
                    startScanLoop()
                }
                true
            }
            GlassKeyEvent.KEYCODE_BACK,
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            tvStatus.setText(R.string.wifi_scan_permission_denied)
            return
        }
        startCameraPipeline()
        startScanLoop()
    }

    private fun startCameraPipeline() {
        if (!hasRequiredPermissions()) {
            return
        }
        cameraPreviewView.startPreview { success ->
            runOnUiThread {
                if (!success) {
                    tvStatus.setText(R.string.wifi_scan_camera_error)
                }
            }
        }
        RokidFrameSource.startFrameStream { success ->
            runOnUiThread {
                isCameraReady = success
                if (!success) {
                    tvStatus.setText(R.string.wifi_scan_camera_error)
                    return@runOnUiThread
                }
                if (connectionStage == ConnectionStage.SCANNING) {
                    tvStatus.setText(R.string.wifi_scan_waiting)
                    startScanLoop()
                }
            }
        }
    }

    private fun stopCameraPipeline() {
        cameraPreviewView.stopPreview()
        RokidFrameSource.stopFrameStream()
        isCameraReady = false
        isProcessingFrame = false
        latestPreviewFrame = null
        latestPreviewWidth = 0
        latestPreviewHeight = 0
    }

    private fun refreshLatestFrameFromSdk(): Boolean {
        val frame = RokidFrameSource.copyLatestCroppedFrame(SCAN_FRAME_TARGET_SIZE) ?: return false
        latestPreviewFrame = frame.data
        latestPreviewWidth = frame.width
        latestPreviewHeight = frame.height
        return true
    }

    private fun startScanLoop() {
        if (connectionStage != ConnectionStage.SCANNING) {
            return
        }
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    private fun handleScanResult(barcodes: List<Barcode>) {
        if (connectionStage != ConnectionStage.SCANNING) {
            return
        }
        val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return
        val payload = WifiQrParser.parse(rawValue)
        when {
            payload == null -> {
                Log.i(TAG, "invalid wifi qr raw=$rawValue")
                showTemporaryStatus(getString(R.string.wifi_scan_invalid), INVALID_QR_COOLDOWN_MS)
            }
            payload.security == WifiQrPayload.SecurityType.WEP -> {
                Log.i(TAG, "unsupported wifi security security=${payload.security}")
                showTemporaryStatus(getString(R.string.wifi_scan_unsupported_security), INVALID_QR_COOLDOWN_MS)
            }
            !SystemStateUtils.isWifiEnabled(this) -> {
                Log.i(TAG, "wifi disabled while scanning, continue with system connect flow ssid=${payload.ssid}")
                connectToWifi(payload)
            }
            else -> connectToWifi(payload)
        }
    }

    private fun connectToWifi(payload: WifiQrPayload) {
        cancelNetworkRequest()
        pendingPayload = payload
        activeStrategyName = null
        scanBlockedUntilMs = Long.MAX_VALUE
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.removeCallbacks(verifyRunnable)
        connectionStage = ConnectionStage.WAITING_SYSTEM_RESULT
        Log.i(
            TAG,
            "wifi qr parsed ssid=${payload.ssid} security=${payload.security} hidden=${payload.hidden}"
        )

        if (launchPrivateWifiFlow(payload)) {
            return
        }
        if (launchAddNetworksFlow(payload)) {
            return
        }
        startSpecifierFallback(payload, "no_system_flow_available")
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
            Log.i(
                TAG,
                "probe private/system wifi entry label=${entry.label} resolved=${resolved != null} action=${entry.action} component=${entry.packageName}/${entry.className}"
            )
            if (resolved != null) {
                activeStrategyName = "private_system:${entry.label}"
                awaitingPrivateFlowReturn = true
                tvStatus.text = getString(R.string.wifi_scan_strategy_system, payload.ssid)
                startActivity(intent)
                Log.i(TAG, "launch private/system wifi flow label=${entry.label} target=${payload.ssid}")
                return true
            }
        }
        Log.i(TAG, "no private/system wifi entry available target=${payload.ssid}")
        return false
    }

    private fun launchAddNetworksFlow(payload: WifiQrPayload): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "skip add networks flow due to sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        val suggestionBuilder = WifiNetworkSuggestion.Builder()
            .setSsid(payload.ssid)
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
        val resolved = intent.resolveActivity(packageManager)
        Log.i(TAG, "probe add networks flow resolved=${resolved != null} target=${payload.ssid}")
        if (resolved == null) {
            return false
        }
        activeStrategyName = "add_networks"
        connectionStage = ConnectionStage.WAITING_SYSTEM_RESULT
        tvStatus.text = getString(R.string.wifi_scan_strategy_add_networks, payload.ssid)
        Log.i(TAG, "launch add networks flow target=${payload.ssid}")
        addNetworksLauncher.launch(intent)
        return true
    }

    private fun startVerification(payload: WifiQrPayload, source: String) {
        connectionStage = ConnectionStage.VERIFYING_CONNECTION
        activeStrategyName = source
        verifyDeadlineMs = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        tvStatus.text = getString(R.string.wifi_scan_verify_connection, payload.ssid)
        mainHandler.removeCallbacks(verifyRunnable)
        mainHandler.post(verifyRunnable)
        Log.i(TAG, "start verification source=$source target=${payload.ssid} deadline=$verifyDeadlineMs")
    }

    private fun handleConnectionVerified(payload: WifiQrPayload, source: String) {
        connectionStage = ConnectionStage.SHOWING_RESULT
        activeStrategyName = source
        mainHandler.removeCallbacks(verifyRunnable)
        mainHandler.removeCallbacks(scanRunnable)
        scanBlockedUntilMs = Long.MAX_VALUE
        tvStatus.text = getString(R.string.wifi_scan_success, payload.ssid)
        InspectionWorkflowSession.updateMode(connected = true)
        Log.i(TAG, "wifi connection verified source=$source target=${payload.ssid}")
        showResultAndFinish(getString(R.string.wifi_scan_success_generic))
    }

    private fun startSpecifierFallback(payload: WifiQrPayload, reason: String) {
        cancelNetworkRequest()
        awaitingPrivateFlowReturn = false
        connectionStage = ConnectionStage.CONNECTING_WITH_SPECIFIER
        activeStrategyName = "specifier_fallback:$reason"
        tvStatus.text = getString(R.string.wifi_scan_connecting, payload.ssid)
        Log.i(TAG, "start specifier fallback reason=$reason target=${payload.ssid}")

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(payload.ssid)
        when (payload.security) {
            WifiQrPayload.SecurityType.OPEN -> Unit
            WifiQrPayload.SecurityType.WPA -> specifierBuilder.setWpa2Passphrase(payload.password!!)
            WifiQrPayload.SecurityType.WEP -> {
                resetToScanning(getString(R.string.wifi_scan_unsupported_security), CONNECT_RESULT_COOLDOWN_MS)
                return
            }
        }
        if (payload.hidden) {
            specifierBuilder.setIsHiddenSsid(true)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    Log.i(TAG, "specifier network available target=${payload.ssid}")
                    connectivityManager.bindProcessToNetwork(network)
                    handleConnectionVerified(payload, "specifier")
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    Log.w(TAG, "specifier network unavailable target=${payload.ssid}")
                    showResultAndFinish(getString(R.string.wifi_scan_connect_failed))
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isFinishing) {
                        Log.w(TAG, "specifier network lost target=${payload.ssid}")
                        showResultAndFinish(getString(R.string.wifi_scan_connect_failed))
                    }
                }
            }
        }
        networkCallback = callback
        tvStatus.setText(R.string.wifi_scan_wait_system)
        Log.i(TAG, "requestNetwork submitted waiting for system confirmation target=${payload.ssid}")
        runCatching {
            connectivityManager.requestNetwork(request, callback, VERIFY_TIMEOUT_MS.toInt())
        }.onFailure { error ->
            Log.w(TAG, "requestNetwork failed target=${payload.ssid} error=${error.message}")
            showResultAndFinish(getString(R.string.wifi_scan_connect_failed))
        }
    }

    private fun cancelNetworkRequest() {
        val callback = networkCallback ?: return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        networkCallback = null
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_CODE_PERMISSIONS)
    }

    private fun applyDebugSnapshotState() {
        cameraPreviewView.visibility = View.INVISIBLE
        viewfinder.visibility = View.INVISIBLE
        val scanFrame = findViewById<View>(R.id.scanFrame)
        val state = intent.getStringExtra("debug_state") ?: "idle"
        when (state) {
            "success" -> {
                scanFrame.background = null
                tvStatus.visibility = View.GONE
                tvScanHint.visibility = View.GONE
                infoCard.visibility = View.GONE
                resultContent.visibility = View.VISIBLE
                ivResultIcon.setImageResource(R.drawable.ic_check_circle)
                tvResultStatusInFrame.text = getString(R.string.wifi_scan_result_success)
                bottomHints.visibility = View.VISIBLE
                tvVoiceHint.setText(R.string.wifi_scan_voice_hint)
                tvTouchHint.setText(R.string.wifi_scan_touch_hint)
            }
            "failed" -> {
                scanFrame.background = null
                tvStatus.visibility = View.GONE
                tvScanHint.visibility = View.GONE
                infoCard.visibility = View.GONE
                resultContent.visibility = View.VISIBLE
                ivResultIcon.setImageResource(R.drawable.ic_close_circle)
                tvResultStatusInFrame.text = getString(R.string.wifi_scan_result_failed)
                tvErrorDetail.visibility = View.VISIBLE
                bottomHints.visibility = View.VISIBLE
                tvVoiceHint.setText(R.string.wifi_scan_voice_hint_retry)
                tvTouchHint.setText(R.string.wifi_scan_touch_hint_retry)
            }
            else -> {
                tvStatus.visibility = View.GONE
                tvScanHint.visibility = View.VISIBLE
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    private fun resetToScanning(message: String, resumeDelayMs: Long) {
        cancelNetworkRequest()
        mainHandler.removeCallbacks(verifyRunnable)
        pendingPayload = null
        activeStrategyName = null
        awaitingPrivateFlowReturn = false
        connectionStage = ConnectionStage.SCANNING
        scanBlockedUntilMs = System.currentTimeMillis() + resumeDelayMs
        tvStatus.text = message
        Log.i(TAG, "reset to scanning delayMs=$resumeDelayMs message=$message")
        if (resumeDelayMs <= 0L) {
            tvStatus.setText(R.string.wifi_scan_waiting)
            startScanLoop()
            return
        }
        mainHandler.postDelayed({
            if (!isFinishing && connectionStage == ConnectionStage.SCANNING) {
                tvStatus.setText(R.string.wifi_scan_waiting)
                startScanLoop()
            }
        }, resumeDelayMs)
    }

    private fun showTemporaryStatus(message: String, resumeDelayMs: Long) {
        connectionStage = ConnectionStage.SCANNING
        scanBlockedUntilMs = System.currentTimeMillis() + resumeDelayMs
        tvStatus.text = message
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed({
            if (connectionStage == ConnectionStage.SCANNING && !isFinishing) {
                tvStatus.setText(R.string.wifi_scan_waiting)
                startScanLoop()
            }
        }, resumeDelayMs)
    }

    private fun privateWifiEntries(): List<PrivateWifiEntry> {
        // 当前公开资料没有确认可用的 Rokid 私有 Wi‑Fi 扫码组件，这里保留集中配置入口，
        // 后续拿到真实组件名后只需补这一处即可。
        return emptyList()
    }

    private fun showResultAndFinish(message: String) {
        connectionStage = ConnectionStage.SHOWING_RESULT
        val isSuccess = message == getString(R.string.wifi_scan_success_generic)

        cameraPreviewView.visibility = View.INVISIBLE
        viewfinder.visibility = View.INVISIBLE
        tvScanHint.visibility = View.GONE
        infoCard.visibility = View.GONE
        val scanFrame = findViewById<View>(R.id.scanFrame)
        scanFrame.background = null

        resultContent.visibility = View.VISIBLE
        ivResultIcon.setImageResource(if (isSuccess) R.drawable.ic_check_circle else R.drawable.ic_close_circle)
        tvResultStatusInFrame.text =
            if (isSuccess) getString(R.string.wifi_scan_result_success) else getString(R.string.wifi_scan_result_failed)

        if (!isSuccess) {
            tvErrorDetail.visibility = View.VISIBLE
        }

        bottomHints.visibility = View.VISIBLE
        if (isSuccess) {
            tvVoiceHint.setText(R.string.wifi_scan_voice_hint)
            tvTouchHint.setText(R.string.wifi_scan_touch_hint)
        } else {
            tvVoiceHint.setText(R.string.wifi_scan_voice_hint_retry)
            tvTouchHint.setText(R.string.wifi_scan_touch_hint_retry)
        }

        releaseScanResources()
        mainHandler.postDelayed({
            if (!isFinishing) {
                val targetClassName = nextAfterSuccess
                if (isSuccess && !targetClassName.isNullOrBlank()) {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val targetClass = Class.forName(targetClassName) as Class<out Activity>
                        startActivity(Intent(this, targetClass))
                    }.onFailure {
                        finish()
                    }
                }
                finish()
            }
        }, RESULT_STAY_MS)
    }

    private fun finishDirectly() {
        releaseScanResources()
        finish()
    }

    private fun releaseScanResources() {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.removeCallbacks(verifyRunnable)
        cancelNetworkRequest()
        stopCameraPipeline()
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
}
