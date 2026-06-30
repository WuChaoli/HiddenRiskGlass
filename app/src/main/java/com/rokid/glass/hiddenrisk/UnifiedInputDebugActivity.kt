package com.rokid.glass.hiddenrisk

import android.os.Bundle
import android.widget.TextView
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一输入调试页。
 * 仅验证三路输入到动作分发的闭环，不承载正式业务副作用。
 */
class UnifiedInputDebugActivity : BaseGlassActivity() {

    private enum class DebugProfile {
        FULL,
        GESTURE_ONLY,
    }

    private lateinit var tvProfile: TextView
    private lateinit var tvLastEvent: TextView
    private lateinit var tvActions: TextView
    private lateinit var tvEvents: TextView
    private lateinit var tvHint: TextView

    private val inputSession by lazy {
        UnifiedInputSession(this, TAG)
    }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val recentEvents = ArrayDeque<String>()

    private var currentProfile = DebugProfile.FULL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unified_input_debug)

        tvProfile = findViewById(R.id.tvDebugProfile)
        tvLastEvent = findViewById(R.id.tvDebugLastEvent)
        tvActions = findViewById(R.id.tvDebugActions)
        tvEvents = findViewById(R.id.tvDebugEvents)
        tvHint = findViewById(R.id.tvDebugHint)

        rebuildActions()
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        rebuildActions()
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun rebuildActions() {
        val actions = buildInputActions()
        inputSession.updateActions(actions)
        tvProfile.text = getString(R.string.unified_input_debug_profile_value, profileLabel(currentProfile))
        tvActions.text = actions.joinToString(separator = "\n") { spec ->
            "• ${spec.label}  <-  ${spec.triggers.joinToString(" / ") { describeTrigger(it) }}"
        }
        tvHint.text = when (currentProfile) {
            DebugProfile.FULL -> getString(R.string.unified_input_debug_hint_full)
            DebugProfile.GESTURE_ONLY -> getString(R.string.unified_input_debug_hint_gesture_only)
        }
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        val confirmTriggers = UnifiedInputSession.buildConfirmTriggers(enableHeadGesture = false)
        val cancelTriggers = UnifiedInputSession.buildCancelTriggers(enableHeadGesture = false)
        val fullOnly = currentProfile == DebugProfile.FULL

        return buildList {
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = "确认 / 切换模式",
                    triggers = confirmTriggers,
                ) { event ->
                    recordEvent("动作 Confirm", event)
                    currentProfile = if (currentProfile == DebugProfile.FULL) {
                        DebugProfile.GESTURE_ONLY
                    } else {
                        DebugProfile.FULL
                    }
                    rebuildActions()
                },
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Cancel,
                    label = "取消 / 退出",
                    triggers = cancelTriggers,
                ) { event ->
                    recordEvent("动作 Cancel", event)
                    finish()
                },
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Next,
                    label = "下一个",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                        UnifiedInputSession.InputTrigger.Voice("下一个", "xia yi ge"),
                    ),
                    enabled = { fullOnly },
                ) { event ->
                    recordEvent("动作 Next", event)
                },
            )
            add(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Previous,
                    label = "上一个",
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                        UnifiedInputSession.InputTrigger.Voice("上一个", "shang yi ge"),
                    ),
                    enabled = { fullOnly },
                ) { event ->
                    recordEvent("动作 Previous", event)
                },
            )
        }
    }

    private fun recordEvent(actionLabel: String, event: UnifiedInputSession.InputEvent) {
        val line = "${formatTime(event.timestampMillis)}  $actionLabel <- ${describeSource(event.source)} (${describeTrigger(event.trigger)})"
        tvLastEvent.text = line
        recentEvents.addFirst(line)
        while (recentEvents.size > MAX_EVENT_LINES) {
            recentEvents.removeLast()
        }
        tvEvents.text = recentEvents.joinToString(separator = "\n")
    }

    private fun profileLabel(profile: DebugProfile): String {
        return when (profile) {
            DebugProfile.FULL -> "FULL"
            DebugProfile.GESTURE_ONLY -> "GESTURE_ONLY"
        }
    }

    private fun describeSource(source: UnifiedInputSession.InputSource): String {
        return when (source) {
            UnifiedInputSession.InputSource.VOICE -> "语音"
            UnifiedInputSession.InputSource.TOUCH -> "触控"
            UnifiedInputSession.InputSource.HEAD_GESTURE -> "陀螺仪"
        }
    }

    private fun describeTrigger(trigger: UnifiedInputSession.InputTrigger): String {
        return when (trigger) {
            is UnifiedInputSession.InputTrigger.Voice -> "语音:${trigger.command}"
            is UnifiedInputSession.InputTrigger.Touch -> when (trigger.key) {
                UnifiedInputSession.InputKey.CLICK -> "触控:单击"
                UnifiedInputSession.InputKey.DOUBLE_CLICK -> "触控:双击"
                UnifiedInputSession.InputKey.FRONT -> "触控:前滑"
                UnifiedInputSession.InputKey.BEHIND -> "触控:后滑"
                UnifiedInputSession.InputKey.BACK -> "触控:返回"
                else -> "触控:${trigger.key}"
            }
            is UnifiedInputSession.InputTrigger.HeadGesture -> "动作:${trigger.type.name}"
        }
    }

    private fun formatTime(timestampMillis: Long): String {
        return timeFormat.format(Date(timestampMillis))
    }

    companion object {
        private const val TAG = "UnifiedInputDebug"
        private const val MAX_EVENT_LINES = 8
    }
}
