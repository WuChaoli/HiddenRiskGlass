package com.rokid.glass.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.hiddenrisk.HeadGestureManager
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback

/**
 * 仅允许当前前台会话管理应用离线词表，防止旧页面延迟 detach 清空新页面词表。
 */
internal class VoiceVocabularyOwnerState<T> {
    private var owner: T? = null

    fun activate(nextOwner: T): T? {
        val previous = owner
        owner = nextOwner
        return previous?.takeIf { it !== nextOwner }
    }

    fun release(candidate: T): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }

    fun isOwner(candidate: T): Boolean = owner === candidate
}

/**
 * 统一输入注册层。
 * 页面只声明业务动作与触发源，不直接分别管理语音、触控、陀螺仪注册。
 */
class UnifiedInputSession(
    context: Context,
    private val ownerTag: String,
    private val onInputActivity: ((InputEvent) -> Unit)? = null,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val voiceAdapter = VoiceInputAdapter(ownerTag, mainHandler)
    private val gestureAdapter = HeadGestureInputAdapter(appContext, ownerTag)

    private var attached = false
    private var actionSpecs: List<InputActionSpec> = emptyList()

    fun attach() {
        if (attached) {
            syncAdapters()
            return
        }
        attached = true
        syncAdapters()
    }

    fun detach() {
        if (!attached) {
            return
        }
        attached = false
        voiceAdapter.detach()
        gestureAdapter.detach()
    }

    fun release() {
        detach()
        actionSpecs = emptyList()
    }

    fun updateActions(specs: List<InputActionSpec>) {
        actionSpecs = specs
        if (attached) {
            syncAdapters()
        }
    }

    fun dispatchTouch(key: Int): Boolean {
        return dispatchTrigger(InputTrigger.Touch(key))
    }

    private fun dispatchTrigger(trigger: InputTrigger): Boolean {
        val spec = actionSpecs.firstOrNull { candidate ->
            candidate.enabled() && candidate.triggers.any { it.matches(trigger) }
        } ?: return false

        val event = InputEvent(
            actionId = spec.id,
            source = trigger.source,
            trigger = trigger,
            timestampMillis = System.currentTimeMillis(),
        )
        onInputActivity?.invoke(event)
        spec.onTrigger(event)
        return true
    }

    private fun syncAdapters() {
        if (!attached) {
            return
        }
        voiceAdapter.attach(actionSpecs) { trigger -> dispatchTrigger(trigger) }
        if (HEAD_GESTURE_LISTENING_ENABLED) {
            gestureAdapter.attach(actionSpecs) { trigger -> dispatchTrigger(trigger) }
        } else {
            gestureAdapter.detach()
        }
    }

    data class InputActionId(val value: String) {
        companion object {
            val Confirm = InputActionId("confirm")
            val Cancel = InputActionId("cancel")
            val Exit = InputActionId("exit")
            val Next = InputActionId("next")
            val Previous = InputActionId("previous")
            val DebugNod = InputActionId("debug_nod")
            val DebugShake = InputActionId("debug_shake")
        }
    }

    object InputKey {
        const val CLICK = 100003
        const val DOUBLE_CLICK = 100004
        const val FRONT = android.view.KeyEvent.KEYCODE_DPAD_LEFT
        const val BEHIND = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        const val BACK = android.view.KeyEvent.KEYCODE_BACK
    }

    sealed class InputTrigger {
        abstract val source: InputSource

        data class Voice(
            val command: String,
            val pinyin: String,
        ) : InputTrigger() {
            override val source: InputSource = InputSource.VOICE
        }

        data class Touch(
            val key: Int,
        ) : InputTrigger() {
            override val source: InputSource = InputSource.TOUCH
        }

        data class HeadGesture(
            val type: HeadGestureManager.HeadGestureType,
        ) : InputTrigger() {
            override val source: InputSource = InputSource.HEAD_GESTURE
        }

        fun matches(other: InputTrigger): Boolean {
            return when {
                this is Voice && other is Voice -> command == other.command && pinyin == other.pinyin
                this is Touch && other is Touch -> key == other.key
                this is HeadGesture && other is HeadGesture -> type == other.type
                else -> false
            }
        }
    }

    enum class InputSource {
        VOICE,
        TOUCH,
        HEAD_GESTURE,
    }

    data class InputEvent(
        val actionId: InputActionId,
        val source: InputSource,
        val trigger: InputTrigger,
        val timestampMillis: Long,
    )

    data class InputActionSpec(
        val id: InputActionId,
        val label: String,
        val triggers: List<InputTrigger>,
        val enabled: () -> Boolean = { true },
        val onTrigger: (InputEvent) -> Unit,
    )

    companion object {
        private const val HEAD_GESTURE_LISTENING_ENABLED = false

        fun buildConfirmTriggers(enableHeadGesture: Boolean): List<InputTrigger> {
            return buildList {
                add(InputTrigger.Touch(InputKey.CLICK))
                add(InputTrigger.Voice("确认", "que ren"))
                if (enableHeadGesture) {
                    add(InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                }
            }
        }

        fun buildCancelTriggers(enableHeadGesture: Boolean): List<InputTrigger> {
            return buildList {
                add(InputTrigger.Touch(InputKey.BACK))
                add(InputTrigger.Touch(InputKey.DOUBLE_CLICK))
                add(InputTrigger.Voice("取消", "qu xiao"))
                if (enableHeadGesture) {
                    add(InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                }
            }
        }
    }

    private class VoiceInputAdapter(
        private val ownerTag: String,
        private val mainHandler: Handler,
    ) {
        private val retryRunnable = object : Runnable {
            override fun run() {
                if (!attached) {
                    return
                }
                if (tryRegisterVoiceActions()) {
                    return
                }
                mainHandler.postDelayed(this, VOICE_RETRY_DELAY_MS)
            }
        }

        private var attached = false
        private var registered = false
        private var specs: List<InputActionSpec> = emptyList()
        private var triggerDispatcher: ((InputTrigger) -> Boolean)? = null
        private var currentVoiceTriggers: List<InputTrigger.Voice> = emptyList()
        private var registeredVoiceActions: List<VoiceAction> = emptyList()
        private var registeredLanguage: String? = null
        private var registeredByVocabularyOverride = false

        fun attach(
            actionSpecs: List<InputActionSpec>,
            onTrigger: (InputTrigger) -> Boolean,
        ) {
            specs = actionSpecs
            triggerDispatcher = onTrigger
            attached = true
            synchronized(ownerLock) {
                ownerState.activate(this)?.unregisterVoiceActions()
            }
            mainHandler.removeCallbacks(retryRunnable)
            val nextVoiceTriggers = collectVoiceTriggers()
            if (nextVoiceTriggers != currentVoiceTriggers) {
                unregisterVoiceActions()
                currentVoiceTriggers = nextVoiceTriggers
            }
            if (currentVoiceTriggers.isEmpty()) {
                return
            }
            if (!registered) {
                mainHandler.post(retryRunnable)
            }
        }

        fun detach() {
            attached = false
            triggerDispatcher = null
            specs = emptyList()
            currentVoiceTriggers = emptyList()
            mainHandler.removeCallbacks(retryRunnable)
            synchronized(ownerLock) {
                if (ownerState.release(this)) {
                    unregisterVoiceActions()
                }
            }
        }

        private fun tryRegisterVoiceActions(): Boolean {
            synchronized(ownerLock) {
                if (!ownerState.isOwner(this)) return true
            }
            val triggerSpecs = currentVoiceTriggers
            if (triggerSpecs.isEmpty()) {
                return true
            }

            val service = runCatching { GlassSdk.getGlassOfflineCmdService() }
                .onFailure { error ->
                    Log.w(ownerTag, "统一输入语音服务未就绪: ${error.message}")
                }
                .getOrNull() ?: return false

            val actions = triggerSpecs.map { voiceTrigger ->
                VoiceAction(voiceTrigger.command, voiceTrigger.pinyin, object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        mainHandler.post {
                            triggerDispatcher?.invoke(voiceTrigger)
                        }
                    }
                })
            }
            val activeLanguage = runCatching { service.language?.trim().orEmpty() }
                .onFailure { error -> Log.w(ownerTag, "统一输入读取离线语言失败，回退逐条注册: ${error.message}") }
                .getOrDefault("")
            if (activeLanguage.isNotBlank()) {
                val covered = runCatching { GlassSdk.setOfflineCmdWords(activeLanguage, actions) }
                    .onFailure { error -> Log.w(ownerTag, "统一输入词表覆盖异常，回退逐条注册: ${error.message}") }
                    .getOrDefault(false)
                if (covered) {
                    registeredVoiceActions = actions
                    registeredLanguage = activeLanguage
                    registeredByVocabularyOverride = true
                    registered = true
                    Log.i(ownerTag, "统一输入词表覆盖成功 language=$activeLanguage: ${triggerSpecs.joinToString { it.command }}")
                    return true
                }
                Log.w(ownerTag, "统一输入词表覆盖返回失败 language=$activeLanguage，回退逐条注册")
            } else {
                Log.w(ownerTag, "统一输入离线语言为空，回退逐条注册")
            }
            return runCatching {
                actions.forEach(service::add)
                registeredVoiceActions = actions
                registeredLanguage = null
                registeredByVocabularyOverride = false
                registered = true
                Log.i(ownerTag, "统一输入逐条语音注册成功: ${triggerSpecs.joinToString { it.command }}")
                true
            }.onFailure { error ->
                Log.w(ownerTag, "统一输入逐条语音注册失败: ${error.message}")
            }.getOrDefault(false)
        }

        private fun unregisterVoiceActions() {
            if (!registered) {
                return
            }
            runCatching {
                val service = GlassSdk.getGlassOfflineCmdService()
                val cleared = if (registeredByVocabularyOverride && registeredLanguage != null) {
                    runCatching { GlassSdk.clearOfflineCmdWords(registeredLanguage!!) }
                        .onFailure { error -> Log.w(ownerTag, "统一输入词表清空异常，回退逐条注销: ${error.message}") }
                        .getOrDefault(false)
                } else {
                    false
                }
                if (!cleared) {
                    if (registeredByVocabularyOverride) {
                        Log.w(ownerTag, "统一输入词表清空返回失败，回退逐条注销")
                    }
                    service?.let { activeService ->
                        registeredVoiceActions.forEach(activeService::remove)
                    }
                }
            }.onFailure { error ->
                Log.w(ownerTag, "统一输入语音注销失败: ${error.message}")
            }
            registered = false
            registeredVoiceActions = emptyList()
            registeredLanguage = null
            registeredByVocabularyOverride = false
        }

        private fun collectVoiceTriggers(): List<InputTrigger.Voice> {
            return specs
                .filter { it.enabled() }
                .flatMap { spec -> spec.triggers }
                .mapNotNull { trigger -> trigger as? InputTrigger.Voice }
                .distinct()
        }

        companion object {
            private const val VOICE_RETRY_DELAY_MS = 500L
            private val ownerLock = Any()
            private val ownerState = VoiceVocabularyOwnerState<VoiceInputAdapter>()
        }
    }

    private class HeadGestureInputAdapter(
        private val context: Context,
        private val ownerTag: String,
    ) {
        private val listener = object : HeadGestureManager.Listener {
            override fun onHeadGesture(event: HeadGestureManager.HeadGestureEvent) {
                val trigger = InputTrigger.HeadGesture(event.type)
                triggerDispatcher?.invoke(trigger)
            }
        }

        private var listening = false
        private var triggerDispatcher: ((InputTrigger) -> Boolean)? = null

        fun attach(
            actionSpecs: List<InputActionSpec>,
            onTrigger: (InputTrigger) -> Boolean,
        ) {
            triggerDispatcher = onTrigger
            val requiresGesture = actionSpecs.any { spec ->
                spec.enabled() && spec.triggers.any { it is InputTrigger.HeadGesture }
            }
            if (!requiresGesture) {
                detach()
                return
            }
            HeadGestureManager.initialize(context)
            if (!HeadGestureManager.isSupported()) {
                Log.w(ownerTag, "统一输入头部动作不可用，设备缺少所需传感器")
                return
            }
            if (!listening) {
                HeadGestureManager.addListener(listener)
                HeadGestureManager.start()
                listening = true
                Log.i(ownerTag, "统一输入头部动作监听已启动")
            }
        }

        fun detach() {
            triggerDispatcher = null
            if (!listening) {
                return
            }
            HeadGestureManager.removeListener(listener)
            HeadGestureManager.stop()
            listening = false
            Log.i(ownerTag, "统一输入头部动作监听已停止")
        }
    }
}
