package com.rokid.glass

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import com.rokid.glesse.R
import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.InspectionLoadingActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.LightshotActivity
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glass.hiddenrisk.UnifiedInputDebugActivity
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback

/**
 * 巡检模式选择页面。
 * 提供 AI识患、任务检查、闪拍、扫一扫、统一输入调试 五个选项，默认选中 AI识患。
 * 前后滑动切换选项，单击确认进入；也支持语音命令直接跳转。
 */
class InspectionModeActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "InspectionModeActivity"
        private const val VOICE_REGISTER_RETRY_MS = 500L
    }

    private lateinit var itemAiInspection: FrameLayout
    private lateinit var itemTaskInspection: FrameLayout
    private lateinit var itemLightshot: FrameLayout
    private lateinit var itemQrScan: FrameLayout
    private lateinit var itemUnifiedInputDebug: FrameLayout
    private lateinit var tvBottomHint: TextView

    private var selectedIndex = 0
    private val itemCount = 5
    private var isPageVisible = false
    private var isVoiceCommandsRegistered = false
    private var voiceRegisterRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hintResetRunnable = Runnable {
        if (!isFinishing) {
            tvBottomHint.setText(R.string.inspection_mode_hint)
        }
    }
    private val voiceRegisterRetryRunnable = object : Runnable {
        override fun run() {
            if (!isPageVisible || isVoiceCommandsRegistered) {
                return
            }
            if (registerVoiceCommandsIfReady()) {
                return
            }
            RokidSdkManager.ensureInitialized()
            mainHandler.postDelayed(this, VOICE_REGISTER_RETRY_MS)
        }
    }

    // 语音命令：说出名称直接跳转对应页面
    // AI识患 兼容多种读音
    private val voiceAiInspection1 = VoiceAction("AI识患", "ei ai shi huan", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { startActivity(Intent(this@InspectionModeActivity, InspectionLoadingActivity::class.java)) }
        }
    })
    private val voiceAiInspection2 = VoiceAction("AI识患", "ei a shi huan", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { startActivity(Intent(this@InspectionModeActivity, InspectionLoadingActivity::class.java)) }
        }
    })
    private val voiceTaskInspection = VoiceAction("任务检查", "ren wu jian cha", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                selectedIndex = 1
                updateSelection()
                tvBottomHint.text = "任务检查模式开发中..."
            }
        }
    })
    private val voiceLightshot = VoiceAction("闪拍", "shan pai", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { startActivity(Intent(this@InspectionModeActivity, LightshotActivity::class.java)) }
        }
    })
    private val voiceQrScan = VoiceAction("扫一扫", "sao yi sao", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { launchWifiQrScan() }
        }
    })
    private val voiceUnifiedInputDebug = VoiceAction("统一输入调试", "tong yi shu ru tiao shi", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { startActivity(Intent(this@InspectionModeActivity, UnifiedInputDebugActivity::class.java)) }
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_mode)

        itemAiInspection = findViewById(R.id.itemAiInspection)
        itemTaskInspection = findViewById(R.id.itemTaskInspection)
        itemLightshot = findViewById(R.id.itemLightshot)
        itemQrScan = findViewById(R.id.itemQrScan)
        itemUnifiedInputDebug = findViewById(R.id.itemUnifiedInputDebug)
        tvBottomHint = findViewById(R.id.tvBottomHint)

        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()

        updateSelection()
    }

    override fun onResume() {
        super.onResume()
        isPageVisible = true
        RokidSdkManager.ensureInitialized()
        scheduleVoiceRegisterRetry(immediate = true)
    }

    override fun onPause() {
        isPageVisible = false
        stopVoiceRegisterRetry()
        mainHandler.removeCallbacks(hintResetRunnable)
        unregisterVoiceCommands()
        super.onPause()
    }

    override fun onDestroy() {
        stopVoiceRegisterRetry()
        mainHandler.removeCallbacks(hintResetRunnable)
        unregisterVoiceCommands()
        RokidSdkManager.removeListener(this)
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                selectedIndex = (selectedIndex + 1).coerceAtMost(itemCount - 1)
                updateSelection()
                true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                updateSelection()
                true
            }
            GlassKeyEvent.KEYCODE_CLICK -> {
                onItemConfirmed()
                true
            }
            GlassKeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    /** 根据 selectedIndex 更新各个选项的高亮背景 */
    private fun updateSelection() {
        itemAiInspection.setBackgroundResource(
            if (selectedIndex == 0) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemTaskInspection.setBackgroundResource(
            if (selectedIndex == 1) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemLightshot.setBackgroundResource(
            if (selectedIndex == 2) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemQrScan.setBackgroundResource(
            if (selectedIndex == 3) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
        itemUnifiedInputDebug.setBackgroundResource(
            if (selectedIndex == 4) R.drawable.inspection_mode_item_bg_selected
            else R.drawable.inspection_mode_item_bg
        )
    }

    /** 确认当前选中项，跳转对应页面 */
    private fun onItemConfirmed() {
        when (selectedIndex) {
            0 -> {
                // AI识患：进入加载页面
                startActivity(Intent(this, InspectionLoadingActivity::class.java))
            }
            1 -> {
                // 任务检查：暂未实现
                showBottomHint("任务检查模式开发中...")
            }
            2 -> {
                // 闪拍：批量采集模型测试样本
                startActivity(Intent(this, LightshotActivity::class.java))
            }
            3 -> {
                launchWifiQrScan()
            }
            4 -> {
                startActivity(Intent(this, UnifiedInputDebugActivity::class.java))
            }
        }
    }

    private fun launchWifiQrScan() {
        mainHandler.removeCallbacks(hintResetRunnable)
        startActivity(Intent(this, WifiQrScanActivity::class.java))
    }

    private fun showBottomHint(message: String, durationMs: Long = 2200L) {
        tvBottomHint.text = message
        mainHandler.removeCallbacks(hintResetRunnable)
        mainHandler.postDelayed(hintResetRunnable, durationMs)
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        Log.i(TAG, "sdk state=$state error=${RokidSdkManager.lastErrorMessage ?: "N/A"}")
        if (!isPageVisible) {
            return
        }
        if (state == RokidSdkManager.SdkState.READY) {
            scheduleVoiceRegisterRetry(immediate = true)
        } else if (state == RokidSdkManager.SdkState.FAILED) {
            Log.w(TAG, "SDK 初始化失败，继续等待重试")
        }
    }

    private fun scheduleVoiceRegisterRetry(immediate: Boolean) {
        if (!isPageVisible || isVoiceCommandsRegistered) {
            return
        }
        mainHandler.removeCallbacks(voiceRegisterRetryRunnable)
        if (immediate) {
            mainHandler.post(voiceRegisterRetryRunnable)
        } else {
            mainHandler.postDelayed(voiceRegisterRetryRunnable, VOICE_REGISTER_RETRY_MS)
        }
    }

    private fun stopVoiceRegisterRetry() {
        mainHandler.removeCallbacks(voiceRegisterRetryRunnable)
        voiceRegisterRetryCount = 0
    }

    private fun registerVoiceCommandsIfReady(): Boolean {
        if (!isPageVisible || isVoiceCommandsRegistered) {
            return false
        }

        val offlineCmdService = runCatching { GlassSdk.getGlassOfflineCmdService() }
            .onFailure { error ->
                Log.w(TAG, "获取离线语音服务失败: ${error.message}")
            }
            .getOrNull()
        if (offlineCmdService == null) {
            voiceRegisterRetryCount++
            if (voiceRegisterRetryCount == 1 || voiceRegisterRetryCount % 10 == 0) {
                Log.w(
                    TAG,
                    "离线语音服务未就绪，继续重试: attempt=$voiceRegisterRetryCount sdkState=${RokidSdkManager.state}"
                )
            }
            return false
        }

        offlineCmdService.add(voiceAiInspection1)
        offlineCmdService.add(voiceAiInspection2)
        offlineCmdService.add(voiceTaskInspection)
        offlineCmdService.add(voiceLightshot)
        offlineCmdService.add(voiceQrScan)
        offlineCmdService.add(voiceUnifiedInputDebug)
        isVoiceCommandsRegistered = true
        voiceRegisterRetryCount = 0
        Log.i(TAG, "已注册巡检模式语音命令")
        return true
    }

    private fun unregisterVoiceCommands() {
        if (!isVoiceCommandsRegistered) {
            return
        }

        GlassSdk.getGlassOfflineCmdService()?.run {
            remove(voiceAiInspection1)
            remove(voiceAiInspection2)
            remove(voiceTaskInspection)
            remove(voiceLightshot)
            remove(voiceQrScan)
            remove(voiceUnifiedInputDebug)
        }
        isVoiceCommandsRegistered = false
        Log.i(TAG, "已移除巡检模式语音命令")
    }
}
