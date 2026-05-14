package com.rokid.glass.input

/**
 * 自动睡眠的纯状态机，便于单元测试覆盖计时与输入恢复语义。
 */
class AutoSleepStateMachine(
    private val idleBeforePromptMs: Long,
    private val promptTimeoutMs: Long,
) {
    enum class UserActivitySource {
        TOUCH,
        VOICE,
        HEAD_MOTION,
    }

    sealed class Event {
        object PromptShown : Event()
        data class ResumeRequested(val source: UserActivitySource) : Event()
        object TimeoutReturnToMenu : Event()
    }

    private var enabled = false
    private var promptVisible = false
    private var promptShownAtMillis: Long? = null

    fun setEnabled(enabled: Boolean): List<Event> {
        this.enabled = enabled
        if (!enabled) {
            promptVisible = false
            promptShownAtMillis = null
        }
        return emptyList()
    }

    fun onIdleQualified(nowMillis: Long): List<Event> {
        if (!enabled || promptVisible || idleBeforePromptMs <= 0L) {
            return emptyList()
        }
        promptVisible = true
        promptShownAtMillis = nowMillis
        return listOf(Event.PromptShown)
    }

    fun notifyUserActivity(source: UserActivitySource): List<Event> {
        if (!enabled) {
            return emptyList()
        }
        if (!promptVisible) {
            return emptyList()
        }
        promptVisible = false
        promptShownAtMillis = null
        return listOf(Event.ResumeRequested(source))
    }

    fun tick(nowMillis: Long): List<Event> {
        val shownAt = promptShownAtMillis
        if (!enabled || !promptVisible || shownAt == null || promptTimeoutMs <= 0L) {
            return emptyList()
        }
        if (nowMillis - shownAt < promptTimeoutMs) {
            return emptyList()
        }
        promptVisible = false
        promptShownAtMillis = null
        return listOf(Event.TimeoutReturnToMenu)
    }

    fun isPromptVisible(): Boolean = promptVisible
}
